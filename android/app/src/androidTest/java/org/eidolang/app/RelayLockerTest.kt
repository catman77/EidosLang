package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.archive.ArchivePublisherCanonical
import org.eidolang.core.archive.Bencode
import org.eidolang.core.archive.ConversationHeadCodec
import org.eidolang.core.archive.ConversationHeadValueV1
import org.eidolang.core.archive.ConversationSegmentBuilder
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.crypto.JvmPrivateIdentity
import org.eidolang.core.crypto.PublicIdentityBundleV1
import org.eidolang.core.message.ConversationCanonical
import org.eidolang.core.model.EidogramAction
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.model.FixedTransform
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.LocalMessengerService
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.transport.MutableHeadRecord
import org.eidolang.core.transport.NetworkAdmissionGate
import org.eidolang.feature.home.ConversationConvention
import org.eidolang.feature.home.Relay
import org.eidolang.feature.home.RelayDelivery
import org.eidolang.transport.libtorrent4j.AndroidArchivePublisherStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * A message survives its author going away.
 *
 * This is the whole point of giving the relay one job back. Every other route needs the sender to
 * be awake, in the app and through Tor at the same instant as the reader — three separate reports
 * of "the message never arrived" turned out to be exactly that, and no amount of retrying fixes it.
 *
 * Nothing here talks to the sender: the segment is built, left in the locker, and then fetched and
 * admitted purely from the relay, with the author's device represented by nothing at all.
 */
class RelayLockerTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val random = SecureRandom()

    /**
     * Take the stand-in peer back out of the address book.
     *
     * It imported one on every run and left it there, so seven "Локер" entries accumulated in a
     * real installation's contact list — unreadable names that could never deliver anything. A test
     * may put whatever it needs into the device; it does not get to leave it there.
     */
    @org.junit.After
    fun removeStandInPeer() {
        runCatching {
            val service = LocalMessengerService(
                AndroidSqliteMessengerRepository(ctx, AndroidRepositoryTextProtector(ctx)),
                AndroidKeystoreIdentityStore(ctx).ensure(), random,
            )
            service.contactSummaries().filter { it.alias == "Локер" }
                .forEach { service.deleteContact(it.userId) }
        }
    }

    @Test
    fun aSegmentLeftInTheLockerIsCollectedWithoutItsAuthor() {
        org.eidolang.feature.home.EidoRelaySettings.apply(ctx)
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val publisher = AndroidArchivePublisherStore(ctx).ensure(identity)
        val peer = JvmPrivateIdentity.generate(random)
        val conversation = ConversationConvention.oneToOne(identity.user.userId, peer.user.userId)
        val document = EidogramDocumentV1(
            listOf(EidogramAction.Add(0, "g000001", "circle.red.m", FixedTransform(500_000, 500_000), 0))
        )

        val envelopes = AndroidSqliteMessengerRepository(ctx, AndroidRepositoryTextProtector(ctx)).use { r ->
            val service = LocalMessengerService(r, identity, random)
            runCatching { service.importContact(org.eidolang.core.crypto.IdentityCanonical.publicBundleJson(
                    PublicIdentityBundleV1(peer.user, peer.certificate)
                ), "Локер", 700) }
            runCatching {
                service.importConversation(ConversationCanonical.descriptorJson(conversation), "locker", 710)
            }
            service.send(conversation.conversationId, document, 720)
            r.messages(conversation.conversationId).map { it.envelopeCanonicalJson }
        }
        val segment = ConversationSegmentBuilder.build(
            conversation = conversation,
            identityBundles = listOf(
                PublicIdentityBundleV1(identity.user, identity.certificate),
                PublicIdentityBundleV1(peer.user, peer.certificate),
            ),
            messageCanonicalJson = envelopes,
        )
        val head = ConversationHeadCodec.create(
            ConversationHeadValueV1(
                conversationId = conversation.conversationId,
                segmentId = segment.manifest.segmentId,
                torrentInfoHashV2 = segment.torrent.infoHashV2Hex,
                headMessageIds = segment.manifest.headMessageIds,
                publisherKeyId = publisher.certificate.publisherKeyId,
                publisherDeviceId = publisher.certificate.deviceId,
            ),
            seq = System.currentTimeMillis() / 1000,
            publisher = publisher,
        )
        val headBytes = Bencode.encode(ConversationHeadCodec.mutablePutFields(head))

        assertTrue(
            "камера хранения не приняла сегмент",
            RelayDelivery.publish(segment, head.targetSha1Hex, headBytes),
        )

        // From here on the author does not exist as far as this test is concerned: no onion, no
        // swarm, no local store — only the locker.
        val storedHead = Relay.getHead(head.targetSha1Hex)
        assertNotNull("голова не сохранилась в камере", storedHead)
        val fetched = RelayDelivery.fetch(
            segment.torrent.infoHashV2Hex,
            org.eidolang.feature.home.OnionDelivery.torrentName(
                conversation.conversationId, segment.manifest.segmentId
            ),
        )
        assertNotNull("сегмент не забрался из камеры", fetched)
        assertEquals("источник не камера хранения", "relay", fetched!!.source)

        // The gate decides, not the locker. It rebuilds the torrent and checks the infohash, the
        // segment id and the DAG against the signed head, so a locker that tampered would be caught
        // here rather than trusted.
        val accepted = NetworkAdmissionGate.admit(
            fetched, MutableHeadRecord(head.targetSha1Hex, storedHead!!),
            ArchivePublisherCanonical.certificateJson(publisher.certificate),
            org.eidolang.core.crypto.IdentityCanonical.publicBundleJson(
                PublicIdentityBundleV1(identity.user, identity.certificate)
            ),
        )
        assertEquals(
            "камера отдала не тот сегмент",
            segment.manifest.segmentId,
            accepted.artifact.manifest.segmentId,
        )
        // A pickup performed by the locker must reach the author's ledger.
        //
        // The ledger learns of collection from this device's own onion server, so a recipient who
        // took the bytes from the locker left no trace: the message stayed at one tick for good,
        // and the copy nobody needed was never dropped. The fetch above has just served every file
        // of this segment, so the locker must now report it fully served.
        val status = Relay.segmentServed(segment.torrent.infoHashV2Hex)
        assertNotNull("камера не сообщает, сколько отдала", status)
        assertTrue(
            "камера отдала сегмент целиком, но не признаётся: $status",
            status!!.first > 0 && status.second >= status.first,
        )
        println("LOCKER PASS камера сообщает о полной выдаче: ${status.second} из ${status.first}")

        // The other half of the claim, and the half that is easy to merely assert: the locker can
        // withhold bytes but cannot forge them. A message file is corrupted in place — the manifest
        // is left intact so the fetch still assembles — and the gate must refuse the result.
        val victim = segment.manifest.messageEntries.first().path
        val original = requireNotNull(Relay.getSegmentFile(segment.torrent.infoHashV2Hex, victim))
        val tampered = original.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertTrue(
            "не удалось подменить файл в камере — проверка бессмысленна",
            Relay.putSegmentFile(segment.torrent.infoHashV2Hex, victim, tampered),
        )
        val poisoned = RelayDelivery.fetch(
            segment.torrent.infoHashV2Hex,
            org.eidolang.feature.home.OnionDelivery.torrentName(
                conversation.conversationId, segment.manifest.segmentId
            ),
        )
        assertNotNull("подменённый сегмент не собрался — проверять нечего", poisoned)
        val refused = runCatching {
            NetworkAdmissionGate.admit(
                poisoned!!, MutableHeadRecord(head.targetSha1Hex, storedHead),
                ArchivePublisherCanonical.certificateJson(publisher.certificate),
                org.eidolang.core.crypto.IdentityCanonical.publicBundleJson(
                    PublicIdentityBundleV1(identity.user, identity.certificate)
                ),
            )
        }.isFailure
        assertTrue("камера хранения смогла подменить содержимое", refused)
        Relay.putSegmentFile(segment.torrent.infoHashV2Hex, victim, original)
        println("LOCKER PASS подмена в камере отвергается допуском")

        // The locker is not an archive. Once the ledger shows the segment was collected there is
        // no reason for a server to keep holding it, and the metadata it represents — who published
        // what, how large, when — is the entire privacy cost of having a locker at all.
        val other = org.eidolang.core.archive.Ed25519Raw.sign(
            ByteArray(32) { 7 }, "eidolang-drop:${segment.torrent.infoHashV2Hex}".toByteArray(Charsets.US_ASCII)
        )
        assertTrue(
            "камеру очистил кто-то, кроме владельца",
            !Relay.dropSegment(segment.torrent.infoHashV2Hex, other),
        )
        assertNotNull(
            "чужая подпись всё-таки удалила сегмент",
            Relay.getSegmentFile(segment.torrent.infoHashV2Hex, victim),
        )
        println("LOCKER PASS чужая подпись сегмент не удаляет")

        assertTrue(
            "владелец не смог убрать сегмент из камеры",
            RelayDelivery.drop(publisher, segment.torrent.infoHashV2Hex),
        )
        assertNull(
            "сегмент остался в камере после удаления",
            Relay.getSegmentFile(segment.torrent.infoHashV2Hex, victim),
        )
        println("LOCKER PASS владелец убирает забранный сегмент из камеры")

        println(
            "LOCKER PASS сегмент забран из камеры без автора: " +
                "${accepted.artifact.manifest.segmentId.take(16)} " +
                "content=${EidogramCanonical.contentHash(document).take(16)}"
        )
    }
}
