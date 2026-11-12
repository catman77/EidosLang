package org.eidolang.core.repository

import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.EidogramDocumentV1
import java.security.MessageDigest

/** Group state and durable fan-out. Network publication is performed after [stage]. */
class GroupChatService(
    private val messenger: LocalMessengerService,
    private val repository: MessengerRepository,
    private val store: GroupConversationStore,
    private val memberOf: (String) -> GroupMemberV1,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val self get() = messenger.localIdentity.user.userId
    fun groups(): List<GroupConversationRecord> = store.all()
    fun group(id: String): GroupConversationRecord = store.get(id) ?: error("Группа не найдена")

    fun create(title: String, remoteUserIds: Collection<String>): GroupConversationRecord = synchronized(GroupPersistence.lock) {
        require(remoteUserIds.isNotEmpty()) { "Выберите участников" }
        val members = (remoteUserIds + self).distinct().map(memberOf)
        val state = GroupStateCodec.issue(messenger.localIdentity, null, title, members, clock())
        val record = GroupConversationRecord(listOf(GroupStateCodec.json(state)))
        importIdentitiesAndConversations(record.chain)
        enqueue(record, GroupControlV1("update", state.groupId, record.chain), members.map { it.userId })
    }

    fun update(id: String, title: String, memberUserIds: Collection<String>): GroupConversationRecord = synchronized(GroupPersistence.lock) {
        val old = group(id)
        require(old.state.ownerUserId == self && old.canWrite(self)) { "Изменять группу может только её создатель" }
        importIdentitiesAndConversations(old.chain)
        val members = (memberUserIds + self).distinct().map(memberOf)
        require(members.size >= 2) { "В группе должен остаться хотя бы один собеседник" }
        val next = GroupStateCodec.issue(messenger.localIdentity, old.state, title, members, clock())
        val record = old.copy(chain = old.chain + GroupStateCodec.json(next))
        importIdentitiesAndConversations(record.chain)
        enqueue(record, GroupControlV1("update", id, record.chain), allMembers(record))
    }

    fun leave(id: String): GroupConversationRecord = synchronized(GroupPersistence.lock) {
        val old = group(id)
        require(old.canWrite(self)) { "Вы уже вышли из этой группы" }
        if (old.state.ownerUserId == self) {
            val next = GroupStateCodec.issue(messenger.localIdentity, old.state, old.state.title, old.state.members, clock(), closed = true)
            val record = old.copy(chain = old.chain + GroupStateCodec.json(next), leftAtRevision = next.revision)
            return@synchronized enqueue(record, GroupControlV1("update", id, record.chain), allMembers(record))
        }
        enqueue(old.copy(leftAtRevision = old.state.revision),
            GroupControlV1("leave", id, seenStateId = old.state.stateId), listOf(old.state.ownerUserId))
    }

    fun send(id: String, document: EidogramDocumentV1, caption: String): EidogramMessageV1 = synchronized(GroupPersistence.lock) {
        val record = group(id)
        require(record.canWrite(self)) { "Вы больше не можете писать в эту группу" }
        importIdentitiesAndConversations(record.chain)
        val state = record.state
        val message = messenger.send(state.conversation().conversationId, document, clock(), caption)
        enqueue(record, GroupControlV1("message", id, record.chain, MessageCanonical.envelopeJson(message)),
            state.members.map { it.userId })
        message
    }

    fun timeline(id: String): List<TimelineItem> = GroupStateCodec.verifyChain(group(id).chain)
        .filter { !it.closed && it.members.any { m -> m.userId == self } }
        .flatMap { messenger.timeline(it.conversation().conversationId) }
        .distinctBy { it.messageId }

    /** Use exactly the existing deterministic pair channel, with an independent locker per peer. */
    fun pairConversation(peerUserId: String): String {
        val seed = MessageDigest.getInstance("SHA-256")
            .digest(("eido-1to1|" + listOf(self, peerUserId).sorted().joinToString("|")).toByteArray()).copyOf(16)
        val descriptor = ConversationFactory.fromSeed(seed, listOf(self, peerUserId))
        messenger.importConversation(ConversationCanonical.descriptorJson(descriptor), null, clock())
        return descriptor.conversationId
    }

    fun pending(): List<Pair<String, GroupOutboxItem>> = groups().flatMap { record -> record.outbox.map { record.groupId to it } }

    /** Recover the existing outer envelope if a crash occurred before recording its ID. */
    fun stage(groupId: String, outboxId: String): GroupOutboxItem = synchronized(GroupPersistence.lock) {
        val record = group(groupId)
        val pending = record.outbox.first { it.id == outboxId }
        if (pending.outerMessageId.isNotEmpty()) return@synchronized pending
        val conversationId = pairConversation(pending.peerUserId)
        val existing = messenger.timeline(conversationId).firstOrNull {
            it.outgoing && it.controlCanonical == pending.controlCanonical
        }
        val at = existing?.createdAtMs ?: clock()
        val messageId = existing?.messageId ?: messenger.send(conversationId, EidogramDocumentV1(emptyList()), at,
            "Служебное сообщение группы «${record.state.title}»", pending.controlCanonical).messageId
        val staged = pending.copy(outerMessageId = messageId, outerCreatedAtMs = at)
        val control = GroupControlCodec.parse(pending.controlCanonical)
        val deliveries = if (control.kind == "message") {
            val id = MessageParser.parseCanonical(control.envelopeCanonical).messageId
            record.outgoingDeliveries + (id to (record.outgoingDeliveries[id].orEmpty() + (pending.peerUserId to at)))
        } else record.outgoingDeliveries
        store.put(record.copy(outbox = record.outbox.map { if (it.id == outboxId) staged else it }, outgoingDeliveries = deliveries))
        staged
    }

    fun published(groupId: String, outboxId: String) = synchronized(GroupPersistence.lock) {
        val record = group(groupId)
        store.put(record.copy(outbox = record.outbox.filterNot { it.id == outboxId }))
    }

    fun delivered(groupId: String, messageId: String, deliveredThrough: Map<String, Long>): Boolean {
        val record = group(groupId)
        val stamps = record.outgoingDeliveries[messageId] ?: return false
        val message = MessageParser.parseCanonical(messenger.messageCanonical(messageId))
        val recipients = message.body.recipientBoxes.map { it.userId }.toSet() - self
        return recipients.isNotEmpty() && recipients.all { peer ->
            val at = stamps[peer]
            at != null && (deliveredThrough[peer] ?: 0) >= at
        }
    }

    /** Only authenticated, already admitted pair messages enter this method. */
    fun consume(pairConversationId: String, onRejected: (String) -> Unit = {}): Int = synchronized(GroupPersistence.lock) {
        val descriptor = messenger.conversationDescriptor(pairConversationId)
        require(descriptor.participantUserIds.size == 2 && self in descriptor.participantUserIds)
        var accepted = 0
        messenger.timeline(pairConversationId).filter { !it.outgoing && it.controlCanonical != null }.forEach { item ->
            try {
            val control = GroupControlCodec.parse(item.controlCanonical!!)
            val old = store.get(control.groupId)
            if (item.messageId in old?.handledControlIds.orEmpty()) return@forEach
            when (control.kind) {
                "leave" -> acceptLeave(control, item.senderUserId)
                else -> acceptUpdateOrMessage(control, item.senderUserId)
            }
            val applied = group(control.groupId)
            store.put(applied.copy(handledControlIds = applied.handledControlIds + item.messageId))
            accepted++
            } catch (error: Exception) {
                onRejected(error.message ?: "Не удалось проверить обновление группы")
            }
        }
        accepted
    }

    private fun acceptUpdateOrMessage(control: GroupControlV1, senderUserId: String) {
        val states = GroupStateCodec.verifyChain(control.chain)
        val last = states.last()
        require(states.any { s -> s.members.any { it.userId == self } }) { "Вы не приглашены в эту группу" }
        val old = store.get(control.groupId)
        if (old != null) {
            val common = minOf(old.chain.size, control.chain.size)
            val previous = GroupStateCodec.verifyChain(old.chain)
            require((0 until common).all { previous[it].stateId == states[it].stateId }) { "Конфликт ревизий группы" }
        }
        // Cached history remains authenticated after revocation, but a forwarded new revision
        // must not grant authority to an owner device already known to be revoked.
        states.drop(old?.chain?.size ?: 0).forEach { state ->
            val issuer = IdentityParser.parsePublicBundleCanonical(state.issuerCanonical)
            require(messenger.isDeviceActive(issuer.user.userId, issuer.device.deviceId)) {
                "Устройство создателя группы отозвано"
            }
        }
        if (control.kind == "update") require(senderUserId == last.ownerUserId) { "Изменять состав может только создатель группы" }
        else {
            require(!last.closed && last.members.any { it.userId == self } && last.members.any { it.userId == senderUserId })
            val message = MessageParser.parseCanonical(control.envelopeCanonical)
            require(message.body.aad.senderUserId == senderUserId && message.body.aad.conversationId == last.conversation().conversationId) {
                "Сообщение не принадлежит отправителю или группе"
            }
            val member = last.members.first { it.userId == senderUserId }.identityCanonical.let(IdentityParser::parsePublicBundleCanonical)
            val known = repository.contactDevices().firstOrNull {
                it.userId == senderUserId && it.deviceId == message.body.aad.senderDeviceId
            }?.bundleCanonicalJson?.let(IdentityParser::parsePublicBundleCanonical)
            val bundle = known ?: member
            require(bundle.user == member.user)
            require(MessageCrypto.verify(message, bundle.user, bundle.device, last.conversation())) { "Неверная подпись группового сообщения" }
        }
        importIdentitiesAndConversations(control.chain)
        val newer = old == null || control.chain.size > old.chain.size
        val record = if (old == null) GroupConversationRecord(control.chain)
        else if (newer) {
            val absentSinceLeaving = old.leftAtRevision?.let { left -> states.drop(left + 1).dropLast(1)
                .any { s -> s.members.none { it.userId == self } } } ?: false
            val rejoined = last.members.any { it.userId == self } &&
                (old.state.members.none { it.userId == self } || absentSinceLeaving)
            old.copy(chain = control.chain, leftAtRevision = if (rejoined) null else old.leftAtRevision)
        } else old
        if (control.kind == "message") messenger.admitIncoming(control.envelopeCanonical, clock(), last.conversation().conversationId)
        store.put(record)
    }

    private fun acceptLeave(control: GroupControlV1, sender: String) {
        val record = group(control.groupId)
        require(record.state.ownerUserId == self && sender != self)
        val seen = GroupStateCodec.verifyChain(record.chain).firstOrNull { it.stateId == control.seenStateId }
            ?: error("Неизвестная ревизия запроса на выход")
        require(seen.members.any { it.userId == sender })
        if (record.state.closed || record.state.members.none { it.userId == sender }) return
        val remaining = record.state.members.filterNot { it.userId == sender }
        val next = GroupStateCodec.issue(messenger.localIdentity, record.state, record.state.title,
            remaining, clock(), closed = remaining.size < 2)
        val updated = record.copy(chain = record.chain + GroupStateCodec.json(next))
        importIdentitiesAndConversations(updated.chain)
        enqueue(updated, GroupControlV1("update", control.groupId, updated.chain), allMembers(updated))
    }

    private fun importIdentitiesAndConversations(chain: List<String>) {
        val states = GroupStateCodec.verifyChain(chain)
        val aliases = messenger.contactSummaries().associate { it.userId to it.alias }
        states.flatMap { it.members }.distinctBy { it.identityCanonical }.forEach { m ->
            if (m.userId == self) messenger.importOwnDevice(m.identityCanonical, clock())
            else messenger.importContact(m.identityCanonical, aliases[m.userId] ?: m.displayName, clock())
        }
        states.filter { !it.closed && it.members.any { m -> m.userId == self } }.forEach { s ->
            messenger.importConversation(ConversationCanonical.descriptorJson(s.conversation()), s.title, s.createdAtMs)
        }
    }

    private fun allMembers(record: GroupConversationRecord) = GroupStateCodec.verifyChain(record.chain)
        .flatMap { it.members }.map { it.userId }.distinct()

    /** Repairs the gap between committing an immutable inner message and committing its fan-out. */
    fun recoverPendingMessages() = synchronized(GroupPersistence.lock) {
        groups().forEach { initial ->
            importIdentitiesAndConversations(initial.chain)
            GroupStateCodec.verifyChain(initial.chain).forEachIndexed { index, state ->
                if (state.closed || state.members.none { it.userId == self }) return@forEachIndexed
                repository.messages(state.conversation().conversationId).filter { it.senderUserId == self }.forEach { message ->
                    val current = group(initial.groupId)
                    val missing = state.members.map { it.userId }.filter {
                        it != self && it !in current.outgoingDeliveries[message.messageId].orEmpty()
                    }
                    if (missing.isNotEmpty()) enqueue(current, GroupControlV1("message", state.groupId,
                        initial.chain.take(index + 1), message.envelopeCanonicalJson), missing)
                }
            }
        }
    }

    private fun enqueue(record: GroupConversationRecord, control: GroupControlV1, recipients: Collection<String>): GroupConversationRecord {
        val raw = GroupControlCodec.json(control)
        GroupControlCodec.parse(raw)
        val added = recipients.distinct().filter { it != self }.map { peer ->
            GroupOutboxItem(HexSha256.ofUtf8("$peer|$raw"), peer, raw)
        }
        val result = record.copy(outbox = (record.outbox + added).distinctBy { it.id })
        store.put(result)
        return result
    }
}
