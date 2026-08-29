package org.eidolang.core.repository

import org.eidolang.core.canonical.EidoMessagePayloadV1
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.model.EidogramDocumentV1
import java.security.SecureRandom
import java.util.PriorityQueue

data class ContactSummary(
    val userId: String,
    val alias: String,
    val deviceCount: Int,
)

data class ConversationSummary(
    val conversationId: String,
    val title: String,
    val participantUserIds: List<String>,
    val messageCount: Int,
    val headMessageIds: List<String>,
    val latestCreatedAtMs: Long?,
)

data class TimelineItem(
    val messageId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val senderSeq: Int,
    val createdAtMs: Long,
    val parentMessageIds: List<String>,
    val outgoing: Boolean,
    val localState: MessageLocalState,
    val document: EidogramDocumentV1,
    /** Author's words for the eidogram; empty for anything written before R22.2. */
    val caption: String = "",
)

class LocalMessengerService(
    private val repository: MessengerRepository,
    val localIdentity: DevicePrivateCrypto,
    private val random: SecureRandom = SecureRandom(),
    private val historicalDocumentProvider: HistoricalDocumentProvider? = null,
) {
    init {
        val raw = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(localIdentity.user, localIdentity.certificate))
        repository.putOwnDevice(
            OwnDeviceRecord(localIdentity.user.userId, localIdentity.certificate.deviceId, raw, 0)
        )
    }

    fun importOwnDevice(bundleCanonicalJson: String, importedAtMs: Long): PublicIdentityBundleV1 {
        val bundle = IdentityParser.parsePublicBundleCanonical(bundleCanonicalJson)
        require(bundle.user.userId == localIdentity.user.userId) { "Device belongs to another user identity" }
        repository.putOwnDevice(
            OwnDeviceRecord(bundle.user.userId, bundle.device.deviceId, bundleCanonicalJson, importedAtMs)
        )
        return bundle
    }

    fun importDeviceRoster(rosterCanonicalJson: String, importedAtMs: Long): UserDeviceRosterV1 {
        val roster = DeviceRosterCanonical.parseCanonical(rosterCanonicalJson)
        val user = knownUser(roster.body.userId)
        val previous = latestRoster(roster.body.userId)
        val knownIds = knownBundles(roster.body.userId).mapTo(linkedSetOf()) { it.device.deviceId }
        DeviceRosterVerifier.advance(previous, roster, user, knownIds)
        repository.putDeviceRoster(
            DeviceRosterRecord(roster.body.userId, roster.body.epoch, roster.rosterId, rosterCanonicalJson, importedAtMs)
        )
        return roster
    }

    /**
     * Bootstrap a fresh device from the latest root-signed roster snapshot. A device with
     * existing roster state must still follow the exact epoch/hash chain.
     */
    fun importDeviceRosterSnapshot(rosterCanonicalJson: String, importedAtMs: Long): UserDeviceRosterV1 {
        val roster = DeviceRosterCanonical.parseCanonical(rosterCanonicalJson)
        val user = knownUser(roster.body.userId)
        val previous = latestRoster(roster.body.userId)
        val knownIds = knownBundles(roster.body.userId).mapTo(linkedSetOf()) { it.device.deviceId }
        if (previous == null) {
            require(DeviceRosterVerifier.verifySignature(roster, user)) { "Invalid root-signed device roster" }
            require((roster.body.activeDeviceIds + roster.body.revokedDeviceIds).all { it in knownIds }) {
                "Roster references unknown device certificate"
            }
        } else {
            DeviceRosterVerifier.advance(previous, roster, user, knownIds)
        }
        repository.putDeviceRoster(
            DeviceRosterRecord(roster.body.userId, roster.body.epoch, roster.rosterId, rosterCanonicalJson, importedAtMs)
        )
        return roster
    }

    fun latestRoster(userId: String): UserDeviceRosterV1? =
        repository.deviceRosters().firstOrNull { it.userId == userId }
            ?.let { DeviceRosterCanonical.parseCanonical(it.rosterCanonicalJson) }

    fun ownDeviceBundlesCanonical(): List<String> =
        repository.ownDevices()
            .filter { it.userId == localIdentity.user.userId }
            .sortedBy { it.deviceId }
            .map { it.bundleCanonicalJson }

    fun latestDeviceRostersCanonical(): List<String> =
        repository.deviceRosters().sortedBy { it.userId }.map { it.rosterCanonicalJson }

    fun allKeyGrantsCanonical(): List<String> =
        repository.keyGrants()
            .sortedWith(compareBy<MessageKeyGrantRecord> { it.messageId }.thenBy { it.targetEncryptionKeyId })
            .map { it.grantCanonicalJson }

    fun isDeviceActive(userId: String, deviceId: String): Boolean =
        latestRoster(userId)?.body?.activeDeviceIds?.contains(deviceId) ?: true


    fun admitHistoricalWithGrant(
        envelopeCanonicalJson: String,
        grantCanonicalJson: String,
        storedAtMs: Long,
    ): InsertMessageResult {
        val message = MessageParser.parseCanonical(envelopeCanonicalJson)
        val conversation = conversationDescriptor(message.body.aad.conversationId)
        val duplicate = repository.messages(message.body.aad.conversationId).firstOrNull { it.messageId == message.messageId }
        if (duplicate != null) {
            val old = MessageParser.parseCanonical(duplicate.envelopeCanonicalJson)
            require(MessageCanonical.bodyJson(old.body) == MessageCanonical.bodyJson(message.body))
            return InsertMessageResult.Duplicate
        }
        val sender = senderBundle(message.body.aad.senderUserId, message.body.aad.senderDeviceId)
        require(MessageCrypto.verify(message, sender.user, sender.device, conversation))

        val grant = MessageKeyGrantCanonical.parseCanonical(grantCanonicalJson)
        val grantor = senderOrOwnBundle(grant.body.ownerUserId, grant.body.grantorDeviceId)
        val target = PublicIdentityBundleV1(localIdentity.user, localIdentity.certificate)
        require(isDeviceActive(grantor.user.userId, grantor.device.deviceId)) { "Grantor device is revoked" }
        require(isDeviceActive(target.user.userId, target.device.deviceId)) { "Target device is revoked" }
        MessageKeyGrantCrypto.open(message, sender, conversation, grant, grantor, localIdentity)

        validateGraphInsertion(message)
        repository.putKeyGrant(
            MessageKeyGrantRecord(message.messageId, target.device.body.encryptionKeyId, grantCanonicalJson, storedAtMs)
        )
        return storeVerified(
            message,
            if (message.body.aad.senderUserId == localIdentity.user.userId) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            MessageLocalState.ACCEPTED,
            storedAtMs,
        )
    }

    fun importKeyGrant(grantCanonicalJson: String, storedAtMs: Long): MessageKeyGrantV1 {
        val grant = MessageKeyGrantCanonical.parseCanonical(grantCanonicalJson)
        require(grant.body.ownerUserId == localIdentity.user.userId)
        require(grant.body.targetDeviceId == localIdentity.certificate.deviceId)
        require(grant.body.targetEncryptionKeyId == localIdentity.certificate.body.encryptionKeyId)
        val messageRaw = messageCanonical(grant.body.messageId)
        val message = MessageParser.parseCanonical(messageRaw)
        val grantor = senderOrOwnBundle(grant.body.ownerUserId, grant.body.grantorDeviceId)
        val target = PublicIdentityBundleV1(localIdentity.user, localIdentity.certificate)
        require(isDeviceActive(grantor.user.userId, grantor.device.deviceId)) { "Grantor device is revoked" }
        require(isDeviceActive(target.user.userId, target.device.deviceId)) { "Target device is revoked" }
        require(MessageKeyGrantCrypto.verify(grant, message, grantor, target)) { "Invalid historical key grant" }
        repository.putKeyGrant(
            MessageKeyGrantRecord(grant.body.messageId, grant.body.targetEncryptionKeyId, grantCanonicalJson, storedAtMs)
        )
        return grant
    }

    fun createHistoricalKeyGrants(targetBundleCanonicalJson: String, storedAtMs: Long): List<String> {
        val target = IdentityParser.parsePublicBundleCanonical(targetBundleCanonicalJson)
        require(target.user.userId == localIdentity.user.userId) { "Historical key grants only migrate own devices" }
        require(isDeviceActive(target.user.userId, target.device.deviceId)) { "Target device is not active" }
        return repository.conversations().flatMap { c -> repository.messages(c.conversationId) }
            .sortedBy { it.messageId }
            .mapNotNull { row ->
                val message = MessageParser.parseCanonical(row.envelopeCanonicalJson)
                if (message.body.recipientBoxes.any { it.encryptionKeyId == target.device.body.encryptionKeyId }) {
                    null
                } else {
                    runCatching {
                        val grant = MessageKeyGrantCrypto.issue(message, localIdentity, target)
                        val raw = MessageKeyGrantCanonical.json(grant)
                        repository.putKeyGrant(
                            MessageKeyGrantRecord(message.messageId, target.device.body.encryptionKeyId, raw, storedAtMs)
                        )
                        raw
                    }.getOrNull()
                }
            }
    }
    fun exportLocalIdentityCanonical(): String =
        IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(localIdentity.user, localIdentity.certificate))

    fun importContact(bundleCanonicalJson: String, alias: String? = null, importedAtMs: Long): ContactSummary {
        require(importedAtMs >= 0)
        val bundle = IdentityParser.parsePublicBundleCanonical(bundleCanonicalJson)
        require(bundle.user.userId != localIdentity.user.userId) { "Local identity is not a contact" }
        val effectiveAlias = alias?.trim()?.takeIf { it.isNotEmpty() } ?: bundle.user.userId.take(12)
        repository.putContact(
            ContactDeviceRecord(
                bundle.user.userId,
                bundle.device.deviceId,
                effectiveAlias,
                bundleCanonicalJson,
                importedAtMs,
            )
        )
        return contactSummaries().first { it.userId == bundle.user.userId }
    }

    /** Canonical public bundle of a contact device, as stored at import. */
    /**
     * Forget a contact. Their messages stay: they are signed and immutable, and the DAG that
     * recovery walks does not get to lose entries because an address-book row was removed.
     */
    fun deleteContact(userId: String) = repository.deleteContact(userId)

    fun contactBundleCanonical(userId: String): String =
        repository.contactDevices().firstOrNull { it.userId == userId }?.bundleCanonicalJson
            ?: error("Unknown contact $userId")

    fun contactSummaries(): List<ContactSummary> =
        repository.contactDevices()
            .groupBy { it.userId }
            .values
            .map { rows ->
                ContactSummary(
                    rows.first().userId,
                    rows.minBy { it.importedAtMs }.alias,
                    rows.count { isDeviceActive(it.userId, it.deviceId) }
                )
            }
            .sortedWith(compareBy<ContactSummary> { it.alias }.thenBy { it.userId })

    fun createConversation(
        remoteUserIds: Collection<String>,
        seed: ByteArray = JcaCrypto.randomBytes(16, random),
        title: String? = null,
        createdAtMs: Long,
    ): ConversationDescriptorV1 {
        require(createdAtMs >= 0)
        val remote = remoteUserIds.toSortedSet()
        require(remote.isNotEmpty()) { "At least one remote participant is required" }
        val contacts = repository.contactDevices().groupBy { it.userId }
        remote.forEach { require(it in contacts) { "Unknown contact user $it" } }
        val descriptor = ConversationFactory.fromSeed(seed, remote + localIdentity.user.userId)
        val effectiveTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: remote.map { uid -> contacts.getValue(uid).first().alias }.joinToString(", ")
        repository.putConversation(
            ConversationRecord(
                descriptor.conversationId,
                ConversationCanonical.descriptorJson(descriptor),
                effectiveTitle,
                createdAtMs,
            )
        )
        return descriptor
    }

    fun importConversation(descriptorCanonicalJson: String, title: String? = null, createdAtMs: Long): ConversationDescriptorV1 {
        val descriptor = ConversationParser.parseCanonical(descriptorCanonicalJson)
        require(localIdentity.user.userId in descriptor.participantUserIds) { "Local user is not a participant" }
        val contacts = repository.contactDevices().groupBy { it.userId }
        descriptor.participantUserIds.filter { it != localIdentity.user.userId }.forEach {
            require(it in contacts) { "Missing contact identity for participant $it" }
        }
        val effectiveTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: descriptor.participantUserIds.filter { it != localIdentity.user.userId }
                .joinToString(", ") { contacts.getValue(it).first().alias }
        repository.putConversation(
            ConversationRecord(descriptor.conversationId, descriptorCanonicalJson, effectiveTitle, createdAtMs)
        )
        return descriptor
    }

    fun conversationDescriptor(conversationId: String): ConversationDescriptorV1 =
        repository.conversations().firstOrNull { it.conversationId == conversationId }
            ?.let { ConversationParser.parseCanonical(it.descriptorCanonicalJson) }
            ?: error("Unknown conversation $conversationId")

    fun conversationDescriptorCanonical(conversationId: String): String =
        repository.conversations().firstOrNull { it.conversationId == conversationId }?.descriptorCanonicalJson
            ?: error("Unknown conversation $conversationId")

    fun send(
        conversationId: String,
        document: EidogramDocumentV1,
        createdAtMs: Long,
        caption: String = "",
    ): EidogramMessageV1 {
        val conversation = conversationDescriptor(conversationId)
        require(isDeviceActive(localIdentity.user.userId, localIdentity.certificate.deviceId)) { "Current device is revoked" }
        val recipients = recipientDevices(conversation)
        val seq = nextSenderSeq(conversationId, localIdentity.certificate.deviceId)
        val parents = currentHeads(conversationId)
        val message = MessageCrypto.seal(
            document = document,
            caption = caption,
            conversation = conversation,
            sender = localIdentity,
            recipients = recipients,
            senderSeq = seq,
            createdAtMs = createdAtMs,
            parentMessageIds = parents,
            random = random,
        )
        // Admission of our own envelope through the same cryptographic boundary.
        val opened = MessageCrypto.open(message, localIdentity.user, localIdentity.certificate, localIdentity, conversation)
        require(EidogramCanonical.contentHash(opened) == EidogramCanonical.contentHash(document))
        storeVerified(message, MessageDirection.OUTGOING, MessageLocalState.READY_FOR_ARCHIVE, createdAtMs)
        return message
    }

    fun admitIncoming(
        envelopeCanonicalJson: String,
        storedAtMs: Long,
        expectedConversationId: String? = null,
    ): InsertMessageResult {
        val message = MessageParser.parseCanonical(envelopeCanonicalJson)
        if (expectedConversationId != null) {
            require(message.body.aad.conversationId == expectedConversationId) { "Message belongs to another conversation" }
        }
        val conversation = conversationDescriptor(message.body.aad.conversationId)
        require(message.body.aad.senderUserId in conversation.participantUserIds)

        // A replay of an already admitted body stays idempotent even if the sender device was
        // revoked later. Revocation is prospective for new repository objects.
        val duplicate = repository.messages(message.body.aad.conversationId).firstOrNull { it.messageId == message.messageId }
        if (duplicate != null) {
            val old = MessageParser.parseCanonical(duplicate.envelopeCanonicalJson)
            require(MessageCanonical.bodyJson(old.body) == MessageCanonical.bodyJson(message.body)) {
                "Same message_id with different message body"
            }
            return InsertMessageResult.Duplicate
        }

        val senderBundle = senderBundle(message.body.aad.senderUserId, message.body.aad.senderDeviceId)
        require(isDeviceActive(senderBundle.user.userId, senderBundle.device.deviceId)) { "Sender device is revoked" }
        require(MessageCrypto.verify(message, senderBundle.user, senderBundle.device, conversation)) { "Incoming signature/authentication failure" }

        openForLocal(message, senderBundle, conversation)

        validateGraphInsertion(message)
        return storeVerified(message, MessageDirection.INCOMING, MessageLocalState.ACCEPTED, storedAtMs)
    }

    fun messageCanonical(messageId: String): String =
        repository.conversations().asSequence()
            .flatMap { repository.messages(it.conversationId).asSequence() }
            .firstOrNull { it.messageId == messageId }?.envelopeCanonicalJson
            ?: error("Unknown message $messageId")

    fun latestOutgoingCanonical(conversationId: String): String? =
        repository.messages(conversationId)
            .filter { it.direction == MessageDirection.OUTGOING }
            .maxWithOrNull(compareBy<StoredMessageRecord> { it.createdAtMs }.thenBy { it.senderSeq }.thenBy { it.messageId })
            ?.envelopeCanonicalJson

    fun currentHeads(conversationId: String): List<String> {
        val records = repository.messages(conversationId)
        val ids = records.mapTo(linkedSetOf()) { it.messageId }
        val consumed = records.flatMap { it.parentMessageIds }.filterTo(linkedSetOf()) { it in ids }
        return (ids - consumed).sorted()
    }

    fun timeline(conversationId: String): List<TimelineItem> {
        val conversation = conversationDescriptor(conversationId)
        val records = topological(repository.messages(conversationId))
        return records.map { r ->
            val message = MessageParser.parseCanonical(r.envelopeCanonicalJson)
            val sender = senderBundle(message.body.aad.senderUserId, message.body.aad.senderDeviceId)
            val payload = openPayloadForLocal(message, sender, conversation)
            TimelineItem(
                r.messageId, r.senderUserId, r.senderDeviceId, r.senderSeq,
                r.createdAtMs, r.parentMessageIds,
                outgoing = r.senderUserId == localIdentity.user.userId,
                localState = r.localState,
                document = payload.document,
                caption = payload.caption,
            )
        }
    }

    fun conversationSummaries(): List<ConversationSummary> =
        repository.conversations().map { r ->
            val d = ConversationParser.parseCanonical(r.descriptorCanonicalJson)
            val msgs = repository.messages(r.conversationId)
            ConversationSummary(
                r.conversationId,
                r.title,
                d.participantUserIds,
                msgs.size,
                currentHeads(r.conversationId),
                msgs.maxOfOrNull { it.createdAtMs },
            )
        }.sortedWith(compareByDescending<ConversationSummary> { it.latestCreatedAtMs ?: Long.MIN_VALUE }
            .thenByDescending { it.conversationId })

    private fun recipientDevices(conversation: ConversationDescriptorV1): List<RecipientPublicDeviceV1> {
        val remoteUsers = conversation.participantUserIds.filter { it != localIdentity.user.userId }.toSet()
        val remote = repository.contactDevices()
            .filter { it.userId in remoteUsers && isDeviceActive(it.userId, it.deviceId) }
            .map { IdentityParser.parsePublicBundleCanonical(it.bundleCanonicalJson) }

        val ownSiblings = repository.ownDevices()
            .filter {
                it.userId == localIdentity.user.userId &&
                    it.deviceId != localIdentity.certificate.deviceId &&
                    isDeviceActive(it.userId, it.deviceId)
            }
            .map { IdentityParser.parsePublicBundleCanonical(it.bundleCanonicalJson) }

        return (remote + ownSiblings)
            .distinctBy { it.device.body.encryptionKeyId }
            .map { MessageCrypto.recipient(it.device) }
    }

    private fun senderBundle(userId: String, deviceId: String): PublicIdentityBundleV1 =
        senderOrOwnBundle(userId, deviceId)

    private fun senderOrOwnBundle(userId: String, deviceId: String): PublicIdentityBundleV1 {
        if (userId == localIdentity.user.userId && deviceId == localIdentity.certificate.deviceId) {
            return PublicIdentityBundleV1(localIdentity.user, localIdentity.certificate)
        }
        if (userId == localIdentity.user.userId) {
            return repository.ownDevices()
                .firstOrNull { it.userId == userId && it.deviceId == deviceId }
                ?.let { IdentityParser.parsePublicBundleCanonical(it.bundleCanonicalJson) }
                ?: error("Unknown own device $userId/$deviceId")
        }
        return repository.contactDevices()
            .firstOrNull { it.userId == userId && it.deviceId == deviceId }
            ?.let { IdentityParser.parsePublicBundleCanonical(it.bundleCanonicalJson) }
            ?: error("Unknown sender device $userId/$deviceId")
    }

    private fun knownBundles(userId: String): List<PublicIdentityBundleV1> =
        if (userId == localIdentity.user.userId) {
            repository.ownDevices().filter { it.userId == userId }
                .map { IdentityParser.parsePublicBundleCanonical(it.bundleCanonicalJson) }
        } else {
            repository.contactDevices().filter { it.userId == userId }
                .map { IdentityParser.parsePublicBundleCanonical(it.bundleCanonicalJson) }
        }

    private fun knownUser(userId: String): UserIdentityV1 =
        if (userId == localIdentity.user.userId) localIdentity.user
        else knownBundles(userId).firstOrNull()?.user ?: error("Unknown user identity $userId")

    private fun openForLocal(
        message: EidogramMessageV1,
        sender: PublicIdentityBundleV1,
        conversation: ConversationDescriptorV1,
    ): EidogramDocumentV1 = openPayloadForLocal(message, sender, conversation).document

    private fun openPayloadForLocal(
        message: EidogramMessageV1,
        sender: PublicIdentityBundleV1,
        conversation: ConversationDescriptorV1,
    ): EidoMessagePayloadV1 {
        val keyId = localIdentity.certificate.body.encryptionKeyId
        if (message.body.recipientBoxes.any { it.encryptionKeyId == keyId }) {
            return MessageCrypto.openPayload(message, sender.user, sender.device, localIdentity, conversation)
        }
        val grantRow = repository.keyGrants(message.messageId)
            .firstOrNull { it.targetEncryptionKeyId == keyId }
        if (grantRow != null) {
            val grant = MessageKeyGrantCanonical.parseCanonical(grantRow.grantCanonicalJson)
            val grantor = senderOrOwnBundle(grant.body.ownerUserId, grant.body.grantorDeviceId)
            return MessageKeyGrantCrypto.openPayload(message, sender, conversation, grant, grantor, localIdentity)
        }

        // R20.2 recovery records are authenticated under an independent history-vault domain.
        // They are a fallback only when the immutable R15 envelope has no usable local key box
        // or R20 historical grant. Message identity/signature is still verified separately.
        // Vault recovery hands back documents only, so a recovered message shows no caption.
        historicalDocumentProvider?.documentFor(message.messageId)?.let { return EidoMessagePayloadV1(it, "") }
        error("No local recipient key box, historical key grant, or authenticated history-vault record")
    }

    private fun nextSenderSeq(conversationId: String, deviceId: String): Int =
        (repository.messages(conversationId)
            .filter { it.senderDeviceId == deviceId }
            .maxOfOrNull { it.senderSeq } ?: -1) + 1

    private fun storeVerified(
        message: EidogramMessageV1,
        direction: MessageDirection,
        state: MessageLocalState,
        storedAtMs: Long,
    ): InsertMessageResult {
        val canonical = MessageCanonical.envelopeJson(message)
        return repository.insertMessage(
            StoredMessageRecord(
                message.messageId,
                message.body.aad.conversationId,
                message.body.aad.senderUserId,
                message.body.aad.senderDeviceId,
                message.body.aad.senderSeq,
                message.body.aad.createdAtMs,
                message.body.aad.parentMessageIds,
                canonical,
                direction,
                state,
                storedAtMs,
            )
        )
    }

    private fun validateGraphInsertion(message: EidogramMessageV1) {
        require(message.messageId !in message.body.aad.parentMessageIds) { "Self-parent message" }
        val existing = repository.messages(message.body.aad.conversationId)
        val candidate = existing + StoredMessageRecord(
            message.messageId,
            message.body.aad.conversationId,
            message.body.aad.senderUserId,
            message.body.aad.senderDeviceId,
            message.body.aad.senderSeq,
            message.body.aad.createdAtMs,
            message.body.aad.parentMessageIds,
            MessageCanonical.envelopeJson(message),
            MessageDirection.INCOMING,
            MessageLocalState.ACCEPTED,
            0,
        )
        // This also detects a cycle that becomes visible only when a formerly missing parent arrives.
        topological(candidate)
    }

    private fun topological(records: List<StoredMessageRecord>): List<StoredMessageRecord> {
        if (records.isEmpty()) return emptyList()
        val byId = records.associateBy { it.messageId }
        require(byId.size == records.size)
        val children = byId.keys.associateWith { mutableListOf<String>() }
        val indegree = byId.keys.associateWith { 0 }.toMutableMap()
        records.forEach { child ->
            child.parentMessageIds.filter { it in byId }.forEach { parent ->
                children.getValue(parent).add(child.messageId)
                indegree[child.messageId] = indegree.getValue(child.messageId) + 1
            }
        }
        val cmp = compareBy<String>(
            { byId.getValue(it).createdAtMs },
            { byId.getValue(it).senderUserId },
            { byId.getValue(it).senderSeq },
            { it },
        )
        val ready = PriorityQueue(cmp)
        indegree.filterValues { it == 0 }.keys.forEach(ready::add)
        val out = mutableListOf<StoredMessageRecord>()
        while (ready.isNotEmpty()) {
            val id = ready.remove()
            out += byId.getValue(id)
            children.getValue(id).sorted().forEach { child ->
                val n = indegree.getValue(child) - 1
                indegree[child] = n
                if (n == 0) ready.add(child)
            }
        }
        require(out.size == records.size) { "Message DAG contains a cycle" }
        return out
    }
}
