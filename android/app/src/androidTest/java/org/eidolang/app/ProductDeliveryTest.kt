package org.eidolang.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
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
import org.eidolang.feature.home.ContactCard
import org.eidolang.feature.home.ContactPublisherStore
import org.eidolang.feature.home.ConversationConvention
import org.eidolang.feature.home.EidoTransport
import org.eidolang.feature.home.publishConversation
import org.eidolang.feature.home.receiveFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom

/**
 * Delivery between two devices over the open internet, through the product's own code path.
 *
 * The only thing the driver carries between the devices is the contact card, which is exchanged out
 * of band by design — it is what people hand each other. Everything after that is what the app does
 * when you press "Отправить" and "Обновить": the head goes into the public DHT, the recipient
 * derives the same conversation, resolves the head and pulls the segment from a real swarm.
 *
 * No adb bridge, no explicit peer, no loopback.
 */
@TwoDeviceOnly
@NativeSessionHeavy
class ProductDeliveryTest {

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val random = SecureRandom()

    private fun ex(name: String) = File(File(ctx.filesDir, "exchange").apply { mkdirs() }, name)

    private fun service(identity: org.eidolang.core.crypto.DevicePrivateCrypto) =
        LocalMessengerService(
            AndroidSqliteMessengerRepository(ctx, AndroidRepositoryTextProtector(ctx)), identity, random,
        )

    private fun ownRoster(service: LocalMessengerService, identity: org.eidolang.core.crypto.DevicePrivateCrypto) {
        runCatching {
            service.importDeviceRoster(
                DeviceRosterCanonical.json(
                    DeviceRosterAuthority.issue(
                        AndroidRootAuthority(), null, listOf(identity.certificate.deviceId), emptyList(),
                    )
                ),
                100,
            )
        }
    }

    @Test
    fun step1ExportCard() {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val eido = EidoTransport.of(ctx, identity)
        service(identity).also { ownRoster(it, identity) }
        ex("card-self.json").writeText(
            ContactCard.write(identity, eido.publisher.certificate), Charsets.UTF_8
        )
        ex("roster-self.json").writeText(
            DeviceRosterCanonical.json(
                DeviceRosterAuthority.issue(
                    AndroidRootAuthority(), null, listOf(identity.certificate.deviceId), emptyList(),
                )
            ),
            Charsets.UTF_8,
        )
        println("PROD step1 user=${identity.user.userId}")
    }

    @Test
    fun step2SendToPeer() {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val eido = EidoTransport.of(ctx, identity)
        val service = service(identity)
        ownRoster(service, identity)

        val card = ContactCard.read(ex("card-peer.json").readText(Charsets.UTF_8))
        val peer = service.importContact(card.identityCanonical, "Пир", 100)
        card.publisher?.let { ContactPublisherStore(ctx).put(peer.userId, it) }
        runCatching { service.importDeviceRoster(ex("roster-peer.json").readText(Charsets.UTF_8), 101) }

        val conversation = ConversationConvention.oneToOne(identity.user.userId, peer.userId)
        runCatching {
            service.importConversation(ConversationCanonical.descriptorJson(conversation), "Пир", 100)
        }

        val document = EidogramDocumentV1(
            listOf(EidogramAction.Add(0, "g000001", "circle.red.l", FixedTransform(420_000, 480_000), 0))
        )
        val message = service.send(
            conversation.conversationId, document, System.currentTimeMillis(), "по-настоящему через сеть",
        )

        val ready = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < ready && eido.transport.dhtNodes() < 20) Thread.sleep(1000)
        println("PROD step2 dhtNodes=${eido.transport.dhtNodes()} firewalled=${eido.transport.isFirewalled()}")

        val outcome = publishConversation(
            ctx, service, eido, identity, conversation.conversationId,
            service.contactBundleCanonical(peer.userId),
        )
        ex("expected-message-id.txt").writeText(message.messageId, Charsets.UTF_8)
        ex("expected-document.sha256").writeText(EidogramCanonical.contentHash(document), Charsets.UTF_8)
        println("PROD step2 outcome=$outcome")
        println("PROD step2 message=${message.messageId}")
        assertEquals("публикация не через релей: $outcome", "отправлено", outcome)

        // Keep seeding while the other device looks for it; the driver kills this process.
        Thread.sleep(240_000)
    }

    @Test
    fun step3ReceiveFromPeer() {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val eido = EidoTransport.of(ctx, identity)
        val service = service(identity)
        ownRoster(service, identity)

        val card = ContactCard.read(ex("card-peer.json").readText(Charsets.UTF_8))
        val peer = service.importContact(card.identityCanonical, "Пир", 100)
        card.publisher?.let { ContactPublisherStore(ctx).put(peer.userId, it) }
        runCatching { service.importDeviceRoster(ex("roster-peer.json").readText(Charsets.UTF_8), 101) }

        val ready = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < ready && eido.transport.dhtNodes() < 20) Thread.sleep(1000)
        println("PROD step3 dhtNodes=${eido.transport.dhtNodes()}")

        var admitted = 0
        val deadline = System.currentTimeMillis() + 240_000
        while (System.currentTimeMillis() < deadline && admitted == 0) {
            admitted = runCatching {
                val r = receiveFrom(
                    ctx, service, eido, identity, peer.userId,
                    service.contactBundleCanonical(peer.userId),
                )
                // The reason is now carried rather than collapsed into a zero; printing it turns a
                // silent four-minute retry loop into a log that says what it is waiting for.
                r.problem?.let { println("PROD step3 not yet: $it") }
                r.admitted
            }.getOrElse { println("PROD step3 attempt failed: ${it.message}"); 0 }
            if (admitted == 0) Thread.sleep(10_000)
        }
        assertTrue("ничего не получено за 4 минуты", admitted > 0)
        println("PROD step3 admitted=$admitted")

        val conversation = ConversationConvention.oneToOne(identity.user.userId, peer.userId)
        // The timeline is a topologically ordered DAG, so the newest message is not first.
        val timeline = service.timeline(conversation.conversationId).filter { !it.outgoing }
        assertTrue("сообщение не попало в ленту", timeline.isNotEmpty())
        val expectedId = ex("expected-message-id.txt").readText(Charsets.UTF_8).trim()
        val delivered = timeline.firstOrNull { it.messageId == expectedId }
        assertTrue(
            "доставленного сообщения нет в ленте (получено ${timeline.size})",
            delivered != null,
        )
        assertEquals(
            ex("expected-document.sha256").readText(Charsets.UTF_8).trim(),
            EidogramCanonical.contentHash(delivered!!.document),
        )
        println("PROD step3 DELIVERED message=${delivered.messageId}")
        println("PROD step3 caption=${delivered.caption}")
    }
}
