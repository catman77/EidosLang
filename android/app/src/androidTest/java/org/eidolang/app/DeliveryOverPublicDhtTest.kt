package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.archive.ConversationHeadCodec
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.crypto.B64Url
import org.eidolang.core.crypto.IdentityCanonical
import org.eidolang.core.crypto.JvmPrivateIdentity
import org.eidolang.core.crypto.PublicIdentityBundleV1
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.message.ConversationCanonical
import org.eidolang.core.model.EidogramAction
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.model.FixedTransform
import org.eidolang.core.multidevice.AndroidRootAuthority
import org.eidolang.core.multidevice.DeviceRosterAuthority
import org.eidolang.core.multidevice.DeviceRosterCanonical
import org.eidolang.core.multidevice.JvmRootAuthority
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.LocalMessengerService
import org.eidolang.feature.home.ConversationConvention
import org.eidolang.feature.home.EidoTransport
import org.eidolang.feature.home.publishConversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The send half of delivery, against the real BitTorrent DHT.
 *
 * `publishConversation` is exactly what the send button runs. This drives it end to end and then
 * resolves the head back out of the global DHT — a genuine round trip through public
 * infrastructure, not a loopback swarm. What it does not prove is that a *remote* peer can then
 * reach this device to pull the data: that depends on NAT, and is measured separately.
 */
@NativeSessionHeavy
class DeliveryOverPublicDhtTest {

    @Test
    fun sendPublishesAHeadTheWorldCanResolve() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val random = SecureRandom()
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val eido = EidoTransport.of(ctx, identity)

        val repo = AndroidSqliteMessengerRepository(ctx, AndroidRepositoryTextProtector(ctx))
        val service = LocalMessengerService(repo, identity, random)

        // Own epoch-0 roster, or send() refuses to treat this device as active.
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

        val peer = JvmPrivateIdentity.generate(random)
        val peerBundle = IdentityCanonical.publicBundleJson(
            PublicIdentityBundleV1(peer.user, peer.certificate)
        )
        service.importContact(peerBundle, "DHT probe", 100)
        runCatching {
            service.importDeviceRoster(
                DeviceRosterCanonical.json(
                    DeviceRosterAuthority.issue(
                        JvmRootAuthority.fromPrimary(peer, random), null,
                        listOf(peer.certificate.deviceId), emptyList(),
                    )
                ),
                101,
            )
        }

        val conversation = ConversationConvention.oneToOne(identity.user.userId, peer.user.userId)
        runCatching {
            service.importConversation(
                ConversationCanonical.descriptorJson(conversation), "DHT probe", 100,
            )
        }
        service.send(
            conversation.conversationId,
            EidogramDocumentV1(
                listOf(EidogramAction.Add(0, "g000001", "circle.red.m", FixedTransform(500_000, 500_000), 0))
            ),
            System.currentTimeMillis(),
            "проверка доставки",
        )

        // Give the session a routing table before asking it to store anything.
        val ready = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < ready && eido.transport.dhtNodes() < 10) Thread.sleep(1000)
        println("DELIVERY dht nodes before publish=${eido.transport.dhtNodes()}")

        val outcome = publishConversation(
            ctx, service, eido, identity, conversation.conversationId, peerBundle,
        )
        println("DELIVERY publish outcome=$outcome")
        assertTrue("публикация не удалась: $outcome", outcome.startsWith("отправлено"))

        // Resolve our own head back out of the public DHT, the way a recipient would.
        val salt = ByteArray(conversation.conversationId.length / 2) { i ->
            conversation.conversationId.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val pk = B64Url.decode(eido.publisher.certificate.dhtPublicKeyRawB64)
        val target = MessageDigest.getInstance("SHA-1").digest(pk + salt)
            .joinToString("") { "%02x".format(it) }
        eido.transport.knowHead(pk, salt)

        val resolved = eido.transport.getHead(target)
        assertNotNull("head was published but could not be resolved from the DHT", resolved)
        val head = ConversationHeadCodec.parseMutablePutFields(resolved!!.fieldsBencoded)
        val value = ConversationHeadCodec.decodeValue(head.value)
        assertEquals(conversation.conversationId, value.conversationId)
        assertTrue(value.torrentInfoHashV2.matches(Regex("[0-9a-f]{64}")))
        println("DELIVERY resolved target=$target infohash=${value.torrentInfoHashV2}")
        println("DELIVERY round trip through the public DHT OK")

        repo.close()
    }
}
