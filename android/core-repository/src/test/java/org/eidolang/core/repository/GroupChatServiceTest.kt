package org.eidolang.core.repository

import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.model.*
import org.junit.Assert.*
import org.junit.Test

class GroupChatServiceTest {
    private class World {
        var time = 10_000L
        val nodes = listOf("Alice", "Bob", "Charlie", "Dana").map { Node(it, this) }
        val mail = mutableListOf<Pair<Node, String>>()
        init {
            nodes.drop(1).forEach { peer -> nodes[0].contact(peer); peer.contact(nodes[0]) }
        }
        fun publish(from: Node, fail: Boolean = false) {
            from.groups.pending().forEach { (groupId, pending) ->
                val staged = from.groups.stage(groupId, pending.id)
                if (!fail) {
                    val peer = nodes.first { it.id == pending.peerUserId }
                    mail += peer to from.service.messageCanonical(staged.outerMessageId)
                    from.groups.published(groupId, pending.id)
                }
            }
        }
        fun receive(to: Node): Int {
            var count = 0
            mail.filter { it.first == to }.forEach { entry ->
                val message = MessageParser.parseCanonical(entry.second)
                val sender = nodes.first { it.id == message.body.aad.senderUserId }
                // Unknown members become reachable after the owner's authenticated invitation.
                if (to.service.contactSummaries().none { it.userId == sender.id }) return@forEach
                val pair = to.groups.pairConversation(sender.id)
                to.service.admitIncoming(entry.second, ++time, pair)
                count += to.groups.consume(pair) { error("Unexpected rejection: $it") }
                mail.remove(entry)
            }
            return count
        }
        fun pump() {
            repeat(4) { nodes.forEach(::publish); nodes.forEach(::receive) }
        }
    }

    private class Node(val name: String, val world: World) {
        val identity = JvmPrivateIdentity.generate()
        val id get() = identity.user.userId
        val bundle get() = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(identity.user, identity.certificate))
        val repository = InMemoryMessengerRepository()
        val store = InMemoryGroupConversationStore()
        val service = LocalMessengerService(repository, identity)
        var groups = newService()
        fun newService() = GroupChatService(service, repository, store, { uid ->
            val node = world.nodes.first { it.id == uid }
            if (uid != id) require(service.contactSummaries().any { it.userId == uid })
            GroupMemberV1(uid, node.bundle, node.name)
        }, { ++world.time })
        fun contact(peer: Node) { service.importContact(peer.bundle, peer.name, ++world.time) }
    }

    private val document = EidogramDocumentV1(listOf(
        EidogramAction.Add(0, "g1", "circle.red.m", FixedTransform(400_000, 500_000), 0)))

    @Test fun threeIndependentProfilesCreateReceiveReplyAndKeepAuthorsAndCaptions() {
        val w = World(); val (alice, bob, charlie) = w.nodes
        val group = alice.groups.create("Друзья", listOf(bob.id, charlie.id)).groupId
        w.pump()
        assertEquals(3, bob.groups.group(group).state.members.size)
        assertTrue(charlie.service.contactSummaries().any { it.userId == bob.id })
        val messages = listOf(alice, bob, charlie).map { node ->
            node.groups.send(group, document, "Привет от ${node.name} & <тест>").also { w.pump() }
        }
        listOf(alice, bob, charlie).forEach { node ->
            val timeline = node.groups.timeline(group)
            assertEquals(messages.map { it.messageId }.toSet(), timeline.map { it.messageId }.toSet())
            assertEquals(setOf(alice.id, bob.id, charlie.id), timeline.map { it.senderUserId }.toSet())
            assertEquals(listOf("Привет от Alice & <тест>", "Привет от Bob & <тест>", "Привет от Charlie & <тест>"), timeline.map { it.caption })
            assertTrue(timeline.all { it.document == document && it.controlCanonical == null })
        }
    }

    @Test fun offlineRecipientKeepsIndependentCopyAndRepeatedControlsAreIdempotent() {
        val w = World(); val (alice, bob, charlie) = w.nodes
        val group = alice.groups.create("Офлайн", listOf(bob.id, charlie.id)).groupId
        w.pump()
        alice.groups.send(group, document, "Дождётся всех")
        w.publish(alice)
        val duplicate = w.mail.first { it.first == bob }
        w.receive(bob)
        assertEquals(1, bob.groups.timeline(group).size)
        assertEquals(0, charlie.groups.timeline(group).size)
        assertTrue(w.mail.any { it.first == charlie })
        w.mail += duplicate; w.receive(bob)
        assertEquals(1, bob.groups.timeline(group).size)
        w.receive(charlie)
        assertEquals("Дождётся всех", charlie.groups.timeline(group).single().caption)
    }

    @Test fun networkFailureAndRestartReuseOuterEnvelopeAndDurableQueue() {
        val w = World(); val (alice, bob) = w.nodes
        val group = alice.groups.create("Повтор", listOf(bob.id)).groupId
        w.publish(alice, fail = true)
        val before = alice.groups.pending().single().second
        assertTrue(before.outerMessageId.isNotBlank())
        alice.groups = alice.newService()
        val after = alice.groups.stage(group, before.id)
        assertEquals(before.outerMessageId, after.outerMessageId)
        assertEquals(1, alice.repository.messages(alice.groups.pairConversation(bob.id)).size)
        val bytes = GroupConversationRecordCodec.json(alice.groups.group(group))
        assertEquals(alice.groups.group(group), GroupConversationRecordCodec.parse(bytes))
        w.publish(alice); w.receive(bob)
        assertTrue(bob.groups.group(group).canWrite(bob.id))
    }

    @Test fun removingAndAddingMembersRotatesConversationAndPreservesAccessibleHistory() {
        val w = World(); val (alice, bob, charlie, dana) = w.nodes
        val group = alice.groups.create("До", listOf(bob.id, charlie.id)).groupId
        w.pump()
        val old = alice.groups.send(group, document, "До смены состава"); w.pump()
        val previousConversation = old.body.aad.conversationId
        alice.groups.update(group, "После", listOf(bob.id, dana.id)); w.pump()
        val newMessage = alice.groups.send(group, document, "После смены состава"); w.pump()
        assertNotEquals(previousConversation, newMessage.body.aad.conversationId)
        assertEquals("После", bob.groups.group(group).state.title)
        assertEquals(2, bob.groups.timeline(group).size)
        assertEquals(1, charlie.groups.timeline(group).size)
        assertEquals("После смены состава", dana.groups.timeline(group).single().caption)
        assertFalse(charlie.groups.group(group).canWrite(charlie.id))
        assertTrue(runCatching { charlie.groups.send(group, document, "Нельзя") }.isFailure)
        assertTrue(runCatching { MessageCrypto.open(newMessage, alice.identity.user, alice.identity.certificate,
            charlie.identity, alice.groups.group(group).state.conversation()) }.isFailure)
    }

    @Test fun participantCanLeaveAndOwnerCanCloseWithoutDeletingHistory() {
        val w = World(); val (alice, bob, charlie) = w.nodes
        val group = alice.groups.create("Выход", listOf(bob.id, charlie.id)).groupId; w.pump()
        alice.groups.send(group, document, "Сохранить историю"); w.pump()
        bob.groups.leave(group)
        assertFalse(bob.groups.group(group).canWrite(bob.id))
        w.pump()
        assertTrue(alice.groups.group(group).state.members.none { it.userId == bob.id })
        alice.groups.leave(group); w.pump()
        assertTrue(charlie.groups.group(group).state.closed)
        assertFalse(charlie.groups.group(group).canWrite(charlie.id))
        assertEquals("Сохранить историю", bob.groups.timeline(group).single().caption)
        assertEquals("Сохранить историю", charlie.groups.timeline(group).single().caption)
    }

    @Test fun nonOwnerCannotEditAndForgedStateAndForkAreRejected() {
        val w = World(); val (alice, bob, charlie) = w.nodes
        val group = alice.groups.create("Подпись", listOf(bob.id, charlie.id)).groupId; w.pump()
        val original = alice.groups.group(group).state
        assertTrue(runCatching { bob.groups.update(group, "Взлом", listOf(alice.id)) }.isFailure)
        assertTrue(runCatching { GroupStateCodec.parse(GroupStateCodec.json(original.copy(title = "Взлом"))) }.isFailure)
        alice.groups.update(group, "Правильно", listOf(bob.id, charlie.id)); w.pump()
        val fork = GroupStateCodec.issue(alice.identity, original, "Другая ветка", original.members, ++w.time)
        val control = GroupControlCodec.json(GroupControlV1("update", group,
            listOf(GroupStateCodec.json(original), GroupStateCodec.json(fork))))
        val pair = alice.groups.pairConversation(bob.id)
        val outer = alice.service.send(pair, document, ++w.time, controlCanonical = control)
        bob.service.admitIncoming(MessageCanonical.envelopeJson(outer), ++w.time, bob.groups.pairConversation(alice.id))
        val errors = mutableListOf<String>()
        assertEquals(0, bob.groups.consume(pair, errors::add))
        assertTrue(errors.any { it.contains("Конфликт") })
        assertEquals("Правильно", bob.groups.group(group).state.title)
    }

    @Test fun groupDeliveryRequiresEveryRecipientAndNotJustTheFirstPickup() {
        val w = World(); val (alice, bob, charlie) = w.nodes
        val group = alice.groups.create("Галочки", listOf(bob.id, charlie.id)).groupId; w.pump()
        val message = alice.groups.send(group, document, "Обоим")
        w.publish(alice)
        assertFalse(alice.groups.delivered(group, message.messageId, mapOf(bob.id to Long.MAX_VALUE)))
        assertTrue(alice.groups.delivered(group, message.messageId,
            mapOf(bob.id to Long.MAX_VALUE, charlie.id to Long.MAX_VALUE)))
    }

    @Test fun committedInnerMessageWithoutItsOutboxIsRecoveredAfterACrash() {
        val w = World(); val (alice, bob, charlie) = w.nodes
        val group = alice.groups.create("После сбоя", listOf(bob.id, charlie.id)).groupId; w.pump()
        val state = alice.groups.group(group).state
        val message = alice.service.send(state.conversation().conversationId, document, ++w.time, "Между двумя записями")
        assertTrue(alice.groups.pending().isEmpty())
        alice.groups = alice.newService()
        alice.groups.recoverPendingMessages()
        assertEquals(2, alice.groups.pending().size)
        alice.groups.recoverPendingMessages()
        assertEquals(2, alice.groups.pending().size)
        w.pump()
        assertEquals(message.messageId, bob.groups.timeline(group).single().messageId)
        assertEquals(message.messageId, charlie.groups.timeline(group).single().messageId)
    }

    @Test fun reInvitationAfterOfflineRemovalRestoresWritingButAnOldUpdateCannotRollBack() {
        val w = World(); val (alice, bob, charlie) = w.nodes
        val group = alice.groups.create("Возвращение", listOf(bob.id, charlie.id)).groupId; w.pump()
        bob.groups.leave(group)
        w.publish(bob); w.receive(alice)
        // Bob has not received the removal; the next full chain contains removal and re-invitation.
        alice.groups.update(group, "Снова вместе", listOf(bob.id, charlie.id))
        w.publish(alice)
        val updates = w.mail.filter { it.first == bob }
        w.mail.removeAll(updates.toSet())
        w.mail += updates.reversed()
        w.receive(bob)
        assertTrue(bob.groups.group(group).canWrite(bob.id))
        assertEquals("Снова вместе", bob.groups.group(group).state.title)
        assertEquals(2, bob.groups.group(group).state.revision)
    }

    @Test fun accountSnapshotFenceKeepsTheMessageAndFanOutOnTheSameSideOfTheSnapshot() {
        val w = World(); val (alice, bob) = w.nodes
        val group = alice.groups.create("Согласованная копия", listOf(bob.id)).groupId; w.pump()
        val started = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        var error: Throwable? = null
        val writer = Thread {
            started.countDown()
            try { alice.groups.send(group, document, "После снимка") } catch (t: Throwable) { error = t }
            finally { done.countDown() }
        }
        synchronized(GroupPersistence.lock) {
            writer.start()
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + 2_000_000_000
            while (writer.state != Thread.State.BLOCKED && done.count > 0 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, writer.state)
            assertTrue(alice.groups.timeline(group).isEmpty())
            assertTrue(alice.groups.pending().isEmpty())
        }
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertNull(error)
        assertEquals(1, alice.groups.timeline(group).size)
        assertEquals(1, alice.groups.pending().size)
    }

    @Test fun revokedOwnerDeviceCannotSmuggleANewRevisionThroughAnActiveMember() {
        val w = World(); val (alice, bob, charlie, dana) = w.nodes
        val group = alice.groups.create("Известный состав", listOf(bob.id, charlie.id)).groupId; w.pump()
        val genesis = alice.groups.group(group).state
        val replacementSigning = JcaCrypto.p256()
        val replacementEncryption = JcaCrypto.rsa2048()
        val replacementCertificate = IdentityFactory.certificate(IdentityFactory.deviceBody(alice.identity.user,
            replacementSigning.public, replacementEncryption.public), alice.identity.rootSigning.private)
        val replacement = JvmPrivateIdentity(alice.identity.rootSigning, replacementSigning,
            replacementEncryption, alice.identity.user, replacementCertificate)
        val replacementRaw = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(replacement.user, replacement.certificate))
        val replacementService = LocalMessengerService(alice.repository, replacement)
        val authority = object : RootAuthority {
            override val user = alice.identity.user
            override fun signRoot(bytes: ByteArray) = JcaCrypto.sign(alice.identity.rootSigning.private, bytes)
        }
        val roster = DeviceRosterAuthority.issue(authority, null, listOf(replacement.certificate.deviceId),
            listOf(alice.identity.certificate.deviceId))
        replacementService.importDeviceRosterSnapshot(DeviceRosterCanonical.json(roster), ++w.time)
        charlie.service.importContact(replacementRaw, "Alice", ++w.time)
        charlie.service.importDeviceRosterSnapshot(DeviceRosterCanonical.json(roster), ++w.time)
        assertFalse(charlie.service.isDeviceActive(alice.id, alice.identity.certificate.deviceId))

        // Revoking the issuer cannot invalidate a revision this recipient already authenticated.
        val historical = bob.groups.send(group, document, "Известная историческая ревизия")
        w.publish(bob); w.receive(charlie)
        assertEquals(historical.messageId, charlie.groups.timeline(group).single().messageId)

        // The outer sender is active Bob, while its new owner proof uses the revoked Alice key.
        val forged = GroupStateCodec.issue(alice.identity, genesis, "Поддельный состав",
            genesis.members + GroupMemberV1(dana.id, dana.bundle, dana.name), ++w.time)
        val inner = MessageCrypto.seal(document, "Попытка добавить постороннего", forged.conversation(), bob.identity,
            forged.members.map { MessageCrypto.recipient(IdentityParser.parsePublicBundleCanonical(it.identityCanonical).device) },
            0, ++w.time)
        val control = GroupControlCodec.json(GroupControlV1("message", group,
            listOf(GroupStateCodec.json(genesis), GroupStateCodec.json(forged)), MessageCanonical.envelopeJson(inner)))
        val pair = bob.groups.pairConversation(charlie.id)
        val outer = bob.service.send(pair, document, ++w.time, controlCanonical = control)
        charlie.service.admitIncoming(MessageCanonical.envelopeJson(outer), ++w.time,
            charlie.groups.pairConversation(bob.id))
        val rejected = mutableListOf<String>()
        assertEquals(0, charlie.groups.consume(pair, rejected::add))
        assertTrue(rejected.any { it.contains("отозван") })
        assertEquals(genesis.stateId, charlie.groups.group(group).state.stateId)
        assertTrue(charlie.repository.messages(forged.conversation().conversationId).isEmpty())
        assertTrue(charlie.service.contactSummaries().none { it.userId == dana.id })

        // An active replacement owner can advance the group while retaining its old signed prefix.
        val replacementGroups = GroupChatService(replacementService, alice.repository, alice.store, { uid ->
            if (uid == alice.id) GroupMemberV1(uid, replacementRaw, alice.name)
            else w.nodes.first { it.id == uid }.let { GroupMemberV1(uid, it.bundle, it.name) }
        }, { ++w.time })
        replacementGroups.update(group, "Действующий создатель", listOf(bob.id, charlie.id))
        val ownerPair = charlie.groups.pairConversation(alice.id)
        val activeControl = GroupControlCodec.json(GroupControlV1("update", group, replacementGroups.group(group).chain))
        val activeOuter = replacementService.send(replacementGroups.pairConversation(charlie.id), document,
            ++w.time, controlCanonical = activeControl)
        charlie.service.admitIncoming(MessageCanonical.envelopeJson(activeOuter), ++w.time, ownerPair)
        val ownerErrors = mutableListOf<String>()
        assertEquals(1, charlie.groups.consume(ownerPair, ownerErrors::add))
        assertTrue(ownerErrors.isEmpty())
        assertEquals("Действующий создатель", charlie.groups.group(group).state.title)
        assertEquals(historical.messageId, charlie.groups.timeline(group).single().messageId)
    }
}
