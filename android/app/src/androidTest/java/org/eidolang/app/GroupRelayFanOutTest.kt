package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.archive.*
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.repository.*
import org.eidolang.core.transport.*
import org.eidolang.feature.home.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

@TwoDeviceOnly
class GroupRelayFanOutTest {
    private fun bundle(id: DevicePrivateCrypto) = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(id.user, id.certificate))

    @Test fun firstRecipientAndCleanupCannotEraseTheOfflineRecipientsCopy() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        check(android.os.Build.MODEL.startsWith("sdk_gphone"))
        check(InstrumentationRegistry.getArguments().getString("groupFixture") == "fresh-emulator")
        EidoRelaySettings.apply(ctx)
        val reachable = Relay.reachable()
        if (!reachable) println("GROUP_RELAY_FANOUT_NOT_RUN: configured relay unavailable")
        assumeTrue("Configured relay unavailable", reachable)
        ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val identity = AndroidAccountRecovery.currentIdentity(ctx)!!
        val service = LocalMessengerService(AndroidMessengerRepositoryProvider.get(ctx), identity)
        val peers = listOf(JvmPrivateIdentity.generate(), JvmPrivateIdentity.generate())
        peers.forEachIndexed { i, peer ->
            service.importContact(bundle(peer), "Релейный участник ${i + 1}", System.currentTimeMillis())
            ContactPublisherStore(ctx).put(peer.user.userId, JcaArchivePublisher.create(peer).certificate)
        }
        val groups = groupChatService(ctx, service)
        val group = groups.create("Проверка независимой доставки", peers.map { it.user.userId })
        val message = groups.send(group.groupId, EidogramDocumentV1(listOf(
            EidogramAction.Add(0, "g1", "circle.green.m", FixedTransform(500_000, 500_000), 0))), "Дождаться второго участника")
        var publication = ""
        repeat(3) {
            if (groups.pending().any { it.first == group.groupId }) {
                publication = flushGroupOutbox(ctx, service, groups, group.groupId)
                println("GROUP_RELAY_RETRY $it pending=${groups.pending().count { it.first == group.groupId }}")
            }
        }
        assertTrue(publication, groups.pending().none { it.first == group.groupId })
        val eido = EidoTransport.of(ctx, identity)

        fun receive(peer: DevicePrivateCrypto): String {
            val conversation = ConversationConvention.oneToOne(identity.user.userId, peer.user.userId)
            val salt = conversation.conversationId.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val pk = B64Url.decode(eido.publisher.certificate.dhtPublicKeyRawB64)
            val target = java.security.MessageDigest.getInstance("SHA-1").digest(pk + salt).joinToString("") { "%02x".format(it) }
            val headBytes = Relay.getHead(target) ?: error("Peer head unavailable")
            val head = ConversationHeadCodec.parseMutablePutFields(headBytes)
            val value = ConversationHeadCodec.decodeValue(head.value)
            val fetched = RelayDelivery.fetch(value.torrentInfoHashV2, OnionDelivery.torrentName(value.conversationId, value.segmentId))
                ?: error("Offline recipient's independent copy is missing")
            val accepted = NetworkAdmissionGate.admit(fetched, MutableHeadRecord(target, headBytes),
                ArchivePublisherCanonical.certificateJson(eido.publisher.certificate), bundle(identity))
            val repo = InMemoryMessengerRepository()
            val receiver = LocalMessengerService(repo, peer)
            receiver.importContact(bundle(identity), "Создатель", 1)
            receiver.importConversation(ConversationCanonical.descriptorJson(conversation), "Создатель", 1)
            accepted.artifact.manifest.messageEntries.forEach { entry ->
                val raw = fetched.swarm.files.first { it.path == entry.path }.bytes.toString(Charsets.UTF_8)
                receiver.admitIncoming(raw, System.currentTimeMillis(), conversation.conversationId)
            }
            val received = GroupChatService(receiver, repo, InMemoryGroupConversationStore(), { error("Not needed for receiving") })
            received.consume(conversation.conversationId) { error(it) }
            assertEquals(message.messageId, received.timeline(group.groupId).single().messageId)
            assertEquals("Дождаться второго участника", received.timeline(group.groupId).single().caption)
            return value.torrentInfoHashV2
        }
        val first = receive(peers[0])
        dropCollectedFromLocker(ctx, eido)
        val second = receive(peers[1])
        assertNotEquals("Оба участника ошибочно разделяют одну удаляемую копию", first, second)
        println("GROUP_RELAY_FANOUT_PASS: real HTTPS locker, signed heads and torrent admission, decrypt by two recipients, cleanup between pickups")
    }
}
