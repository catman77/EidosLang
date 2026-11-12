package org.eidolang.core.repository

import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.EidogramDocumentV1
import org.junit.Assert.*
import org.junit.Test

class MessengerFrontierTest {
    @Test fun moreThanSixteenConcurrentGroupAuthorsCanBeMergedWithoutLosingMessages() {
        val owner = JvmPrivateIdentity.generate()
        val peers = List(17) { JvmPrivateIdentity.generate() }
        val repo = InMemoryMessengerRepository()
        val service = LocalMessengerService(repo, owner)
        peers.forEach { peer -> service.importContact(IdentityCanonical.publicBundleJson(
            PublicIdentityBundleV1(peer.user, peer.certificate)), "Автор", 1) }
        val conversation = service.createConversation(peers.map { it.user.userId }, createdAtMs = 1)
        val recipients = (peers + owner).map { MessageCrypto.recipient(it.certificate) }
        peers.forEachIndexed { i, peer ->
            val message = MessageCrypto.seal(EidogramDocumentV1(emptyList()), "Параллельное $i", conversation,
                peer, recipients, 0, 100L + i)
            service.admitIncoming(MessageCanonical.envelopeJson(message), 200L + i)
        }
        assertEquals(17, service.currentHeads(conversation.conversationId).size)
        val merge = service.send(conversation.conversationId, EidogramDocumentV1(emptyList()), 300, "Общий ответ")
        assertTrue(merge.body.aad.parentMessageIds.size <= 16)
        assertEquals(18, service.timeline(conversation.conversationId).size)
        val last = service.send(conversation.conversationId, EidogramDocumentV1(emptyList()), 301, "Следующий ответ")
        assertTrue(merge.messageId in last.body.aad.parentMessageIds)
        assertEquals(19, service.timeline(conversation.conversationId).size)
    }
}
