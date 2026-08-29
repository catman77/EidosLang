package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.archive.ArchivePublisherCanonical
import org.eidolang.core.archive.Bencode
import org.eidolang.core.archive.ConversationHeadCodec
import org.eidolang.core.archive.ConversationHeadValueV1
import org.eidolang.core.archive.ConversationSegmentBuilder
import org.eidolang.core.archive.JcaArchivePublisher
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.IdentityCanonical
import org.eidolang.core.crypto.JvmPrivateIdentity
import org.eidolang.core.crypto.PublicIdentityBundleV1
import org.eidolang.core.model.EidogramAction
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.model.FixedTransform
import org.eidolang.core.multidevice.DeviceRosterAuthority
import org.eidolang.core.multidevice.DeviceRosterCanonical
import org.eidolang.core.multidevice.JvmRootAuthority
import org.eidolang.core.repository.InMemoryMessengerRepository
import org.eidolang.core.repository.LocalMessengerService
import org.eidolang.core.transport.MutableHeadRecord
import org.eidolang.core.transport.NetworkAdmissionGate
import org.eidolang.feature.home.EidoOnionClient
import org.eidolang.feature.home.EidoOnionServer
import org.eidolang.feature.home.EidoTor
import org.eidolang.feature.home.OnionDelivery
import org.eidolang.feature.home.OnionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * A real eidogram delivered over an onion address, with no relay and no swarm.
 *
 * The previous gate showed the circuit carries arbitrary bytes. This one carries the actual thing:
 * an eidogram is authored, archived into an R16 conversation segment, published to this device's
 * onion service, fetched back through a Tor circuit file by file, and put through
 * `NetworkAdmissionGate` — which rebuilds the torrent from the fetched files and refuses anything
 * whose infohash, segment id, conversation id or message DAG disagrees with the signed head.
 *
 * Everything runs on generated identities against an in-memory repository. The device's own
 * messenger database and AndroidKeyStore identity are deliberately untouched: this test must not add
 * a contact or a conversation to the phone somebody is actually using.
 */
@NativeSessionHeavy
class OnionDeliveryTest {

    @Test
    fun eidogramTravelsOverTheOnionAndPassesAdmission() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val random = SecureRandom()

        // ---- author a real conversation segment, entirely in memory
        val alice = JvmPrivateIdentity.generate(random)
        val bob = JvmPrivateIdentity.generate(random)
        val repo = InMemoryMessengerRepository()
        val service = LocalMessengerService(repo, alice, random)
        service.importDeviceRoster(
            DeviceRosterCanonical.json(
                DeviceRosterAuthority.issue(
                    JvmRootAuthority.fromPrimary(alice, random), null,
                    listOf(alice.certificate.deviceId), emptyList(),
                )
            ), 100,
        )
        service.importContact(
            IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(bob.user, bob.certificate)), "Bob", 200,
        )
        service.importDeviceRoster(
            DeviceRosterCanonical.json(
                DeviceRosterAuthority.issue(
                    JvmRootAuthority.fromPrimary(bob, random), null,
                    listOf(bob.certificate.deviceId), emptyList(),
                )
            ), 210,
        )

        val conversation = service.createConversation(listOf(bob.user.userId), title = "over onion", createdAtMs = 300)
        val document = EidogramDocumentV1(
            listOf(EidogramAction.Add(0, "g000001", "circle.red.m", FixedTransform(390_000, 500_000), 0))
        )
        val message = service.send(conversation.conversationId, document, 400)

        val segment = ConversationSegmentBuilder.build(
            conversation = service.conversationDescriptor(conversation.conversationId),
            identityBundles = listOf(
                PublicIdentityBundleV1(alice.user, alice.certificate),
                PublicIdentityBundleV1(bob.user, bob.certificate),
            ),
            messageCanonicalJson = repo.messages(conversation.conversationId).map { it.envelopeCanonicalJson },
        )
        val publisher = JcaArchivePublisher.create(alice, random)
        val head = ConversationHeadCodec.create(
            ConversationHeadValueV1(
                conversationId = conversation.conversationId,
                segmentId = segment.manifest.segmentId,
                torrentInfoHashV2 = segment.torrent.infoHashV2Hex,
                headMessageIds = segment.manifest.headMessageIds,
                publisherKeyId = publisher.certificate.publisherKeyId,
                publisherDeviceId = publisher.certificate.deviceId,
            ),
            seq = 1,
            publisher = publisher,
        )
        val headBytes = Bencode.encode(ConversationHeadCodec.mutablePutFields(head))

        // ---- publish it where this device's onion service can hand it out
        val store = OnionStore(ctx)
        OnionDelivery.publish(store, segment, head.targetSha1Hex, headBytes)

        val tor = EidoTor.of(ctx)
        val onion = tor.startAndPublish()
        assertNotNull("Tor не подключился: ${tor.attempts}", onion)
        val server = EidoOnionServer(store)
        server.start()

        try {
            assertNotNull(
                "онион не отвечает",
                EidoOnionClient.getRetrying(onion!!, "/hello") { println("DELIVERY descriptor attempt $it") },
            )

            // ---- fetch it back the way a second device would
            val fetchedHead = EidoOnionClient.get(onion, "/head/${head.targetSha1Hex}")
            assertNotNull("голова не отдана", fetchedHead)

            // The torrent name is recomputed from the signed head, never taken from the sender.
            val name = OnionDelivery.torrentName(conversation.conversationId, segment.manifest.segmentId)
            val fetched = OnionDelivery.fetch(onion, segment.torrent.infoHashV2Hex, name)
            assertNotNull("сегмент не собран", fetched)
            assertEquals("не все файлы сегмента получены", segment.files.size, fetched!!.swarm.files.size)
            println("ONION DELIVERY fetched ${fetched.swarm.files.size} files over the circuit")

            val accepted = NetworkAdmissionGate.admit(
                fetched,
                MutableHeadRecord(head.targetSha1Hex, fetchedHead!!),
                ArchivePublisherCanonical.certificateJson(publisher.certificate),
                IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(alice.user, alice.certificate)),
            )
            assertEquals(segment.manifest.segmentId, accepted.artifact.manifest.segmentId)
            println("ONION PASS segment admitted by NetworkAdmissionGate after travelling over Tor")

            // ---- and it is the same eidogram, not merely a well-formed segment
            val bobRepo = InMemoryMessengerRepository()
            val bobService = LocalMessengerService(bobRepo, bob, random)
            bobService.importDeviceRoster(
                DeviceRosterCanonical.json(
                    DeviceRosterAuthority.issue(
                        JvmRootAuthority.fromPrimary(bob, random), null,
                        listOf(bob.certificate.deviceId), emptyList(),
                    )
                ), 500,
            )
            bobService.importContact(
                IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(alice.user, alice.certificate)), "Alice", 510,
            )
            bobService.importConversation(
                org.eidolang.core.message.ConversationCanonical.descriptorJson(
                    service.conversationDescriptor(conversation.conversationId)
                ), null, 520,
            )
            val manifest = accepted.artifact.manifest
            var admitted = 0
            manifest.messageEntries.forEach { entry ->
                val raw = fetched.swarm.files.single { it.path == entry.path }.bytes.toString(Charsets.UTF_8)
                bobService.admitIncoming(raw, 530)
                admitted++
            }
            assertEquals(1, admitted)
            val received = bobService.timeline(conversation.conversationId).single()
            assertEquals("MessageId не сохранился", message.messageId, received.messageId)
            assertEquals(
                "эйдограмма изменилась в пути",
                EidogramCanonical.contentHash(document),
                EidogramCanonical.contentHash(received.document),
            )
            println("ONION PASS Bob reads the identical eidogram: ${message.messageId}")

            // Negative control: a segment whose files were never published must not resolve. Without
            // this, "fetch returned something" could be true of any onion that answers at all.
            assertNull(
                "неопубликованный сегмент не должен собираться",
                OnionDelivery.fetch(onion, "00".repeat(32), name),
            )
            assertTrue(server.served > segment.files.size)
            println("ONION PASS unpublished segment does not resolve")
        } finally {
            server.stop()
        }
    }
}
