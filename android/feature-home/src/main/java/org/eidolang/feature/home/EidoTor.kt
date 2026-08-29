package org.eidolang.feature.home

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import org.eidolang.core.hardening.AndroidLocalSecretBox
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLock
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager
import org.briarproject.onionwrapper.AndroidTorWrapper
import org.briarproject.onionwrapper.CircumventionProvider
import org.briarproject.onionwrapper.CircumventionProviderFactory
import org.briarproject.onionwrapper.TorWrapper
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The onion service key, derived from the history-vault recovery secret rather than generated.
 *
 * The address has to survive a reinstall, and nothing stored on the device can: AndroidKeyStore
 * entries are bound to the app's UID and are destroyed with it — measured in
 * `KeystoreSurvivesUninstallTest`, not assumed. The only thing that already survives is the recovery
 * secret, because the owner exports it as the recovery backup and code.
 *
 * So the key is not kept, it is *recomputed*. Nothing new is stored, nothing new goes on the wire,
 * and the frozen R20.2/R21 canonical formats are untouched — which matters, because putting the key
 * inside the recovery backup body would have changed bytes that earlier releases' golden vectors
 * pin. The cost is stated plainly: whoever holds the recovery pair can reconstruct this address, so
 * the address is exactly as secret as the recovery code.
 */
object OnionServiceKey {

    /** The domain string. Changing it changes every derived address, so it is frozen here. */
    private const val DOMAIN = "eidolang.onion.v1"

    fun fromRecoverySecret(secret: ByteArray): String {
        require(secret.size == 32) { "recovery secret must be 32 bytes" }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
        }
        return blobFromSeed(mac.doFinal(DOMAIN.toByteArray(Charsets.US_ASCII)))
    }

    /**
     * Tor's `ED25519-V3` blob is the *expanded* secret key, not the seed.
     *
     * It is the clamped SHA-512 of the seed — the same representation that cost a run in the DHT
     * work, where the NaCl `seed || public` layout passed every length check and was then rejected by
     * every storing node. Here the failure would be quieter still: ADD_ONION would simply produce a
     * different address than intended.
     */
    fun blobFromSeed(seed: ByteArray): String {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val h = java.security.MessageDigest.getInstance("SHA-512").digest(seed)
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = (h[31].toInt() and 127).toByte()
        h[31] = (h[31].toInt() or 64).toByte()
        return "ED25519-V3:" + android.util.Base64.encodeToString(h, android.util.Base64.NO_WRAP)
    }
}

/**
 * Tor, and this device's own onion address.
 *
 * Why this exists at all: a v3 onion address is reachable from anywhere without a single hole
 * punched in a single NAT, because both ends only ever make outgoing connections. That removes the
 * one job the relay was actually needed for. The relay was never able to read anything; after this
 * it is not needed for reachability either.
 *
 * The consequence that decides the rest of the design: **Tor carries TCP only**. The BitTorrent DHT
 * is UDP, so it cannot run over Tor at all. That is not a limitation to work around — with an onion
 * address there is nothing left for the DHT to do. The DHT existed to answer "where is this person";
 * an onion address *is* the answer, and it travels on the contact card.
 *
 * The service key is kept so the address survives a restart. An address that changed on every launch
 * would mean every contact card went stale the moment the app was killed.
 */
class EidoTor private constructor(
    private val tor: TorWrapper,
    private val keyFile: File,
    private val country: String,
    private val appContext: Context,
) {

    /** Port the onion service advertises; the local socket it forwards to is [LOCAL_PORT]. */
    val onionPort: Int get() = ONION_PORT

    @Volatile
    var onionAddress: String? = null
        private set

    /** Tor's own state name. A String, so the wrapper library stops at this module's boundary. */
    val state: String get() = tor.torState.name

    /** How each route was tried, in order. Empty until [startAndPublish] runs. */
    val attempts: List<Attempt> get() = tried.toList()

    /** One route to the Tor network and what it cost. [route] is "direct" or a bridge type name. */
    data class Attempt(val route: String, val seconds: Long, val connected: Boolean)

    private val tried = mutableListOf<Attempt>()
    private val circumvention: CircumventionProvider by lazy {
        CircumventionProviderFactory.createCircumventionProvider()
    }

    /**
     * Start Tor and publish the onion service, blocking until Tor reports CONNECTED.
     *
     * Tries a direct connection first, then pluggable-transport bridges — obfs4, meek, snowflake —
     * in the order the circumvention data recommends for [country]. Direct is tried first even in a
     * country known to block Tor, because it is much faster when it works and the phone may be on a
     * VPN; and bridges are tried even where the data says they are unnecessary, because "this
     * country blocks Tor" is a statement about the country, not about this particular network.
     *
     * @return the onion address, or null if no route connected within [timeoutMs].
     */
    fun startAndPublish(
        timeoutMs: Long = TOTAL_TIMEOUT_MS,
        /**
         * A key derived from the recovery secret, when the owner has one. It wins over whatever is
         * stored locally: after a restore the stored copy is either absent or belongs to the old
         * installation, and the derived one is the address the contacts already hold.
         */
        derivedKey: String? = null,
    ): String? {
        tor.start()
        tor.enableNetwork(true)
        val deadline = System.currentTimeMillis() + timeoutMs

        // Direct is still probed where Tor is known to be blocked — the phone may be on a VPN, and
        // then it is by far the fastest route — but only briefly. A full-length direct attempt there
        // is a minute of startup spent on an outcome the circumvention data already predicts.
        val directBudget =
            if (circumvention.shouldUseBridges(country)) DIRECT_PROBE_MS else DIRECT_TIMEOUT_MS
        if (!awaitConnected("direct", directBudget, deadline)) {
            val types = circumvention.getSuitableBridgeTypes(country).toList()
            for ((index, type) in types.withIndex()) {
                if (System.currentTimeMillis() >= deadline) break
                val bridges = runCatching { circumvention.getBridges(type, country) }.getOrDefault(emptyList())
                if (bridges.isEmpty()) continue
                // Reconfiguring bridges on a running Tor leaves the managed proxy dead: the log
                // shows `Managed proxy "N/A" process terminated with status code 0` followed by
                // `No running bridges` and no new proxy, so every attempt after the first was
                // guaranteed to fail. Only a restart brings a fresh transport process up.
                if (index > 0) restartTor()
                tor.enableBridges(bridges)
                // Share out what is left instead of a fixed slice. The fixed 120 s threw away a
                // bridge that had connected and reached 75%, and then left 220 s of the 480 s
                // budget unspent — the run gave up at 260 s having stopped trying at 260 s.
                val remaining = deadline - System.currentTimeMillis()
                val share = remaining / (types.size - index).coerceAtLeast(1)
                if (awaitConnected(type.name, maxOf(share, BRIDGE_MIN_MS), deadline)) break
            }
        }
        // Whatever is configured now, keep waiting on it while the budget lasts. Tor climbing to
        // 75% and pausing there is normal on a slow bridge; abandoning it is what turned a slow
        // connection into no connection.
        if (tor.torState != TorWrapper.TorState.CONNECTED) {
            val left = deadline - System.currentTimeMillis()
            if (left > 0) awaitConnected("wait", left, deadline)
        }
        if (tor.torState != TorWrapper.TorState.CONNECTED) return null

        val existing = derivedKey ?: readKey()
        // Argument order is the opposite of what the parameter names say. The library declares
        // `publishHiddenService(port, localPort, key)` but builds ADD_ONION as
        // `Port=<localPort>,127.0.0.1:<port>` — so the *first* argument is the local target and the
        // *second* is the virtual port on the onion. Passing them in named order publishes a service
        // on onion port 6883 forwarding to local port 80, where nothing listens, and every connection
        // comes back as SOCKS "connection refused" — indistinguishable from a firewall.
        val props = tor.publishHiddenService(LOCAL_PORT, ONION_PORT, existing?.takeIf { it.isNotEmpty() })
        // Sealed either way: a derived key is cheap to recompute but the secret it comes from is not
        // always to hand, and Tor needs the blob on every launch.
        if (existing.isNullOrEmpty() || derivedKey != null) writeKey(props.privKey)
        // The wrapper hands back the bare 56-character base32 name. Everything downstream — contact
        // cards, SOCKS connects — wants a hostname, so the suffix is added once, here, rather than
        // at each of those call sites.
        onionAddress = props.onion.removeSuffix(".onion") + ".onion"
        return onionAddress
    }

    /**
     * The onion service key, sealed under an AndroidKeyStore-wrapped key rather than left in the
     * clear.
     *
     * This key *is* the address. Anyone holding it can impersonate this device's service, so a
     * plaintext copy in app storage was the weakest link in the whole transport: everything else the
     * app keeps — identities, message rows, vault secrets — is already sealed.
     *
     * It does not make the address survive a reinstall. Keystore entries are bound to the app's UID
     * and are destroyed with it; measured, not assumed (`KeystoreSurvivesUninstallTest`). Sealing
     * protects the key at rest and nothing more.
     */
    private fun readKey(): String? {
        sealed.takeIf { it.isFile }?.let { f ->
            return runCatching {
                box.open(NS, KEY_ID, f.readText(Charsets.UTF_8)).toString(Charsets.UTF_8)
            }.getOrNull()
        }
        // One-time migration of the plaintext file written before this existed. Losing it would mean
        // silently changing the address of a device whose contacts already hold the old one.
        val legacy = keyFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()
        if (!legacy.isNullOrEmpty()) {
            writeKey(legacy)
            keyFile.delete()
        }
        return legacy
    }

    private fun writeKey(privKey: String) {
        sealed.parentFile?.mkdirs()
        sealed.writeText(box.seal(NS, KEY_ID, privKey.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
    }

    private val box by lazy { AndroidLocalSecretBox(appContext) }
    private val sealed get() = File(keyFile.parentFile, "onion-service-key.sealed")

    /** Wait for CONNECTED, recording the attempt either way. Never waits past [deadline]. */
    private fun awaitConnected(route: String, budgetMs: Long, deadline: Long): Boolean {
        val started = System.currentTimeMillis()
        val until = minOf(started + budgetMs, deadline)
        while (System.currentTimeMillis() < until && tor.torState != TorWrapper.TorState.CONNECTED) {
            Thread.sleep(POLL_MS)
        }
        val connected = tor.torState == TorWrapper.TorState.CONNECTED
        tried += Attempt(route, (System.currentTimeMillis() - started) / 1000, connected)
        return connected
    }

    /**
     * Bounce Tor so the next bridge type gets a live managed proxy.
     *
     * `enableBridges` on a running instance is not enough — see the caller. Failures are swallowed
     * because a restart that does not take is no worse than the state it replaces.
     */
    private fun restartTor() = runCatching {
        tor.stop()
        Thread.sleep(1_000)
        tor.start()
        tor.enableNetwork(true)
    }.let { }

    fun stop() = runCatching { tor.stop() }.let { }

    /**
     * Withdraw the service from this Tor session.
     *
     * Tor refuses to add the same onion twice in one session — `Onion address collision`, which is
     * how a re-publish of the *same* key announces itself. Only needed by tests that publish more
     * than once in a process; a real launch publishes once.
     */
    fun unpublish(onion: String) = runCatching {
        tor.removeHiddenService(onion.removeSuffix(".onion"))
        onionAddress = null
    }.let { }

    companion object {
        /** Virtual port on the onion service. 80 keeps the address readable as a URL. */
        const val ONION_PORT = 80
        const val LOCAL_PORT = 6883
        const val SOCKS_PORT = 59050
        const val CONTROL_PORT = 59051

        private const val POLL_MS = 1_000L
        private const val DIRECT_TIMEOUT_MS = 60_000L
        private const val DIRECT_PROBE_MS = 20_000L
        /** Floor for one bridge type, so a long list cannot slice the budget into useless pieces. */
        private const val BRIDGE_MIN_MS = 90_000L
        private const val TOTAL_TIMEOUT_MS = 480_000L

        private const val NS = "onion-service"
        private const val KEY_ID = "onion-service-key.v1"


        @Volatile
        private var instance: EidoTor? = null

        fun of(context: Context): EidoTor = instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }

        private fun build(context: Context): EidoTor {
            val app = context.applicationContext as Application
            val io = Executors.newCachedThreadPool()
            val dir = File(app.filesDir, "tor").apply { mkdirs() }
            // The ABI names the folder the binary sits in inside tor-android.
            val architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val wrapper = AndroidTorWrapper(
                app, WakeLocks(app), io, io, architecture, dir, SOCKS_PORT, CONTROL_PORT,
            )
            return EidoTor(wrapper, File(dir, "onion-service-key.txt"), countryOf(app), app)
        }

        /**
         * Which country's bridge list to use — uppercase, as the circumvention data expects.
         *
         * The mobile network is asked first and the SIM second: they answer where the phone *is*,
         * which is what decides whether Tor is blocked. The locale is only a last resort, because it
         * says which language the owner reads, not which network they are on — a phone set to
         * English in Moscow would be sent down the wrong route.
         */
        private fun countryOf(app: Application): String {
            val tm = runCatching {
                app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            }.getOrNull()
            val fromNetwork = tm?.networkCountryIso?.takeIf { it.isNotBlank() }
            val fromSim = tm?.simCountryIso?.takeIf { it.isNotBlank() }
            val fallback = Locale.getDefault().country
            return (fromNetwork ?: fromSim ?: fallback).uppercase(Locale.US)
        }
    }

    /**
     * The library's own implementation of this is package-private, and Tor needs a real wake lock:
     * without one the process is frozen while the phone dozes and the onion service quietly stops
     * answering — the failure would look like an unreachable contact, not like a sleeping phone.
     */
    private class WakeLocks(private val app: Application) : AndroidWakeLockManager {
        private val power get() = app.getSystemService(Context.POWER_SERVICE) as PowerManager

        override fun createWakeLock(tag: String): AndroidWakeLock = object : AndroidWakeLock {
            private val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eidolang:$tag")
                .apply { setReferenceCounted(true) }

            override fun acquire() = lock.acquire(WAKE_LOCK_MS)
            override fun release() = runCatching { lock.release() }.let { }
        }

        override fun runWakefully(task: Runnable, tag: String) {
            val lock = createWakeLock(tag)
            lock.acquire()
            try {
                task.run()
            } finally {
                lock.release()
            }
        }

        override fun executeWakefully(task: Runnable, executor: Executor, tag: String) {
            val lock = createWakeLock(tag)
            lock.acquire()
            executor.execute {
                try {
                    task.run()
                } finally {
                    lock.release()
                }
            }
        }

        override fun executeWakefully(task: Runnable, tag: String) =
            executeWakefully(task, Executors.newSingleThreadExecutor(), tag)

        private companion object {
            const val WAKE_LOCK_MS = 10 * 60 * 1000L
        }
    }
}
