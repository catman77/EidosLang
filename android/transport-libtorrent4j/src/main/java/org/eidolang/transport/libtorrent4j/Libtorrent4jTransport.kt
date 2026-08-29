package org.eidolang.transport.libtorrent4j

import org.eidolang.core.archive.BValue
import org.eidolang.core.archive.Bencode
import org.eidolang.core.archive.Bep44MutableHeadV1
import org.eidolang.core.archive.ConversationHeadCodec
import org.eidolang.core.transport.FetchResult
import org.eidolang.core.transport.MutableHeadRecord
import org.eidolang.core.transport.SwarmObject
import org.eidolang.core.transport.TorrentTransport
import org.eidolang.core.transport.TransportFile
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Entry
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.DhtPutAlert
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.string_int_pair
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * `TorrentTransport` backed by the prebuilt libtorrent 2.1.0 Android binding.
 *
 * This replaces `ReferenceTransportNetwork` — which its own comment calls "NOT BitTorrent" — with
 * a real BEP52 swarm and BEP44 DHT. Two deviations from the R17 design are deliberate and were
 * chosen when the product was wired up:
 *
 *  * **Puts are signed by libtorrent, not pre-signed by the project.** The binding only offers
 *    `dht_put_item(pk, sk, entry, salt)`; the callback form that `LibtorrentNative.dhtPutPreSigned`
 *    was written for is not exposed. The transport is therefore given the publisher's Ed25519
 *    seed. The result is wire-identical: libtorrent signs the standard BEP44 signable buffer with
 *    the same key, so `ConversationHeadCodec.verify` accepts it.
 *  * **`getHead` needs the key, not just the target.** `target = SHA1(pk || salt)` is not
 *    invertible and BEP44 mutable gets are addressed by `(pk, salt)`. Callers register the pairs
 *    they care about with [knowHead] — normally straight from a publisher certificate.
 */
class Libtorrent4jTransport(
    private val storageRoot: File,
    listenPort: Int = 0,
    private val publisherSeed: ByteArray? = null,
    private val publisherPublicKey: ByteArray? = null,
    bootstrapNodes: String = "",
    /** Bind address. Use `0.0.0.0` when the peer is reached through an adb tunnel. */
    listenInterface: String = "127.0.0.1",
    /** Lift libtorrent's anti-Sybil rules so a loopback DHT can form. Test/local use only. */
    private val localDhtTuning: Boolean = false,
    /**
     * Ask the router to open the listening port. Off for loopback tests; a real deployment behind a
     * home NAT needs it, or nobody can pull what this device seeds.
     */
    enablePortMapping: Boolean = false,
    /**
     * Multicast discovery on the local network. Two devices on the same Wi-Fi find each other
     * directly, with no DHT and no NAT in the way — the one path that works even when the phone is
     * unreachable from the internet.
     */
    enableLocalDiscovery: Boolean = false,
    private val timeoutMs: Long = 120_000,
) : TorrentTransport {

    private val session = SessionManager()
    private val knownHeads = ConcurrentHashMap<String, Pair<ByteArray, ByteArray>>()

    /** Nodes that acknowledged the last [putHead]; 0 means the head reached nobody. */
    @Volatile var lastPutSuccessCount: Int = -1
        private set

    init {
        storageRoot.mkdirs()
        val sp = SettingsPack()
            .setString(
                settings_pack.string_types.listen_interfaces.swigValue(),
                "$listenInterface:$listenPort",
            )
            .setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), bootstrapNodes)
            .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), enableLocalDiscovery)
            .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), enablePortMapping)
            .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), enablePortMapping)
            // uTP carries BitTorrent over UDP, which traverses far more NATs than TCP and is the
            // transport BEP 55 hole punching rides on.
            .setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
        if (localDhtTuning) {
            // Every node shares 127.0.0.1, which libtorrent's anti-Sybil defaults reject: the
            // routing table stays empty and BEP42 node-id enforcement fails every store.
            sp.setBoolean(settings_pack.bool_types.dht_restrict_routing_ips.swigValue(), false)
                .setBoolean(settings_pack.bool_types.dht_restrict_search_ips.swigValue(), false)
                .setBoolean(settings_pack.bool_types.dht_ignore_dark_internet.swigValue(), false)
                .setBoolean(settings_pack.bool_types.dht_enforce_node_id.swigValue(), false)
        }
        session.start(SessionParams(sp))
    }

    val listenPortActual: Int
        get() = session.listenEndpoints().firstOrNull()?.substringAfterLast(':')?.toIntOrNull() ?: 0

    fun addDhtNode(host: String, port: Int) = session.swig().add_dht_node(string_int_pair(host, port))

    fun dhtNodes(): Long = session.dhtNodes()

    fun isFirewalled(): Boolean = session.isFirewalled()

    fun externalAddress(): String = session.externalAddress() ?: "?"

    fun listenEndpoints(): List<String> = session.listenEndpoints()

    /** Register a `(publicKey, salt)` pair so [getHead] can address it. */
    fun knowHead(publicKeyRaw: ByteArray, salt: ByteArray) {
        knownHeads[sha1Hex(publicKeyRaw + salt)] = publicKeyRaw.copyOf() to salt.copyOf()
    }

    fun connectPeer(infoHashV2Hex: String, host: String, port: Int) {
        handleFor(infoHashV2Hex)?.swig()?.connect_peer(TcpEndpoint(host, port).swig())
    }

    // ------------------------------------------------------------------ swarm

    override fun seed(objectToSeed: SwarmObject) {
        val root = File(storageRoot, objectToSeed.torrentName)
        objectToSeed.files.forEach { f ->
            val target = File(root, f.path)
            target.parentFile?.mkdirs()
            target.writeBytes(f.bytes)
        }
        val info = TorrentInfo(objectToSeed.torrentMetainfo)
        require(info.infoHashes().swig().v2.to_hex() == objectToSeed.infoHashV2Hex) {
            "metainfo does not match the declared v2 infohash"
        }
        session.download(info, storageRoot, null, null, null, TorrentFlags.SEED_MODE)
        awaitOrThrow("seeding ${objectToSeed.infoHashV2Hex}") {
            handleFor(objectToSeed.infoHashV2Hex)?.status()?.isSeeding == true
        }
    }

    override fun fetch(infoHashV2Hex: String): FetchResult? {
        val existing = handleFor(infoHashV2Hex)
        if (existing == null) {
            session.download(magnetFor(infoHashV2Hex), storageRoot, torrent_flags_t())
        }
        if (!await { handleFor(infoHashV2Hex)?.status()?.isSeeding == true }) return null

        val handle = handleFor(infoHashV2Hex) ?: return null
        val info = handle.torrentFile() ?: return null
        val root = File(storageRoot, info.name())
        val files = root.walkTopDown().filter { it.isFile }
            .map { TransportFile(it.relativeTo(root).path.replace('\\', '/'), it.readBytes()) }
            .toList().sortedBy { it.path }
        if (files.isEmpty()) return null
        return FetchResult(
            // libtorrent4j does not hand back the original metainfo bytes, and
            // NetworkAdmissionGate does not read this field: it re-derives the torrent from the
            // files and checks it against the infohash carried in the signed head.
            SwarmObject(info.name(), ByteArray(0), infoHashV2Hex, files),
            "libtorrent4j:${info.infoHashes().swig().v2.to_hex().take(12)}",
        )
    }

    override fun removeSeed(infoHashV2Hex: String) {
        handleFor(infoHashV2Hex)?.let { session.remove(it) }
    }

    // -------------------------------------------------------------------- dht

    override fun putHead(record: MutableHeadRecord): Boolean {
        val seed = publisherSeed ?: return false
        val pub = publisherPublicKey ?: return false
        val head = ConversationHeadCodec.parseMutablePutFields(record.fieldsBencoded)
        require(head.publicKeyRaw.contentEquals(pub)) {
            "head was built for another publisher key than this transport holds"
        }
        knowHead(head.publicKeyRaw, head.salt)

        val successes = AtomicInteger(-1)
        val listener = object : AlertListener {
            override fun types() = intArrayOf(AlertType.DHT_PUT.swig())
            override fun alert(a: Alert<*>) {
                if (a is DhtPutAlert && a.publicKey().contentEquals(pub)) {
                    successes.set(a.swig().num_success)
                }
            }
        }
        session.addListener(listener)
        try {
            session.dhtPutItem(pub, expandedSecret(seed), Entry.bdecode(Bencode.encode(head.value)), head.salt)
            if (!await { successes.get() >= 0 }) return false
            lastPutSuccessCount = successes.get()
            // num_success == 0 means the item reached nobody; the alert alone proves nothing.
            return successes.get() > 0
        } finally {
            session.removeListener(listener)
        }
    }

    override fun getHead(targetSha1Hex: String): MutableHeadRecord? {
        val (pub, salt) = knownHeads[targetSha1Hex] ?: return null
        // Short: this is a fallback behind the relay, and a long block here starves the retry loop.
        val item = session.dhtGetItem(pub, salt, 15) ?: return null
        val entry = item.item ?: return null                      // a miss looks like a hit here
        val value = Bencode.decode(entry.bencode()) as? BValue.Dict ?: return null
        val head = Bep44MutableHeadV1(
            publicKeyRaw = pub,
            salt = salt,
            seq = item.seq,
            value = value,
            signature = item.signature,
            targetSha1Hex = targetSha1Hex,
        )
        return MutableHeadRecord(targetSha1Hex, Bencode.encode(ConversationHeadCodec.mutablePutFields(head)))
    }

    override fun close() {
        runCatching { session.stop() }
    }

    // ---------------------------------------------------------------- helpers

    private fun magnetFor(v2Hex: String) = "magnet:?xt=urn:btmh:1220$v2Hex"

    /**
     * `SessionManager.find` takes a Sha1Hash and `Sha1Hash` has no byte-array constructor, so
     * enumerate instead and match on the v2 infohash directly.
     */
    private fun handleFor(v2Hex: String): TorrentHandle? {
        val all = session.swig().get_torrents()
        for (i in 0 until all.size) {
            val th = all.get(i)
            val ih = th.info_hashes()
            if (ih.has_v2() && ih.v2.to_hex() == v2Hex) return TorrentHandle(th)
        }
        return null
    }

    private fun await(predicate: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (predicate()) return true
            Thread.sleep(200)
        }
        return false
    }

    private fun awaitOrThrow(what: String, predicate: () -> Boolean) {
        check(await(predicate)) { "timed out waiting for $what" }
    }

    private fun sha1Hex(b: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }


    private companion object {
        /** libtorrent bundles orlp/ed25519: the secret is clamped SHA-512(seed), not seed||public. */
        fun expandedSecret(seed: ByteArray): ByteArray {
            val h = MessageDigest.getInstance("SHA-512").digest(seed)
            h[0] = (h[0].toInt() and 248).toByte()
            h[31] = (h[31].toInt() and 63).toByte()
            h[31] = (h[31].toInt() or 64).toByte()
            return h
        }
    }
}
