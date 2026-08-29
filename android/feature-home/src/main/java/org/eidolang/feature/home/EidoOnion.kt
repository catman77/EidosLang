package org.eidolang.feature.home

import android.content.Context
import org.eidolang.core.archive.SegmentParser
import org.eidolang.core.vault.AndroidBootstrapRecoverySecret
import org.eidolang.core.transport.FetchResult
import org.eidolang.core.transport.SwarmObject
import org.eidolang.core.transport.TransportFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What this device hands out over its own onion address, and how it asks another device for theirs.
 *
 * This is the piece that makes the relay unnecessary. The relay was doing two jobs in delivery —
 * storing the signed head and seeding the segment — and it was doing them only because neither phone
 * is reachable from the internet. An onion address removes that reason: both ends only ever make
 * outgoing connections, so the sender can simply serve its own bytes.
 *
 * The bytes are the same bytes. A head is the bencoded BEP44 record and a segment is the transport
 * artifact, exactly as the DHT and the swarm carry them, so `NetworkAdmissionGate` still decides what
 * is admitted and nothing here becomes a new wire entity. The onion is a way of *reaching* the bytes,
 * not a new format for them — which is also why this needs no counterexample under the R22 rule.
 *
 * HTTP is spoken by hand over a plain socket. Android has no `com.sun.net.httpserver`, and every
 * embeddable server is a dependency carrying far more surface than two GET routes justify. There is
 * no TLS: a v3 onion address *is* the public key, so the circuit is already authenticated and
 * encrypted end to end — putting TLS inside it would authenticate nothing that is not already known.
 */
class EidoOnionServer(
    private val store: OnionStore,
    private val port: Int = EidoTor.LOCAL_PORT,
    /**
     * Called with `(infohash, path)` for every segment file actually handed out.
     *
     * This is the only place in the product that observes a delivery. Nothing is asked of the
     * recipient and nothing is added to the wire — the server is simply told what it just did, and
     * [DeliveryLedger] decides when enough has gone out to call a segment collected.
     */
    private val onSegmentServed: (String, String) -> Unit = { _, _ -> },
) {
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "onion-serve").apply { isDaemon = true } }
    private var socket: ServerSocket? = null

    /** Requests served since start, by route. Diagnostics: a silent server looks like a dead one. */
    @Volatile
    var served: Int = 0
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        // Loopback only. Tor forwards to this port from the circuit; nothing else on the phone's
        // networks should be able to reach it, and binding the wildcard would expose it on Wi-Fi.
        val server = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
        socket = server
        pool.execute {
            while (running.get()) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { runCatching { serve(client) }; runCatching { client.close() } }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
    }

    private fun serve(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val request = readRequestLine(client.getInputStream()) ?: return
        val (method, path) = request
        val out = client.getOutputStream()
        if (method != "GET") return reply(out, 405, ByteArray(0))
        // Set only when a segment file is really produced, so a 404 — a probe, a typo, a stale path
        // from an older segment — cannot be mistaken for bytes that left the device.
        var segmentServed: Pair<String, String>? = null
        val body = when {
            path.startsWith("/head/") -> store.head(path.removePrefix("/head/"))
            // `/segment/<infohash>/<path inside the segment>`. Files are served one by one rather
            // than as one archive because that is what the segment already is — the recipient reads
            // the manifest, asks for the paths it names, and `ConversationSegmentLoader` rebuilds the
            // torrent and checks the infohash. Bundling them would invent a container format for
            // something that already has one.
            path.startsWith("/segment/") -> path.removePrefix("/segment/").split('/', limit = 2)
                .takeIf { it.size == 2 }
                ?.let { (infoHash, inner) ->
                    store.segmentFile(infoHash, inner)?.also { segmentServed = infoHash to inner }
                }
            path == "/hello" -> HELLO
            // The owner's avatar, fetched after an introduction rather than carried through it. A
            // PNG is the one part of a contact card that will not fit in a QR code, and it is also
            // the one part nobody needs at the moment of meeting.
            path == "/avatar" -> store.avatar()
            // The owner's contact card, which is what an invite link resolves to. Public by
            // definition — it is the file people already hand each other — and bound to this
            // address: the link carries its hash, so this route cannot answer with another identity
            // without the scan failing.
            path.startsWith("/card/") -> store.card(path.removePrefix("/card/"))
            else -> null
        }
        if (body == null) reply(out, 404, ByteArray(0)) else {
            served++
            reply(out, 200, body)
            // After the reply: bookkeeping must never be what stops a recipient getting its bytes.
            segmentServed?.let { (infoHash, inner) -> runCatching { onSegmentServed(infoHash, inner) } }
        }
    }

    /**
     * Read the request line and drain the headers.
     *
     * Bounded on purpose: an unbounded read here is a way to make the phone allocate until it dies,
     * and an onion address is public the moment it is on a contact card.
     */
    private fun readRequestLine(input: InputStream): Pair<String, String>? {
        val first = readLine(input, MAX_LINE) ?: return null
        var headers = 0
        while (true) {
            val line = readLine(input, MAX_LINE) ?: return null
            if (line.isEmpty()) break
            if (++headers > MAX_HEADERS) return null
        }
        val parts = first.split(' ')
        if (parts.size < 2) return null
        return parts[0].uppercase(Locale.US) to parts[1]
    }

    private fun readLine(input: InputStream, limit: Int): String? {
        val buf = ByteArrayOutputStream()
        while (buf.size() <= limit) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (c == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(c)
        }
        return null
    }

    private fun reply(out: OutputStream, code: Int, body: ByteArray) {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "NO"}\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private companion object {
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_LINE = 4096
        const val MAX_HEADERS = 32
        val HELLO = "eidolang".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * The heads and segments this device is prepared to hand out.
 *
 * Names are checked against the shape the protocol gives them — SHA-1 hex for a BEP44 target, SHA-256
 * hex for a v2 infohash — before they touch the filesystem. Without that, a request for
 * `/head/../../databases/messenger.db` would be served, and the one thing this server must never do
 * is read outside the two directories it owns.
 */
class OnionStore(context: Context) {
    private val heads = File(context.filesDir, "onion/head")
    private val segments = File(context.filesDir, "onion/segment")
    private val avatarFile = File(context.filesDir, "onion/avatar.png")
    private val cards = File(context.filesDir, "onion/card")

    fun putHead(target: String, bytes: ByteArray) = put(heads, target, 40, bytes)

    /**
     * The owner's own avatar, offered to anyone holding the address.
     *
     * Public by intention — it is what a contact sees next to the name — but bounded, because it is
     * served to whoever asks and an unbounded read here would be a way to make the phone allocate
     * until it dies.
     */
    fun putAvatar(png: ByteArray) {
        require(png.size <= MAX_AVATAR) { "avatar too large" }
        avatarFile.parentFile?.mkdirs()
        avatarFile.writeBytes(png)
    }

    fun avatar(): ByteArray? = avatarFile.takeIf { it.isFile }
        ?.takeIf { it.length() <= MAX_AVATAR }?.readBytes()

    /**
     * The owner's own contact card, filed under its own hash.
     *
     * By hash, and every version kept, because an invite link pins the exact bytes it was made from.
     * Changing a nickname rewrites the card and therefore its hash — and if this device served only
     * the newest one, every code already handed out or printed would start failing, at the moment
     * its owner did something as ordinary as correcting their name.
     *
     * Old cards stay safe to answer with: they carry the same keys and the same address, differing
     * only in the name that was on the code the other person actually scanned. The picture is not in
     * them at all — `/avatar` is always current.
     *
     * Returns the name the card is served under, which is what goes into the link.
     */
    fun putCard(json: ByteArray): String {
        require(json.size <= EidoInvite.MAX_CARD_BYTES) { "card too large" }
        val hash = org.eidolang.core.crypto.HexSha256.of(json)
        cards.mkdirs()
        File(cards, "$hash.json").writeBytes(json)
        // A bound on the pathological case only: cards are ~2.4 KB, so keeping a long history costs
        // nothing, but a loop that rewrote the profile forever should not fill the device.
        cards.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_CARDS)
            ?.forEach { it.delete() }
        return hash
    }

    fun card(sha256Hex: String): ByteArray? {
        if (!isHex(sha256Hex, 64)) return null
        return File(cards, "$sha256Hex.json").takeIf { it.isFile }
            ?.takeIf { it.length() <= EidoInvite.MAX_CARD_BYTES }?.readBytes()
    }

    fun head(target: String): ByteArray? = get(heads, target, 40)

    /**
     * Store one file of a segment, addressed by its path inside the segment.
     *
     * The path is hashed rather than used as a filename. Segment paths contain slashes
     * (`identities/<user>/<device>.json`), so using them directly would mean creating nested
     * directories from a name that arrives over the network — the traversal problem, reintroduced at
     * the one place where the name genuinely has to carry structure. A SHA-256 is hex by
     * construction, so the existing name check covers it unchanged.
     */
    fun putSegmentFile(infoHashV2Hex: String, path: String, bytes: ByteArray) {
        require(isHex(infoHashV2Hex, 64)) { "bad infohash" }
        put(File(segments, infoHashV2Hex), pathKey(path), 64, bytes)
    }

    fun segmentFile(infoHashV2Hex: String, path: String): ByteArray? {
        if (!isHex(infoHashV2Hex, 64)) return null
        return get(File(segments, infoHashV2Hex), pathKey(path), 64)
    }

    private fun pathKey(path: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(path.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun put(dir: File, name: String, length: Int, bytes: ByteArray) {
        require(isHex(name, length)) { "bad name" }
        dir.mkdirs()
        File(dir, "$name.bin").writeBytes(bytes)
    }

    private fun get(dir: File, name: String, length: Int): ByteArray? {
        if (!isHex(name, length)) return null
        return File(dir, "$name.bin").takeIf { it.isFile }?.readBytes()
    }

    companion object {
        const val MAX_AVATAR = 256 * 1024
        const val MAX_CARDS = 32
    }

    private fun isHex(s: String, length: Int) =
        s.length == length && s.all { it in '0'..'9' || it in 'a'..'f' }
}

/**
 * This device as a reachable node: Tor up, the server listening, the address known.
 *
 * Started in the background and idempotent, because bringing Tor up takes a minute or two even when
 * nothing is wrong, and blocking a send on it would look exactly like the frozen send screen this
 * product already had once. A send that happens before the node is up still publishes its bytes into
 * the store — they are simply handed out later, when somebody asks.
 */
object EidoOnionNode {

    @Volatile
    private var started = false

    @Volatile
    var address: String? = null
        private set

    /** Null until Tor has connected and the service has been published. */
    val isReachable: Boolean get() = address != null

    /**
     * Whether the address was derived from the recovery secret, and therefore survives a reinstall.
     *
     * False means the owner has no history vault yet: the address is a local key and dies with the
     * app. Worth surfacing, because the difference is invisible until somebody reinstalls and their
     * contacts quietly stop reaching them.
     */
    @Volatile
    var portable: Boolean = false
        private set

    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            val app = context.applicationContext
            Thread {
                runCatching {
                    val store = OnionStore(app)
                    EidoProfileStore(app).load()?.avatarPng?.let { store.putAvatar(it) }
                    val ledger = DeliveryLedger(app)
                    EidoOnionServer(store) { infoHash, path ->
                        ledger.served(infoHash, path, System.currentTimeMillis())
                    }.start()
                    val derived = derivedKey(app)
                    portable = derived != null
                    val tor = EidoTor.of(app)
                    address = tor.startAndPublish(derivedKey = derived)
                    // The routes, not just the verdict. Diagnosing why an address failed to appear
                    // otherwise meant reading Tor's own log line by line and inferring which attempt
                    // each line belonged to.
                    println(
                        "ONION node address=$address portable=$portable routes=" +
                            tor.attempts.joinToString { "${it.route}/${it.seconds}s/${it.connected}" }
                    )
                }.onFailure { println("ONION node failed: $it") }
            }.apply { isDaemon = true; name = "onion-node" }.start()
        }
    }

    /**
     * The onion key derived from this installation's history-vault recovery secret, if it has one.
     *
     * Never null in practice: the secret is created at first run precisely so that the address is
     * portable from the very first card. Null only if the Keystore itself refuses, in which case Tor
     * falls back to a local key and [portable] says so.
     */
    fun derivedKey(context: Context): String? = runCatching {
        val raw = AndroidBootstrapRecoverySecret.forOnion(context).copyBytes()
        try {
            OnionServiceKey.fromRecoverySecret(raw)
        } finally {
            raw.fill(0)
        }
    }.getOrNull()
}

/**
 * Moving a conversation segment over an onion address instead of the swarm.
 *
 * This is the replacement for what the relay was doing: it stored the head because neither phone can
 * be dialled, and it seeded the segment for the same reason. Both jobs disappear once the sender is
 * reachable at an address of its own.
 *
 * Nothing new goes on the wire. The head is the same bencoded BEP44 record; the segment is the same
 * set of files, requested one at a time by the paths its own manifest names; and the recipient still
 * hands the result to `NetworkAdmissionGate`, which rebuilds the torrent and refuses anything whose
 * infohash, segment id, conversation id or message DAG does not match the signed head. A malicious
 * onion can therefore withhold bytes but cannot forge them, exactly as a malicious peer could not.
 */
object OnionDelivery {

    /**
     * Fetch a contact's avatar from their onion address and file it locally.
     *
     * Separate from the introduction on purpose. Carrying a PNG through a QR code is what would have
     * forced the code past what a phone camera can read, and the picture is worth nothing until
     * there is a conversation to show it in. Missing simply means no picture yet — never a failure
     * the person meeting somebody has to understand.
     */
    fun fetchAvatar(context: Context, userId: String, onion: String): Boolean {
        val png = EidoOnionClient.get(onion, "/avatar", maxBytes = OnionStore.MAX_AVATAR)
            ?.takeIf { it.isNotEmpty() } ?: return false
        // PNG magic. Anything else is not shown: this arrives from a stranger's device and is handed
        // straight to the image decoder.
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (png.size < magic.size || !png.copyOf(magic.size).contentEquals(magic)) return false
        ContactAvatarStore(context).put(userId, png)
        return true
    }

    /** Put a segment and its head where this device's onion service can hand them out. */
    fun publish(
        store: OnionStore,
        segment: org.eidolang.core.archive.ConversationSegmentArtifactV1,
        headTargetSha1Hex: String,
        headBencoded: ByteArray,
    ) {
        segment.files.forEach { store.putSegmentFile(segment.torrent.infoHashV2Hex, it.path, it.bytes) }
        store.putHead(headTargetSha1Hex, headBencoded)
    }

    /**
     * Pull a whole segment from a peer's onion address.
     *
     * The manifest is fetched first because it is the only thing that says which files exist. A
     * missing or short file returns null rather than a partial segment: handing an incomplete file
     * set to the admission gate would fail as an infohash mismatch, which reads like tampering
     * rather than like a dropped connection.
     */
    fun fetch(onion: String, infoHashV2Hex: String, torrentName: String): FetchResult? {
        // The manifest is the first request after the head, so the circuit may still be settling;
        // the files after it go over a warm one and do not need the same patience.
        val manifestBytes = EidoOnionClient.getRetrying(
            onion, "/segment/$infoHashV2Hex/$MANIFEST", attempts = 6, delayMs = 4_000,
        ) ?: return null
        val manifest = runCatching {
            SegmentParser.parseCanonical(manifestBytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return null

        val paths = buildList {
            add(CONVERSATION)
            manifest.identityEntries.forEach { add(it.path) }
            manifest.messageEntries.forEach { add(it.path) }
        }
        val files = paths.map { path ->
            val bytes = EidoOnionClient.get(onion, "/segment/$infoHashV2Hex/$path") ?: return null
            TransportFile(path, bytes)
        } + TransportFile(MANIFEST, manifestBytes)

        return FetchResult(SwarmObject(torrentName, ByteArray(0), infoHashV2Hex, files), "onion")
    }

    /**
     * The torrent name a segment gets, recomputed rather than transmitted.
     *
     * `ConversationSegmentBuilder` derives it from the conversation and segment ids, both of which
     * the recipient already has from the signed head. Sending it would mean trusting the sender for
     * a value that feeds the infohash check — the check would then be verifying the sender against
     * itself.
     */
    fun torrentName(conversationId: String, segmentId: String) =
        "eidolang-${conversationId.take(12)}-${segmentId.take(12)}"

    private const val MANIFEST = "segment-manifest.json"
    private const val CONVERSATION = "conversation.json"
}

/**
 * Fetching from somebody else's onion address, through Tor's SOCKS port.
 *
 * The SOCKS5 handshake is written out rather than going through `java.net.Proxy`, because the whole
 * thing turns on one detail: the hostname must be sent to Tor **unresolved**. A `.onion` name has no
 * DNS answer, so a client that resolves before connecting fails — and it fails with a name-resolution
 * error, which reads like a network fault rather than a design mistake.
 */
object EidoOnionClient {

    /**
     * Why the last [get] returned null.
     *
     * A bare null cost two runs to interpret: "the onion does not answer" covers a refused SOCKS
     * handshake, a service Tor cannot find, a 404 and a truncated body, and those have nothing to do
     * with each other. The SOCKS reply code in particular is Tor telling us precisely what went
     * wrong, and throwing it away was the expensive part.
     */
    @Volatile
    var lastError: String? = null
        private set

    /** GET a path from an onion host. Returns null on any failure — see [lastError]. */
    fun get(
        onion: String,
        path: String,
        socksPort: Int = EidoTor.SOCKS_PORT,
        onionPort: Int = EidoTor.ONION_PORT,
        timeoutMs: Int = 90_000,
        /**
         * Refuse a body larger than this.
         *
         * The peer at the other end of a circuit is a stranger — an invite link can come from
         * anyone — and until now the reply was read for however many bytes it claimed. Callers that
         * know their answer is small say so; the default only bounds the pathological case, since a
         * conversation segment has no fixed size.
         */
        maxBytes: Int = 64 * 1024 * 1024,
    ): ByteArray? = runCatching {
        lastError = null
        Socket().use { s ->
            s.soTimeout = timeoutMs
            s.connect(java.net.InetSocketAddress("127.0.0.1", socksPort), CONNECT_TIMEOUT_MS)
            val out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream())

            // Greeting: SOCKS5, one method, "no authentication".
            out.write(byteArrayOf(5, 1, 0)); out.flush()
            val greeting = ByteArray(2).also { input.readFully(it) }
            if (greeting[0] != 5.toByte() || greeting[1] != 0.toByte()) {
                lastError = "socks greeting ${greeting[0]}/${greeting[1]}"
                return null
            }

            val host = onion.toByteArray(Charsets.US_ASCII)
            // CONNECT, address type 3 = domain name. Type 3 is what keeps the name unresolved.
            val req = ByteArrayOutputStream().apply {
                write(byteArrayOf(5, 1, 0, 3)); write(host.size); write(host)
                write(onionPort shr 8); write(onionPort and 0xFF)
            }
            out.write(req.toByteArray()); out.flush()

            val head = ByteArray(4).also { input.readFully(it) }
            if (head[1] != 0.toByte()) {
                // Tor's own verdict. 0x04 is "host unreachable", which for an onion means the
                // descriptor was not found; 0x01 is a general failure. They need different fixes.
                lastError = "socks reply ${socksReply(head[1].toInt())}"
                return null
            }
            when (head[3].toInt()) {
                1 -> input.readFully(ByteArray(4))
                3 -> input.readFully(ByteArray(input.read()))
                4 -> input.readFully(ByteArray(16))
                else -> { lastError = "socks address type ${head[3]}"; return null }
            }
            input.readFully(ByteArray(2))            // bound port, unused

            out.write("GET $path HTTP/1.1\r\nHost: $onion\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            readHttpBody(input, maxBytes)
        }
    }.onFailure { lastError = "${it.javaClass.simpleName}: ${it.message}" }.getOrNull()

    private fun socksReply(code: Int) = when (code) {
        1 -> "1 general failure"
        2 -> "2 not allowed"
        3 -> "3 network unreachable"
        4 -> "4 host unreachable (onion descriptor not found)"
        5 -> "5 connection refused"
        6 -> "6 ttl expired"
        7 -> "7 command not supported"
        8 -> "8 address type not supported"
        else -> "$code unknown"
    }

    /**
     * The same GET, retried until the service answers.
     *
     * Publishing a hidden service is not the same as being reachable through it: Tor still has to
     * upload the service descriptor to the directory nodes, and until it has, every connection is
     * refused with a perfectly ordinary SOCKS error. So a single failed GET means nothing, and an
     * address must not go onto a contact card until something has actually answered on it.
     */
    fun getRetrying(
        onion: String,
        path: String,
        attempts: Int = 24,
        delayMs: Long = 5_000,
        onAttempt: (Int) -> Unit = {},
    ): ByteArray? {
        repeat(attempts) { i ->
            onAttempt(i + 1)
            get(onion, path)?.let { return it }
            println("ONION attempt ${i + 1} failed: $lastError")
            Thread.sleep(delayMs)
        }
        return null
    }

    private fun readHttpBody(input: InputStream, maxBytes: Int): ByteArray? {
        val status = readLine(input) ?: run { lastError = "empty response"; return null }
        if (!status.startsWith("HTTP/1.1 200") && !status.startsWith("HTTP/1.0 200")) {
            lastError = "http $status"
            return null
        }
        var length = -1
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toIntOrNull() ?: -1
            }
        }
        // Refuse on the announced size before reading a byte, so an absurd declaration costs nothing.
        if (length > maxBytes) {
            lastError = "too large $length > $maxBytes"
            return null
        }
        val body = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (length < 0 || body.size() < length) {
            val n = input.read(buf)
            if (n < 0) break
            body.write(buf, 0, n)
            // And again on what actually arrives: a reply with no Content-Length at all would
            // otherwise be read until the phone runs out of memory.
            if (body.size() > maxBytes) {
                lastError = "too large > $maxBytes"
                return null
            }
        }
        // A short read is a truncated answer, not an empty one; admitting it would hand a partial
        // head to the admission gate and turn a network fault into a signature failure.
        if (length >= 0 && body.size() != length) {
            lastError = "truncated ${body.size()}/$length"
            return null
        }
        return body.toByteArray()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (c == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(c)
        }
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
}
