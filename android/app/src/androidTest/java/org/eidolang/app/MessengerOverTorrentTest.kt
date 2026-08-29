package org.eidolang.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.archive.ConversationHeadCodec
import org.eidolang.core.archive.ConversationHeadValueV1
import org.eidolang.core.archive.ConversationSegmentBuilder
import org.eidolang.core.archive.Ed25519Raw
import org.eidolang.core.archive.SegmentParser
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.crypto.IdentityCanonical
import org.eidolang.core.crypto.IdentityParser
import org.eidolang.core.crypto.PublicIdentityBundleV1
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.message.MessageCanonical
import org.eidolang.core.model.EidogramAction
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.model.FixedTransform
import org.eidolang.core.multidevice.DeviceRosterAuthority
import org.eidolang.core.multidevice.DeviceRosterCanonical
import org.eidolang.core.multidevice.AndroidRootAuthority
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.LocalMessengerService
import org.eidolang.core.transport.MutableHeadRecord
import org.eidolang.core.transport.NetworkAdmissionGate
import org.eidolang.core.transport.TransportObjects
import org.eidolang.transport.libtorrent4j.AndroidArchivePublisherStore
import org.eidolang.transport.libtorrent4j.Libtorrent4jTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom

/**
 * The messenger actually delivering a message between two devices over BitTorrent.
 *
 * Alice authors an eidogram on one device, archives it into an R16 conversation segment, seeds it
 * and publishes a BEP44 head. Bob, on a different device, resolves the head, fetches the segment
 * over a real BitTorrent connection, runs `NetworkAdmissionGate`, admits the envelopes into his own
 * repository and reads the eidogram back.
 *
 * Driven by `tools/messenger-over-torrent.sh`, which carries the small control-plane artifacts
 * (identity bundles, conversation descriptor, publisher certificate, head record) between the two
 * devices as files and bridges the data path with `adb forward`. The *message payload* travels
 * over BitTorrent; the control plane does not, because the two devices share no DHT.
 */
@NativeSessionHeavy
@TwoDeviceOnly
class MessengerOverTorrentTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val random = SecureRandom()

    private fun ex(name: String) = File(File(ctx.filesDir, "exchange").apply { mkdirs() }, name)
    private fun File.text() = readText(Charsets.UTF_8)
    private fun File.put(s: String) = writeText(s, Charsets.UTF_8)

    private fun repo() = AndroidSqliteMessengerRepository(ctx, AndroidRepositoryTextProtector(ctx))

    private fun glyph(x: Int) = EidogramDocumentV1(
        listOf(EidogramAction.Add(0, "g000001", "circle.red.m", FixedTransform(x, 500_000), 0))
    )

    // ------------------------------------------------------------------ alice

    /** Fresh primary identity, epoch-0 roster, exported public bundle. */
    @Test
    fun step1IdentityAndExport() {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        repo().use { r ->
            val service = LocalMessengerService(r, identity, random)
            val roster = DeviceRosterAuthority.issue(
                AndroidRootAuthority(), null, listOf(identity.certificate.deviceId), emptyList(),
            )
            val rosterJson = DeviceRosterCanonical.json(roster)
            // Idempotent across reruns: the identity lives in AndroidKeyStore, so a repeat run
            // re-issues an epoch-0 roster while the repository already holds one, and
            // DeviceRosterVerifier.advance refuses a non-advancing epoch. The stored roster is
            // equivalent, so keep it.
            runCatching { service.importDeviceRoster(rosterJson, 100) }
                .onFailure { println("MSG step1 roster already present: ${it.message}") }
            ex("roster-${identity.user.userId.take(8)}.json").put(rosterJson)
        }
        ex("identity-self.json").put(
            IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(identity.user, identity.certificate))
        )
        println("MSG step1 user=${identity.user.userId}")
    }

    /**
     * Alice imports Bob, opens a conversation, sends an eidogram, archives it into an R16 segment,
     * seeds it and publishes the head. Keeps seeding until the driver stops the process.
     */
    @Test
    fun step2AliceSendsAndSeeds() {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val publisher = AndroidArchivePublisherStore(ctx).ensure(identity)
        val bobRaw = ex("identity-bob.json").text()
        val bob = IdentityParser.parsePublicBundleCanonical(bobRaw)

        val transport = Libtorrent4jTransport(
            File(ctx.filesDir, "swarm-alice"), listenPort = 6881,
            publisherSeed = publisher.seed,
            publisherPublicKey = publisher.publicKeyRaw,
            listenInterface = "127.0.0.1",
            localDhtTuning = true,
        )
        repo().use { r ->
            val service = LocalMessengerService(r, identity, random)
            service.importContact(bobRaw, "Bob", 200)
            service.importDeviceRoster(ex("roster-bob.json").text(), 201)

            val conversation = service.createConversation(
                listOf(bob.user.userId), title = "over torrent", createdAtMs = 300,
            )
            val document = glyph(390_000)
            val message = service.send(conversation.conversationId, document, 400)

            val segment = ConversationSegmentBuilder.build(
                conversation = conversation,
                identityBundles = listOf(
                    PublicIdentityBundleV1(identity.user, identity.certificate),
                    PublicIdentityBundleV1(bob.user, bob.device),
                ),
                messageCanonicalJson = listOf(MessageCanonical.envelopeJson(message)),
            )
            transport.seed(TransportObjects.fromArtifact(segment))

            val head = ConversationHeadCodec.create(
                ConversationHeadValueV1(
                    conversationId = conversation.conversationId,
                    segmentId = segment.manifest.segmentId,
                    torrentInfoHashV2 = segment.torrent.infoHashV2Hex,
                    headMessageIds = segment.manifest.headMessageIds,
                    publisherKeyId = publisher.certificate.publisherKeyId,
                    publisherDeviceId = publisher.certificate.deviceId,
                ),
                seq = 1, publisher = publisher,
            )
            val record = MutableHeadRecord(
                head.targetSha1Hex,
                org.eidolang.core.archive.Bencode.encode(ConversationHeadCodec.mutablePutFields(head)),
            )

            ex("conversation.json").put(service.conversationDescriptorCanonical(conversation.conversationId))
            ex("publisher-cert.json").put(
                org.eidolang.core.archive.ArchivePublisherCanonical.certificateJson(publisher.certificate)
            )
            ex("head.bencode.b64").put(android.util.Base64.encodeToString(record.fieldsBencoded, android.util.Base64.NO_WRAP))
            ex("head-target.txt").put(head.targetSha1Hex)
            ex("infohash.txt").put(segment.torrent.infoHashV2Hex)
            ex("expected-message-id.txt").put(message.messageId)
            ex("expected-document.sha256").put(EidogramCanonical.contentHash(document))

            println("MSG step2 conversation=${conversation.conversationId}")
            println("MSG step2 message=${message.messageId}")
            println("MSG step2 infohash=${segment.torrent.infoHashV2Hex}")
            println("MSG step2 SEEDING on 127.0.0.1:6881")
        }
        // Hold the swarm open while the other device fetches; the driver kills this process.
        Thread.sleep(180_000)
        transport.close()
    }

    // -------------------------------------------------------------------- bob

    /**
     * Bob resolves the head he was handed, pulls the segment from Alice over BitTorrent, admits it
     * and must read back the identical eidogram.
     */
    @Test
    fun step3BobFetchesAndReads() {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val aliceRaw = ex("identity-alice.json").text()
        val alice = IdentityParser.parsePublicBundleCanonical(aliceRaw)
        val infoHash = ex("infohash.txt").text().trim()
        val peer = ex("alice-endpoint.txt").text().trim()   // host:port, written by the driver

        val transport = Libtorrent4jTransport(
            File(ctx.filesDir, "swarm-bob"), listenPort = 6883,
            listenInterface = "0.0.0.0", localDhtTuning = true,
        )
        try {
            repo().use { r ->
                val service = LocalMessengerService(r, identity, random)
                service.importContact(aliceRaw, "Alice", 200)
                service.importDeviceRoster(ex("roster-alice.json").text(), 201)
                service.importConversation(ex("conversation.json").text(), "over torrent", 300)

                // Kick the fetch off, then point it at Alice: there is no shared DHT.
                val worker = Thread { transport.fetch(infoHash) }.apply { isDaemon = true; start() }
                Thread.sleep(2000)
                val (host, port) = peer.split(":").let { it[0] to it[1].toInt() }
                transport.connectPeer(infoHash, host, port)
                worker.join(120_000)

                val fetched = transport.fetch(infoHash)
                assertNotNull("segment never arrived from the other device", fetched)
                println("MSG step3 fetched ${fetched!!.swarm.files.size} files from ${fetched.source}")

                val head = MutableHeadRecord(
                    ex("head-target.txt").text().trim(),
                    android.util.Base64.decode(ex("head.bencode.b64").text().trim(), android.util.Base64.NO_WRAP),
                )
                val publisherCert = ex("publisher-cert.json").text()
                val accepted = NetworkAdmissionGate.admit(fetched, head, publisherCert, aliceRaw)
                println("MSG step3 admitted segment=${accepted.artifact.manifest.segmentId}")

                // Admit every envelope the segment carries into Bob's own repository.
                val manifest = SegmentParser.parseCanonical(
                    fetched.swarm.files.single { it.path == "segment-manifest.json" }
                        .bytes.toString(Charsets.UTF_8)
                )
                var admitted = 0
                manifest.messageEntries.forEach { entry ->
                    val raw = fetched.swarm.files.single { it.path == entry.path }
                        .bytes.toString(Charsets.UTF_8)
                    service.admitIncoming(raw, 500)
                    admitted++
                }
                assertTrue("no message was admitted", admitted > 0)

                val conversationId = org.eidolang.core.message.ConversationParser
                    .parseCanonical(ex("conversation.json").text()).conversationId
                val timeline = service.timeline(conversationId)
                assertEquals("Bob should hold exactly the delivered message", 1, timeline.size)
                assertEquals(
                    "MessageId changed in delivery",
                    ex("expected-message-id.txt").text().trim(), timeline.single().messageId,
                )
                assertEquals(
                    "the eidogram Bob decrypted is not the one Alice sent",
                    ex("expected-document.sha256").text().trim(),
                    EidogramCanonical.contentHash(timeline.single().document),
                )
                assertEquals(alice.user.userId, timeline.single().senderUserId)
                println("MSG step3 DELIVERED message=${timeline.single().messageId}")
                println("MSG step3 document hash matches the sender's")
            }
        } finally {
            transport.close()
        }
    }
}
