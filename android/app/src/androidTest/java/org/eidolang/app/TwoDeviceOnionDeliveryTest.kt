package org.eidolang.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.archive.ArchivePublisherParser
import org.eidolang.core.archive.Bencode
import org.eidolang.core.archive.ConversationHeadCodec
import org.eidolang.core.archive.ConversationHeadValueV1
import org.eidolang.core.archive.ConversationSegmentBuilder
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.crypto.B64Url
import org.eidolang.core.crypto.IdentityCanonical
import org.eidolang.core.crypto.IdentityParser
import org.eidolang.core.crypto.PublicIdentityBundleV1
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.message.ConversationCanonical
import org.eidolang.core.model.EidogramAction
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.model.FixedTransform
import org.eidolang.core.multidevice.AndroidRootAuthority
import org.eidolang.core.multidevice.DeviceRosterAuthority
import org.eidolang.core.multidevice.DeviceRosterCanonical
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.LocalMessengerService
import org.eidolang.core.transport.MutableHeadRecord
import org.eidolang.core.transport.NetworkAdmissionGate
import org.eidolang.feature.home.ContactCard
import org.eidolang.feature.home.DeliveryLedger
import org.eidolang.feature.home.ContactOnionStore
import org.eidolang.feature.home.ConversationConvention
import org.eidolang.feature.home.EidoOnionClient
import org.eidolang.feature.home.EidoOnionNode
import org.eidolang.feature.home.EidoOnionServer
import org.eidolang.feature.home.EidoTor
import org.eidolang.feature.home.OnionDelivery
import org.eidolang.feature.home.OnionStore
import org.eidolang.transport.libtorrent4j.AndroidArchivePublisherStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * An eidogram delivered from one physical device to another over Tor, with no relay and no swarm.
 *
 * Everything so far has been one device talking to its own onion address. That exercises the whole
 * circuit — descriptor lookup, rendezvous, the lot — but it cannot show the thing the product is
 * actually for: that a *different* device, which shares no network, no DHT and no server with the
 * sender, can reach it knowing only what fits on a contact card.
 *
 * Driven by `tools/two-device-onion.sh`, which alternates devices and carries the two cards between
 * them as files. Nothing else crosses: the recipient is told no head target, no infohash and no
 * torrent name. It derives the conversation from the two user ids by the same convention the app
 * uses, computes the BEP44 target from the sender's publisher key itself, and takes the rest from
 * the signed head. Handing those over would have made the test prove only that bytes can be copied.
 *
 * The sender's step keeps serving for [SERVE_MS] because an onion service exists only while its
 * process does; the driver runs it in the background and starts the recipient underneath it.
 */
@NativeSessionHeavy
@TwoDeviceOnly
class TwoDeviceOnionDeliveryTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val random = SecureRandom()

    private fun ex(name: String) = File(File(ctx.filesDir, "exchange").apply { mkdirs() }, name)
    private fun repo() = AndroidSqliteMessengerRepository(ctx, AndroidRepositoryTextProtector(ctx))

    private fun document() = EidogramDocumentV1(
        listOf(EidogramAction.Add(0, "g000001", "circle.red.m", FixedTransform(390_000, 500_000), 0))
    )

    /** Identity, epoch-0 roster, onion address, and the card that carries all three. */
    @Test
    fun step1PublishOwnCard() {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        repo().use { r ->
            val service = LocalMessengerService(r, identity, random)
            val roster = DeviceRosterAuthority.issue(
                AndroidRootAuthority(), null, listOf(identity.certificate.deviceId), emptyList(),
            )
            runCatching { service.importDeviceRoster(DeviceRosterCanonical.json(roster), 100) }
        }
        val onion = EidoTor.of(ctx).startAndPublish(derivedKey = EidoOnionNode.derivedKey(ctx))
        assertNotNull("Tor не подключился: ${EidoTor.of(ctx).attempts}", onion)

        val publisher = AndroidArchivePublisherStore(ctx).ensure(identity)
        ex("card-self.json").writeText(
            ContactCard.write(identity, publisher.certificate, "Тест", null, onion!!), Charsets.UTF_8
        )
        println("ONION2 step1 user=${identity.user.userId.take(8)} onion=$onion")
    }

    /**
     * The sender: import the peer's card, author an eidogram, publish it on its own onion, and keep
     * answering.
     */
    @Test
    fun step2SendAndServe() {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val peerCard = ContactCard.read(ex("card-peer.json").readText(Charsets.UTF_8))
        val peer = IdentityParser.parsePublicBundleCanonical(peerCard.identityCanonical)
        val publisher = AndroidArchivePublisherStore(ctx).ensure(identity)

        val conversation = ConversationConvention.oneToOne(identity.user.userId, peer.user.userId)
        val segment = repo().use { r ->
            val service = LocalMessengerService(r, identity, random)
            runCatching { service.importContact(peerCard.identityCanonical, "Пир", 200) }
            runCatching {
                service.importConversation(ConversationCanonical.descriptorJson(conversation), "onion", 300)
            }
            service.send(conversation.conversationId, document(), 400)
            ConversationSegmentBuilder.build(
                conversation = conversation,
                identityBundles = listOf(
                    PublicIdentityBundleV1(identity.user, identity.certificate),
                    PublicIdentityBundleV1(peer.user, peer.device),
                ),
                messageCanonicalJson = r.messages(conversation.conversationId).map { it.envelopeCanonicalJson },
            )
        }
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
        OnionDelivery.publish(
            OnionStore(ctx), segment, head.targetSha1Hex,
            Bencode.encode(ConversationHeadCodec.mutablePutFields(head)),
        )
        // The ticks, wired exactly as the product wires them. Until now the ledger had only been
        // driven over loopback, where the same process plays both ends; what that cannot show is the
        // thing the second tick actually claims — that a *different* device came and took the bytes.
        val ledger = DeliveryLedger(ctx)
        val infoHash = segment.torrent.infoHashV2Hex
        ledger.published(infoHash, peer.user.userId, segment.files.size, System.currentTimeMillis())
        assertFalse(
            "доставка засчитана до того, как кто-либо пришёл",
            ledger.all().single { it.infoHashV2Hex == infoHash }.collected,
        )

        val onion = EidoTor.of(ctx).startAndPublish(derivedKey = EidoOnionNode.derivedKey(ctx))
        assertNotNull("Tor не подключился", onion)
        val server = EidoOnionServer(OnionStore(ctx)) { h, path ->
            ledger.served(h, path, System.currentTimeMillis())
        }
        server.start()

        // What the recipient must reproduce on its own. Written for the driver to compare against,
        // never sent to the recipient.
        ex("expected.txt").writeText(
            segment.manifest.headMessageIds.single() + "\n" + EidogramCanonical.contentHash(document()),
            Charsets.UTF_8,
        )
        println("ONION2 step2 serving $onion head=${head.targetSha1Hex} for up to ${SERVE_MS / 1000}s")
        // Poll rather than sleep the whole budget: the segment being collected is precisely the
        // condition this step is waiting for, so there is nothing left to wait for afterwards.
        val deadline = System.currentTimeMillis() + SERVE_MS
        var collected = false
        while (System.currentTimeMillis() < deadline && !collected) {
            Thread.sleep(5_000)
            collected = ledger.all().single { it.infoHashV2Hex == infoHash }.collected
        }
        println("ONION2 step2 served ${server.served} requests, collected=$collected")
        assertTrue("получатель ни разу не постучался", server.served > 0)
        assertTrue("сегмент не отдан целиком — вторая галочка не появилась", collected)
        assertEquals(
            "доставка приписана не тому контакту",
            peer.user.userId,
            ledger.all().single { it.infoHashV2Hex == infoHash }.contactUserId,
        )
        println("ONION2 PASS the second tick came from another device collecting the segment")
        server.stop()
    }

    /** The recipient: everything below is derived, not received. */
    @Test
    fun step3ReceiveOverOnion() {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val senderCard = ContactCard.read(ex("card-peer.json").readText(Charsets.UTF_8))
        val sender = IdentityParser.parsePublicBundleCanonical(senderCard.identityCanonical)
        val senderPublisher = requireNotNull(senderCard.publisher) { "визитка без publisher" }
        assertTrue("на визитке нет onion-адреса", senderCard.onion.isNotEmpty())
        ContactOnionStore(ctx).put(sender.user.userId, senderCard.onion)

        // Tor is needed to *reach* the sender; this device publishes nothing here.
        assertNotNull("Tor не подключился", EidoTor.of(ctx).startAndPublish(derivedKey = null))

        val conversation = ConversationConvention.oneToOne(identity.user.userId, sender.user.userId)
        val salt = ByteArray(conversation.conversationId.length / 2) { i ->
            conversation.conversationId.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val target = MessageDigest.getInstance("SHA-1")
            .digest(B64Url.decode(senderPublisher.dhtPublicKeyRawB64) + salt)
            .joinToString("") { "%02x".format(it) }

        val headBytes = EidoOnionClient.getRetrying(senderCard.onion, "/head/$target", attempts = 30) {
            println("ONION2 step3 head attempt $it")
        }
        assertNotNull("голова не получена с онион-адреса отправителя", headBytes)
        val item = ConversationHeadCodec.parseMutablePutFields(headBytes!!)
        val value = ConversationHeadCodec.decodeValue(item.value)

        val fetched = OnionDelivery.fetch(
            senderCard.onion, value.torrentInfoHashV2,
            OnionDelivery.torrentName(value.conversationId, value.segmentId),
        )
        assertNotNull("сегмент не собран через онион", fetched)

        val accepted = NetworkAdmissionGate.admit(
            fetched!!, MutableHeadRecord(target, headBytes),
            org.eidolang.core.archive.ArchivePublisherCanonical.certificateJson(
                ArchivePublisherParser.parseCanonical(
                    org.eidolang.core.archive.ArchivePublisherCanonical.certificateJson(senderPublisher)
                )
            ),
            senderCard.identityCanonical,
        )
        println("ONION2 step3 admitted segment=${accepted.artifact.manifest.segmentId.take(16)}")

        var messageId = ""
        var contentHash = ""
        repo().use { r ->
            val service = LocalMessengerService(r, identity, random)
            runCatching { service.importContact(senderCard.identityCanonical, "Отправитель", 500) }
            runCatching {
                service.importConversation(ConversationCanonical.descriptorJson(conversation), "onion", 510)
            }
            accepted.artifact.manifest.messageEntries.forEach { entry ->
                val raw = fetched.swarm.files.single { it.path == entry.path }.bytes.toString(Charsets.UTF_8)
                service.admitIncoming(raw, 520)
            }
            // Not `single()`: a repeat run leaves the previous eidogram in place, and the point is
            // this delivery, not an empty device. The head of the segment just admitted names which
            // message that is, and it comes from the fetched bytes rather than from the driver.
            val wanted = accepted.artifact.manifest.headMessageIds.single()
            val item2 = service.timeline(conversation.conversationId).single { it.messageId == wanted }
            messageId = item2.messageId
            contentHash = EidogramCanonical.contentHash(item2.document)
        }
        ex("received.txt").writeText("$messageId\n$contentHash", Charsets.UTF_8)
        println("ONION2 PASS received over onion message=$messageId hash=$contentHash")
    }

    private companion object {
        /** Long enough for the recipient's Tor to bootstrap and fetch, short enough to fail fast. */
        const val SERVE_MS = 420_000L
    }
}
