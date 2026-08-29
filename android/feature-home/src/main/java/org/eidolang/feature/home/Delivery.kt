package org.eidolang.feature.home

import android.content.Context
import org.eidolang.core.archive.ArchivePublisherCanonical
import org.eidolang.core.archive.ArchivePublisherCertificateV1
import org.eidolang.core.archive.ArchivePublisherParser
import org.eidolang.core.archive.Bencode
import org.eidolang.core.archive.ConversationHeadCodec
import org.eidolang.core.archive.ConversationHeadValueV1
import org.eidolang.core.archive.ConversationSegmentBuilder
import org.eidolang.core.archive.Ed25519Raw
import org.eidolang.core.archive.SegmentParser
import org.eidolang.core.canonical.CanonicalJson
import org.eidolang.core.canonical.JValue
import org.eidolang.core.canonical.StrictJsonParser
import org.eidolang.core.crypto.DevicePrivateCrypto
import org.eidolang.core.crypto.HexSha256
import org.eidolang.core.crypto.IdentityCanonical
import org.eidolang.core.crypto.IdentityParser
import org.eidolang.core.crypto.PublicIdentityBundleV1
import org.eidolang.core.hardening.AndroidLocalSecretBox
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.message.ConversationFactory
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.LocalMessengerService
import org.eidolang.core.transport.FetchResult
import org.eidolang.core.transport.MutableHeadRecord
import org.eidolang.core.transport.NetworkAdmissionGate
import org.eidolang.core.transport.SwarmObject
import org.eidolang.core.transport.TransportFile
import org.eidolang.core.transport.TransportObjects
import org.eidolang.transport.libtorrent4j.AndroidArchivePublisherStore
import org.eidolang.transport.libtorrent4j.Libtorrent4jTransport
import java.io.File
import java.security.MessageDigest

/**
 * A one-to-one conversation both sides can derive without talking first.
 *
 * `ConversationFactory.fromSeed` is deterministic, so seeding it from the sorted participant ids
 * gives Alice and Bob the same conversation id — and therefore the same BEP44 salt — before a
 * single byte has moved. Without this the recipient could not compute the DHT address of a message
 * addressed to them, and the conversation descriptor would have to be handed over out of band.
 */
object ConversationConvention {
    fun oneToOne(a: String, b: String) = ConversationFactory.fromSeed(
        MessageDigest.getInstance("SHA-256")
            .digest(("eido-1to1|" + listOf(a, b).sorted().joinToString("|")).toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 16),
        listOf(a, b),
    )
}

/**
 * What one person hands another so they can exchange eidograms.
 *
 * The R22 flow exported a bare identity bundle, which is enough to encrypt *to* someone but not to
 * find what they published: BEP44 heads are addressed by the publisher's Ed25519 key. The card
 * therefore carries the archive publisher certificate too.
 */
/**
 * A name you can show a person and still verify: "CatmanJoe#7f3a".
 *
 * Zooko's triangle says a name can be at most two of human-readable, globally unique and
 * decentralised. This product gives up global uniqueness — the one property that requires somebody
 * to hold every name, and therefore a server nobody can do without. The suffix comes from the root
 * key, so two people may both call themselves CatmanJoe and still be told apart, and neither can
 * take the other's suffix without the other's private key.
 */
object EidoHandle {
    const val SUFFIX_LEN = 4

    fun of(nickname: String, userId: String): String {
        val nick = nickname.trim().ifBlank { "без имени" }
        return "$nick#${userId.take(SUFFIX_LEN)}"
    }

    /** The nickname without its suffix, for when the suffix is displayed separately. */
    fun nicknameOf(handle: String) = handle.substringBeforeLast('#')
}

object ContactCard {
    private const val VERSION = "eido-contact-card-v1"

    /**
     * @param nickname and [avatarPng] are what the recipient will see in their contact list. Before
     * they travelled on the card, an imported contact was labelled with its `userId` — a hex string
     * nobody can recognise, let alone remember.
     */
    fun write(
        identity: DevicePrivateCrypto,
        publisher: ArchivePublisherCertificateV1,
        nickname: String = "",
        avatarPng: ByteArray? = null,
        onion: String = "",
    ): String =
        CanonicalJson.obj(
            mapOf(
                "card_version" to CanonicalJson.string(VERSION),
                "identity" to IdentityCanonical.publicBundleJson(
                    PublicIdentityBundleV1(identity.user, identity.certificate)
                ),
                "profile" to CanonicalJson.obj(
                    mapOf(
                        "avatar_png_b64" to CanonicalJson.string(
                            avatarPng?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: ""
                        ),
                        "nickname" to CanonicalJson.string(nickname),
                    )
                ),
                "publisher" to ArchivePublisherCanonical.certificateJson(publisher),
                // Where to reach this device. Nothing else on the card says that: until now delivery
                // went through a relay everybody shared, so an address was never needed. It is a
                // separate object from `profile` because it is not about the person — the nickname
                // and avatar survive a reinstall, this does not.
                "transport" to CanonicalJson.obj(
                    mapOf("onion" to CanonicalJson.string(onion))
                ),
            )
        )

    data class Parsed(
        val identityCanonical: String,
        val publisher: ArchivePublisherCertificateV1?,
        val nickname: String = "",
        val avatarPng: ByteArray? = null,
        val onion: String = "",
    )

    /** Accepts a card or a bare R22 identity bundle, so older exports still import. */
    fun read(text: String): Parsed {
        val root = StrictJsonParser(text).parse() as? JValue.Obj ?: error("Не похоже на визитку")
        if ((root.fields["card_version"] as? JValue.Str)?.value != VERSION) {
            IdentityParser.parsePublicBundleCanonical(text)
            return Parsed(text, null)
        }
        val identity = org.eidolang.core.canonical.CanonicalReserialize.of(
            root.fields["identity"] ?: error("Визитка без identity")
        )
        IdentityParser.parsePublicBundleCanonical(identity)
        val publisher = root.fields["publisher"]?.let {
            ArchivePublisherParser.parseCanonical(org.eidolang.core.canonical.CanonicalReserialize.of(it))
        }
        // Optional: cards written before this field exist in the wild, and a card is a file people
        // have already exchanged.
        val profile = root.fields["profile"] as? JValue.Obj
        val nickname = (profile?.fields?.get("nickname") as? JValue.Str)?.value.orEmpty()
        val avatar = (profile?.fields?.get("avatar_png_b64") as? JValue.Str)?.value
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull() }
        // Validated against the shape of a v3 address, not merely copied. It is fed to Tor as a
        // hostname, and an unchecked string from a file somebody sent is the wrong thing to hand to a
        // network client.
        val onion = ((root.fields["transport"] as? JValue.Obj)?.fields?.get("onion") as? JValue.Str)
            ?.value.orEmpty()
            .takeIf { it.matches(Regex("[a-z2-7]{56}\\.onion")) }
            .orEmpty()
        return Parsed(identity, publisher, nickname, avatar, onion)
    }
}

/** Where each contact can be reached. Public information, but kept with the rest of contact state. */
class ContactOnionStore(private val context: Context) {
    private val dir get() = File(context.filesDir, "contact-onion").apply { mkdirs() }

    fun put(userId: String, onion: String) {
        if (!onion.matches(Regex("[a-z2-7]{56}\\.onion"))) return
        File(dir, "$userId.txt").writeText(onion, Charsets.UTF_8)
    }

    fun get(userId: String): String? = File(dir, "$userId.txt")
        .takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()
        ?.takeIf { it.matches(Regex("[a-z2-7]{56}\\.onion")) }
}

/** Publisher certificates of people we can receive from, kept sealed like everything else local. */
class ContactPublisherStore(private val context: Context) {
    private val box = AndroidLocalSecretBox(context)
    private val dir get() = File(context.filesDir, "contact-publishers").apply { mkdirs() }

    fun put(userId: String, cert: ArchivePublisherCertificateV1) {
        File(dir, "$userId.bin").writeText(
            box.seal(NS, userId, ArchivePublisherCanonical.certificateJson(cert).toByteArray(Charsets.UTF_8)),
            Charsets.UTF_8,
        )
    }

    fun get(userId: String): ArchivePublisherCertificateV1? = runCatching {
        val f = File(dir, "$userId.bin")
        if (!f.exists()) return null
        ArchivePublisherParser.parseCanonical(
            box.open(NS, userId, f.readText(Charsets.UTF_8)).toString(Charsets.UTF_8)
        )
    }.getOrNull()

    private companion object { const val NS = "contact-publisher" }
}


/**
 * The one reachable node in a swarm of unreachable phones.
 *
 * Two devices behind VPN or CGNAT can both open outbound connections and neither can accept one, so
 * they never meet. Measured on this project: both peers reported `firewalled=true`, and a head
 * stored on 7 DHT nodes was not findable from the other device. The relay fixes both halves — it
 * serves heads over HTTP and joins the swarm so each side can reach it outbound.
 *
 * It is not an application server: it stores signed public heads and encrypted segments, verifies
 * signatures before accepting anything, and never holds a key that could open a message.
 */
object Relay {
    const val DEFAULT_HOST = "45.144.54.36"
    const val SWARM_PORT = 6881
    private const val HTTP_PORT = 6882
    private const val TIMEOUT_MS = 15_000

    /**
     * Where the relay lives, and it is now a setting rather than a constant.
     *
     * Set once at start-up from [EidoRelaySettings]; everything else reads it. Kept as a plain
     * field rather than passing a context into every call because `Relay` is reached from the
     * publish and receive paths, which have no business knowing about preferences.
     */
    @Volatile
    var host: String = DEFAULT_HOST

    val base: String get() = "https://$host:$HTTP_PORT"

    private fun open(path: String, method: String, base: String = this.base): java.net.HttpURLConnection =
        (java.net.URL(base + path).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // Pinned to the certificate, not to the address.
            //
            // The manifest's network-security-config trusted that certificate for one hard-coded
            // IP, so the moment the address became a setting every other address would have failed
            // TLS — and cleartext is forbidden, correctly, so there is no fallback. Pinning the
            // certificate itself keeps exactly the same guarantee while letting the relay move:
            // this app talks to something holding that key or to nothing at all.
            //
            // The consequence is worth stating: pointing the app at a *different* relay only works
            // if that relay presents this certificate. Accepting whatever is offered would be far
            // worse than the problem it solves.
            // Only once the certificate has been loaded. `Relay` is reached from publish, receive
            // and refresh, all of which can run before the screen that calls `EidoRelaySettings
            // .apply` has composed; an uninitialised pin threw straight into a `runCatching` and
            // every relay call silently returned "unreachable". Until it is loaded the manifest's
            // own pin for the default address still applies, so nothing is loosened by waiting.
            if (this is javax.net.ssl.HttpsURLConnection && pinnedCertificate != null) {
                sslSocketFactory = pinned.socketFactory
                hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                    runCatching {
                        session.peerCertificates.firstOrNull() == pinnedCertificate
                    }.getOrDefault(false)
                }
            }
        }

    /** Loaded once from the bundled certificate; without it the relay is simply unreachable. */
    @Volatile
    private var pinnedCertificate: java.security.cert.Certificate? = null

    private lateinit var pinned: javax.net.ssl.SSLContext

    fun trust(context: Context) {
        if (pinnedCertificate != null) return
        synchronized(this) {
            if (pinnedCertificate != null) return
            val cert = context.resources.openRawResource(R.raw.eidolang_relay).use {
                java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
            val ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("eidolang-relay", cert)
            }
            val tmf = javax.net.ssl.TrustManagerFactory
                .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(ks) }
            pinned = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, tmf.trustManagers, java.security.SecureRandom())
            }
            pinnedCertificate = cert
        }
    }

    /** Is the relay answering? Distinct from "nobody is listed" — see [searchDirectory]. */
    fun reachable(): Boolean = runCatching {
        open("/directory/search?q=zz", "GET").responseCode in 200..499
    }.getOrDefault(false)

    fun putHead(target: String, bencoded: ByteArray): Boolean = runCatching {
        open("/head/$target", "PUT").run {
            doOutput = true
            setFixedLengthStreamingMode(bencoded.size)
            outputStream.use { it.write(bencoded) }
            responseCode == 200
        }
    }.getOrDefault(false)

    fun getHead(target: String): ByteArray? = runCatching {
        open("/head/$target", "GET").run {
            if (responseCode != 200) null else inputStream.use { it.readBytes() }
        }
    }.getOrNull()

    /**
     * A found person: their card, plus the two things worth showing before you add them.
     *
     * [nickname] is chosen by its owner and is not unique — the relay binds a directory slot to the
     * hash of a root key, so nobody can overwrite anyone else's entry, but two people may perfectly
     * well list themselves as the same name. Treat a match as a lead, not as proof of who it is.
     */
    data class DirectoryHit(
        val userId: String,
        val nickname: String,
        val avatarPng: ByteArray?,
        val cardJson: String,
    )

    /**
     * Is this nickname free?
     *
     * `null` means the relay could not be reached — deliberately distinct from `false`, because
     * "taken" and "unknown" call for different words on screen and treating one as the other either
     * blocks a legitimate name or lets a duplicate through.
     */
    fun nicknameAvailable(nickname: String, ownUserId: String = ""): Boolean? = runCatching {
        val n = java.net.URLEncoder.encode(nickname, "UTF-8")
        val u = java.net.URLEncoder.encode(ownUserId, "UTF-8")
        open("/directory/available?nickname=$n&user_id=$u", "GET").run {
            if (responseCode != 200) return null
            org.json.JSONObject(inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
                .getBoolean("available")
        }
    }.getOrNull()

    /** 200 published, 409 the name belongs to somebody else, null the relay is unreachable. */
    fun claimDirectory(cardJson: String, visible: Boolean): Int? = runCatching {
        open("/directory?visible=" + (if (visible) "1" else "0"), "PUT").run {
            doOutput = true
            val body = cardJson.toByteArray(Charsets.UTF_8)
            setFixedLengthStreamingMode(body.size)
            outputStream.use { it.write(body) }
            responseCode
        }
    }.getOrNull()

    /** Publish, or withdraw by publishing a card with an empty nickname. */
    fun publishDirectory(cardJson: String): Boolean = runCatching {
        open("/directory", "PUT").run {
            doOutput = true
            val body = cardJson.toByteArray(Charsets.UTF_8)
            setFixedLengthStreamingMode(body.size)
            outputStream.use { it.write(body) }
            responseCode == 200
        }
    }.getOrDefault(false)

    /**
     * Look a nickname up, or null if the directory could not be reached at all.
     *
     * Null, not an empty list, and the distinction is the whole point. This used to swallow every
     * failure into `emptyList()`, so a directory that was down, unreachable or refusing TLS was
     * displayed as "Никого не нашлось" — the app stating as fact that a person does not exist when
     * all it knew was that it had not managed to ask. `nicknameAvailable` already drew this line;
     * search did not.
     *
     * @param base exists so the unreachable branch can be tested against an address that is
     * certainly dead, instead of a test that passes only while the real relay happens to be down.
     */
    fun searchDirectory(query: String, base: String = Relay.base): List<DirectoryHit>? = runCatching {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        open("/directory/search?q=$q", "GET", base).run {
            if (responseCode != 200) return null
            val text = inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val arr = org.json.JSONObject(text).getJSONArray("results")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DirectoryHit(
                    userId = o.getString("user_id"),
                    nickname = o.optString("nickname"),
                    avatarPng = o.optString("avatar_png_b64").takeIf { it.isNotEmpty() }?.let {
                        runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull()
                    },
                    cardJson = o.getString("card_json"),
                )
            }
        }
    }.getOrNull()

    /**
     * Leave one file of an encrypted segment in the relay's locker.
     *
     * Addressed by the hash of its path inside the segment, exactly as the onion store addresses
     * it, so the relay's filenames stay plain hex and no name that arrived over the network is ever
     * used as a path. The relay refuses anything no signed head refers to.
     */
    fun putSegmentFile(infoHashV2Hex: String, path: String, bytes: ByteArray): Boolean = runCatching {
        open("/segment/$infoHashV2Hex/${pathKey(path)}", "PUT").run {
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
            outputStream.use { it.write(bytes) }
            responseCode == 200
        }
    }.getOrDefault(false)

    fun getSegmentFile(infoHashV2Hex: String, path: String): ByteArray? = runCatching {
        open("/segment/$infoHashV2Hex/${pathKey(path)}", "GET").run {
            if (responseCode != 200) null else inputStream.use { it.readBytes() }
        }
    }.getOrNull()

    private fun pathKey(path: String) =
        HexSha256.of(path.toByteArray(Charsets.UTF_8))

    /** How many files of this segment the locker has handed out, or null if it cannot be asked. */
    fun segmentServed(infoHashV2Hex: String): Pair<Int, Int>? = runCatching {
        open("/segment/$infoHashV2Hex/status", "GET").run {
            if (responseCode != 200) return null
            val o = org.json.JSONObject(inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
            o.getInt("files") to o.getInt("served")
        }
    }.getOrNull()

    /**
     * Remove a segment from the locker, signed by the key that put it there.
     *
     * The signature is over `eidolang-drop:<infohash>` with the publisher's Ed25519 key — the same
     * key that signs the head vouching for the segment, so no new key and no new trust. Unsigned
     * deletion would be a way for anyone to wipe other people's undelivered messages.
     */
    fun dropSegment(infoHashV2Hex: String, signature: ByteArray): Boolean = runCatching {
        open("/segment/$infoHashV2Hex", "DELETE").run {
            doOutput = true
            setFixedLengthStreamingMode(signature.size)
            outputStream.use { it.write(signature) }
            responseCode == 200
        }
    }.getOrDefault(false)

    /** Ask the relay to join this swarm so it can pull the segment and seed it onward. */
    fun carry(infoHashV2Hex: String): Boolean = runCatching {
        open("/carry/$infoHashV2Hex", "POST").run {
            doOutput = true
            setFixedLengthStreamingMode(0)
            outputStream.use { }
            responseCode == 200
        }
    }.getOrDefault(false)
}

/**
 * The relay as a left-luggage office for encrypted segments.
 *
 * The relay was taken out of delivery because it was a server everybody depended on. What replaced
 * it — each phone serving its own bytes over its own onion — removed the dependency and introduced
 * a worse one: both people had to be awake, in the app, and through Tor at the same instant. Three
 * separate "the message never arrived" reports turned out to be that, not a bug.
 *
 * So the relay gets one narrow job back. It holds the same segment files the onion serves, and it
 * cannot read them: the payload is encrypted to the recipient and the relay takes part in no key
 * exchange. It verifies nothing about them either — the recipient still runs `NetworkAdmissionGate`
 * over whatever arrives, which rebuilds the torrent and refuses anything whose infohash, segment id
 * or message DAG does not match the signed head. A malicious locker can withhold bytes; it cannot
 * forge them, and it cannot read them.
 *
 * Onion stays the preferred route — faster, and nobody in the middle at all. This is the fallback
 * for the case that actually happens: the other person has closed the app.
 */
object RelayDelivery {

    fun publish(
        segment: org.eidolang.core.archive.ConversationSegmentArtifactV1,
        headTargetSha1Hex: String,
        headBencoded: ByteArray,
    ): Boolean {
        // Head first: the relay refuses a segment no signed head refers to, and that ordering is
        // what makes the locker unusable as a general-purpose file host.
        if (!Relay.putHead(headTargetSha1Hex, headBencoded)) return false
        return segment.files.all {
            Relay.putSegmentFile(segment.torrent.infoHashV2Hex, it.path, it.bytes)
        }
    }

    /** The same assembly `OnionDelivery.fetch` does, from the locker instead of the author. */
    fun fetch(infoHashV2Hex: String, torrentName: String): FetchResult? {
        val manifestBytes = Relay.getSegmentFile(infoHashV2Hex, MANIFEST) ?: return null
        val manifest = runCatching {
            SegmentParser.parseCanonical(manifestBytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return null
        val paths = buildList {
            add(CONVERSATION)
            manifest.identityEntries.forEach { add(it.path) }
            manifest.messageEntries.forEach { add(it.path) }
        }
        val files = paths.map { path ->
            TransportFile(path, Relay.getSegmentFile(infoHashV2Hex, path) ?: return null)
        } + TransportFile(MANIFEST, manifestBytes)
        return FetchResult(SwarmObject(torrentName, ByteArray(0), infoHashV2Hex, files), "relay")
    }

    /** Tell the locker the segment has been collected and need not be kept. */
    fun drop(
        publisher: org.eidolang.core.archive.JcaArchivePublisher,
        infoHashV2Hex: String,
    ): Boolean = runCatching {
        Relay.dropSegment(
            infoHashV2Hex,
            publisher.signBep44("eidolang-drop:$infoHashV2Hex".toByteArray(Charsets.US_ASCII)),
        )
    }.getOrDefault(false)

    private const val MANIFEST = "segment-manifest.json"
    private const val CONVERSATION = "conversation.json"
}

/**
 * Owns the single libtorrent session the app talks through.
 *
 * One session for the process: sessions are expensive, and a segment has to keep being seeded after
 * the send screen is gone or the recipient finds nothing.
 */
class EidoTransport private constructor(
    private val context: Context,
    private val identity: DevicePrivateCrypto,
) {
    val publisher = AndroidArchivePublisherStore(context).ensure(identity)

    val transport: Libtorrent4jTransport by lazy {
        Libtorrent4jTransport(
            storageRoot = File(context.filesDir, "swarm"),
            listenPort = 0,
            publisherSeed = publisher.seed,
            publisherPublicKey = publisher.publicKeyRaw,
            // Public BitTorrent DHT: this is how BEP44 discovery is meant to work, and it is what
            // makes the messenger serverless. It also means the head — conversation id, segment id,
            // infohash, publishing device id — is visible to the global DHT. Message contents stay
            // encrypted; the fact that something was published does not.
            bootstrapNodes = "dht.libtorrent.org:25401,router.bittorrent.com:6881,dht.transmissionbt.com:6881",
            listenInterface = "0.0.0.0",
            // A phone is usually unreachable from the internet. Port mapping helps behind a plain
            // home router; local discovery covers the case that actually works regardless — both
            // devices on the same Wi-Fi.
            enablePortMapping = true,
            enableLocalDiscovery = false,
        )
    }

    fun close() = runCatching { transport.close() }.let { }

    companion object {
        @Volatile private var instance: EidoTransport? = null

        fun of(context: Context, identity: DevicePrivateCrypto): EidoTransport =
            instance ?: synchronized(this) {
                instance ?: EidoTransport(context.applicationContext, identity).also { instance = it }
            }
    }
}

/**
 * Publish everything in a conversation and announce where it is.
 *
 * The whole conversation is re-archived rather than just the new message: a segment is the unit a
 * head points at, and a recipient who was offline for the first three messages should still get
 * them from the one head they can find.
 */
fun publishConversation(
    context: Context,
    service: LocalMessengerService,
    eido: EidoTransport,
    identity: DevicePrivateCrypto,
    conversationId: String,
    contactBundleCanonical: String,
): String {
    EidoRelaySettings.apply(context)
    val repository = AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context))
    val envelopes = repository.use { it.messages(conversationId).map { m -> m.envelopeCanonicalJson } }
    require(envelopes.isNotEmpty()) { "нечего отправлять" }

    val conversation = service.conversationDescriptor(conversationId)
    val contact = IdentityParser.parsePublicBundleCanonical(contactBundleCanonical)
    val segment = ConversationSegmentBuilder.build(
        conversation = conversation,
        identityBundles = listOf(
            PublicIdentityBundleV1(identity.user, identity.certificate),
            PublicIdentityBundleV1(contact.user, contact.device),
        ),
        messageCanonicalJson = envelopes,
    )
    // Seeding to the swarm is likewise nobody's critical path — the recipient reaches the bytes
    // through the locker or the onion — so it no longer holds up the send either.
    Thread { runCatching { eido.transport.seed(TransportObjects.fromArtifact(segment)) } }
        .apply { isDaemon = true; name = "swarm-seed" }.start()

    val head = ConversationHeadCodec.create(
        ConversationHeadValueV1(
            conversationId = conversation.conversationId,
            segmentId = segment.manifest.segmentId,
            torrentInfoHashV2 = segment.torrent.infoHashV2Hex,
            headMessageIds = segment.manifest.headMessageIds,
            publisherKeyId = eido.publisher.certificate.publisherKeyId,
            publisherDeviceId = eido.publisher.certificate.deviceId,
        ),
        seq = System.currentTimeMillis() / 1000,   // monotonic across restarts
        publisher = eido.publisher,
    )
    // The DHT is the slowest and least reliable of the three routes, and nothing waits on it: the
    // locker and the onion both carry the message on their own. Announcing it in the background
    // takes tens of seconds off every send.
    Thread {
        runCatching {
            eido.transport.putHead(
                MutableHeadRecord(
                    head.targetSha1Hex,
                    Bencode.encode(ConversationHeadCodec.mutablePutFields(head)),
                )
            )
        }
    }.apply { isDaemon = true; name = "dht-announce" }.start()
    val headBytes = Bencode.encode(ConversationHeadCodec.mutablePutFields(head))

    // Serve it from this device's own onion address. This is the route meant to replace the relay:
    // it needs nobody else's storage and nobody else's goodwill. The bytes are written here whether
    // or not Tor is up yet — publishing is local, and reachability catches up on its own.
    OnionDelivery.publish(OnionStore(context), segment, head.targetSha1Hex, headBytes)
    // The locker, but only if the direct route does not carry it first.
    //
    // Everything the relay holds is metadata it would otherwise never see — who published what, how
    // large, and when — so it should hold as little as possible for as short as possible. If the
    // recipient's own address answers, give them a short window to take the bytes straight from
    // here; only what nobody collected goes into the locker. And whatever does go in is dropped
    // again the moment the ledger shows it was collected.
    //
    // The window is bounded and the fallback is unconditional: if it expires with nothing
    // collected, the copy is uploaded. Being slightly too generous with the relay is recoverable;
    // a message that quietly reaches nobody is what made this unusable.
    val infoHash = segment.torrent.infoHashV2Hex
    // Straight into the locker, with no window to see whether the recipient takes it directly.
    //
    // That window probed the peer and then waited up to half a minute — 50 seconds of latency on
    // every send, spent on a privacy gain that deleting after collection already delivers. Sending
    // has to feel immediate; the copy is removed once it has been picked up.
    val inLocker =
        runCatching { RelayDelivery.publish(segment, head.targetSha1Hex, headBytes) }.getOrDefault(false)
    // Remember that these bytes are owed to this contact, so the moment they are collected can be
    // recognised. Without it a send is indistinguishable from a message that will sit here forever.
    DeliveryLedger(context).published(
        segment.torrent.infoHashV2Hex, contact.user.userId, segment.files.size,
        System.currentTimeMillis(),
    )
    EidoOnionNode.ensureStarted(context)

    println("PUBLISH head=${head.targetSha1Hex} onion=${EidoOnionNode.address}")
    // The relay used to store the head and seed the segment here. Both jobs existed only because
    // neither phone could be dialled, and an onion address ends that. What replaces it is not a
    // different server but no server: this device serves its own bytes.
    //
    // The honest consequence, and it is a real change: the sender has to be running for the
    // recipient to collect. The relay held messages for a sender who had gone; an onion service
    // exists only while its process does.
    // Deliberately not "отправлено". Publishing is local and practically cannot fail, so saying it
    // succeeded described the easy half and left the half that actually takes hours unspoken. The
    // message is kept and re-offered until somebody takes it; the second tick is what says they did.
    println("PUBLISH locker=$inLocker")
    return when {
        inLocker -> "отправлено — заберут, даже если закрыть приложение"
        EidoOnionNode.isReachable -> "готово, но заберут, только пока приложение открыто"
        else -> "сохранено; будет отдано, как только появится сеть"
    }
}

/**
 * Look for anything addressed to us by this contact and take it in.
 *
 * Returns how many new messages were admitted. Everything is verified by `NetworkAdmissionGate`
 * before it is allowed anywhere near the repository.
 */
/**
 * What one poll of one contact actually did.
 *
 * A count alone could not tell the five different ways receiving fails apart, and the screen turned
 * every one of them into "Ничего нового" — the same sentence for "there is nothing new", "I have no
 * key to verify this person with", "they are offline" and "the bytes did not come". The first of
 * those is not even retryable: without a publisher certificate this contact can *never* deliver,
 * and the app repeated the reassuring sentence forever.
 */
data class ReceiveResult(val admitted: Int, val problem: String? = null)

/**
 * Top up what is known about how to reach a contact.
 *
 * A card is captured once, at the instant of the introduction, and very often it has no onion
 * address in it: Tor takes a minute or two to come up, so somebody who has just installed the app
 * and made themselves findable publishes a card with no address at all. Nothing ever looked again.
 * That is how a contact ends up permanently unreachable by the one route that needs no server, and
 * silently demoted to the DHT — which this project already found unreliable for exactly this
 * lookup. Measured here: a contact added at 02:04 kept an empty address after its owner obtained
 * one at 02:12, and would have kept it forever.
 *
 * Only fills gaps, and only over the directory, which is the one place a card can be re-read
 * without the person's device being reachable — the very thing that is in doubt.
 *
 * @return true if a usable address is now on file.
 */
fun refreshContactTransport(context: Context, contactUserId: String, alias: String): Boolean {
    EidoRelaySettings.apply(context)
    if (ContactOnionStore(context).get(contactUserId) != null) return true
    val nickname = EidoHandle.nicknameOf(alias).trim()
    if (nickname.length < 2) return false
    val hit = Relay.searchDirectory(nickname)?.firstOrNull { it.userId == contactUserId } ?: return false
    val card = runCatching { ContactCard.read(hit.cardJson) }.getOrNull() ?: return false
    card.publisher?.let { ContactPublisherStore(context).put(contactUserId, it) }
    ContactOnionStore(context).put(contactUserId, card.onion)
    return card.onion.isNotEmpty()
}

fun receiveFrom(
    context: Context,
    service: LocalMessengerService,
    eido: EidoTransport,
    identity: DevicePrivateCrypto,
    contactUserId: String,
    contactBundleCanonical: String,
): ReceiveResult {
    val publisherCert = ContactPublisherStore(context).get(contactUserId)
        ?: return ReceiveResult(0, "нет ключа публикации — добавьте контакт заново по коду или ссылке")
    val conversation = ConversationConvention.oneToOne(identity.user.userId, contactUserId)

    // The recipient derives the same conversation, so it can compute the DHT address itself.
    runCatching {
        service.importConversation(
            org.eidolang.core.message.ConversationCanonical.descriptorJson(conversation),
            null, System.currentTimeMillis(),
        )
    }

    val salt = ByteArray(conversation.conversationId.length / 2) { i ->
        conversation.conversationId.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    val pk = org.eidolang.core.crypto.B64Url.decode(publisherCert.dhtPublicKeyRawB64)
    eido.transport.knowHead(pk, salt)
    val target = MessageDigest.getInstance("SHA-1").digest(pk + salt)
        .joinToString("") { "%02x".format(it) }

    println("RECEIVE target=$target conv=${conversation.conversationId.take(16)} dht=${eido.transport.dhtNodes()}")
    // The sender's own address first, if we have one: it is the only route that involves nobody but
    // the two of us. Relay next, because it works when both sides are firewalled; the DHT last,
    // because it proved unreliable for cross-device lookup in practice.
    EidoRelaySettings.apply(context)
    // Our own Tor, before anybody else's. `EidoOnionClient` dials a local SOCKS port, so when this
    // device's Tor has not finished starting every onion attempt fails instantly with
    // `ConnectException: failed to connect to /127.0.0.1 (port 59050)` — and the app then announced
    // that the *sender* was offline. Measured: twelve retries burned in 48 seconds against a socket
    // that was never going to be there, while the sender was serving perfectly well. Tor takes
    // about two and a half minutes to come up on a bridged network, which is exactly the window in
    // which somebody opens the app and presses "Обновить".
    EidoOnionNode.ensureStarted(context)
    val ourTorUp = EidoOnionNode.isReachable
    val senderOnion = ContactOnionStore(context).get(contactUserId)?.takeIf { ourTorUp }
    // Retried, not asked once.
    //
    // Reaching an onion service is not a single request: Tor has to find the service descriptor in
    // the distributed directory first, and on a bridged connection that took twelve attempts and a
    // full minute when measured on this project's own tablet. Delivery asked exactly once and then
    // declared the head missing — while the sender was serving it perfectly well. The invite path
    // has used `getRetrying` since the day it was written; delivery never did, which is why an
    // introduction by QR worked and a message sent afterwards did not.
    val headBytes = firstAnswer(
        HEAD_RACE_MS,
        // Fewer attempts than when the onion was the only route: it is racing two others that
        // answer in under a second, so a long retry here only delays giving up.
        { senderOnion?.let { EidoOnionClient.getRetrying(it, "/head/$target", attempts = 5, delayMs = 3_000) } },
        { Relay.getHead(target) },
        { eido.transport.getHead(target)?.fieldsBencoded },
    )
    println("RECEIVE head=${headBytes?.size ?: -1} onionKnown=${senderOnion != null}")
    val headRecord = headBytes?.let { MutableHeadRecord(target, it) }
        ?: run {
            println("RECEIVE head not found for $target ourTor=$ourTorUp")
            return ReceiveResult(
                0,
                if (!ourTorUp) "ваша сеть Tor ещё поднимается — повторите через минуту"
                else "объявления нет — устройство собеседника, похоже, не в сети",
            )
        }
    val head = ConversationHeadCodec.parseMutablePutFields(headRecord.fieldsBencoded)
    val value = ConversationHeadCodec.decodeValue(head.value)
    // Same order for the payload. The torrent name is recomputed from the head we just verified the
    // signature of, never taken from whoever answered.
    val name = OnionDelivery.torrentName(value.conversationId, value.segmentId)
    val fetched = firstAnswer(
        SEGMENT_RACE_MS,
        { senderOnion?.let { OnionDelivery.fetch(it, value.torrentInfoHashV2, name) } },
        { RelayDelivery.fetch(value.torrentInfoHashV2, name) },
        { eido.transport.fetch(value.torrentInfoHashV2) },
    )
        ?: return ReceiveResult(
            0,
            if (!ourTorUp) "ваша сеть Tor ещё поднимается — повторите через минуту"
            else "объявление есть, но само сообщение не забралось",
        )

    println("RECEIVE source=${fetched.source}")

    val accepted = NetworkAdmissionGate.admit(
        fetched, headRecord,
        ArchivePublisherCanonical.certificateJson(publisherCert),
        contactBundleCanonical,
    )
    val manifest = SegmentParser.parseCanonical(
        fetched.swarm.files.single { it.path == "segment-manifest.json" }.bytes.toString(Charsets.UTF_8)
    )
    // Count what is new, not what was accepted.
    //
    // A segment carries the whole conversation, and re-admitting a message that is already stored
    // is a NOOP that succeeds — so counting successes reported "Получено: 8" when exactly one
    // message had arrived. Observed on a real delivery: eight admitted, timeline 7 -> 8.
    // Message ids straight from the repository, not through `timeline()`.
    //
    // `timeline` decrypts every message in the conversation to build its result, and this needs
    // nothing but the ids. Calling it twice — once to see what was already here and once to count
    // what arrived — was 6.5 of the 11.7 seconds a poll took, all of it spent decrypting messages
    // that were then thrown away.
    fun storedIds(): Set<String> = runCatching {
        AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context))
            .use { r -> r.messages(conversation.conversationId).map { it.messageId }.toSet() }
    }.getOrDefault(emptySet())

    val before = storedIds()
    // Only what is not already here.
    //
    // A segment carries the whole conversation, so every poll used to re-admit every message ever
    // exchanged — each one an RSA-OAEP unwrap and a GCM open. Measured: 7.7 of 12.7 seconds spent
    // re-decrypting ten messages to discover that nine of them were already stored, and the cost
    // grows with the history rather than with what arrived. The manifest names each message, so the
    // ones already held can simply be skipped.
    val fresh = manifest.messageEntries.filter { it.messageId !in before }
    println("RECEIVE новых в сегменте: ${fresh.size} из ${manifest.messageEntries.size}")
    fresh.forEach { entry ->
        val raw = fetched.swarm.files.single { it.path == entry.path }.bytes.toString(Charsets.UTF_8)
        runCatching { service.admitIncoming(raw, System.currentTimeMillis()) }
    }
    val admitted = storedIds().count { it !in before }
    check(accepted.artifact.manifest.segmentId == value.segmentId)
    return ReceiveResult(admitted)
}

/** A poll must end. Heads are small and fast; a segment may be megabytes over a slow circuit. */
private const val HEAD_RACE_MS = 25_000L
private const val SEGMENT_RACE_MS = 90_000L

/**
 * Run the routes at once and take whichever answers first.
 *
 * Trying them in order cost the wait for every route that was going to fail. Measured on a bridged
 * network: twelve onion attempts for a head the sender was not serving took nine minutes before the
 * locker — which answers in well under a second — was asked at all. The routes are independent, so
 * queueing them bought nothing but their timeouts.
 *
 * Losers are interrupted rather than waited on, so a slow onion no longer sets the pace for a fast
 * relay. A cancelled attempt costs a dropped socket and nothing else: nothing here mutates state.
 */
fun <T> firstAnswer(timeoutMs: Long, vararg routes: () -> T?): T? {
    val pool = java.util.concurrent.Executors.newFixedThreadPool(routes.size) { r ->
        Thread(r, "route").apply { isDaemon = true }
    }
    return try {
        val done = java.util.concurrent.ExecutorCompletionService<T?>(pool)
        routes.forEach { route -> done.submit { runCatching(route).getOrNull() } }
        val deadline = System.currentTimeMillis() + timeoutMs
        var answer: T? = null
        repeat(routes.size) {
            if (answer == null) {
                val left = deadline - System.currentTimeMillis()
                // Bounded, or a poll waits for the slowest way of finding nothing. The onion route
                // alone retries twelve times while Tor looks for a descriptor that will never
                // appear because the sender is simply not running, and the screen sat on
                // "Проверяю" for minutes. Nothing here is lost by giving up: the message stays
                // where it is and the next poll asks again.
                if (left > 0) {
                    answer = runCatching {
                        done.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS)?.get()
                    }.getOrNull()
                }
            }
        }
        answer
    } finally {
        pool.shutdownNow()
    }
}

/**
 * Clear out of the locker everything that has since been collected.
 *
 * The relay is not meant to be an archive: it exists so a message survives its author closing the
 * app, and the moment the recipient has it that reason is gone. Run on every refresh, and safe to
 * repeat — a drop for something already gone is simply a 404.
 */
fun dropCollectedFromLocker(context: Context, eido: EidoTransport): Int {
    val ledger = DeliveryLedger(context)
    var dropped = 0
    // A pickup this device did not serve is still a pickup.
    //
    // The ledger learns about collection from our own onion server, so a recipient who took the
    // bytes from the locker instead left no trace here: the message stayed at one tick for good,
    // and the copy nobody needed was never removed. Ask the locker how much of each outstanding
    // segment it has handed out, and treat a fully served one as collected.
    ledger.all().filter { !it.collected }.forEach { record ->
        val status = Relay.segmentServed(record.infoHashV2Hex) ?: return@forEach
        val (files, served) = status
        if (files > 0 && served >= files) {
            ledger.served(record.infoHashV2Hex, "relay:$served", System.currentTimeMillis())
            ledger.markCollectedByLocker(record.infoHashV2Hex)
        }
    }
    ledger.all().filter { it.collected && !it.droppedFromLocker }.forEach { record ->
        if (RelayDelivery.drop(eido.publisher, record.infoHashV2Hex)) {
            ledger.markDropped(record.infoHashV2Hex)
            dropped++
        }
    }
    return dropped
}

/** Unused import guard so the sealed-store helper keeps its dependency explicit. */
private val keepHex = HexSha256
