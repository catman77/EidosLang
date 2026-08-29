package org.eidolang.core.recovery

import org.eidolang.core.archive.*
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.repository.*
import java.security.SecureRandom
import java.util.PriorityQueue

data class ArchiveSegmentSummary(
    val segmentId: String,
    val conversationId: String,
    val torrentInfoHashV2: String,
    val messageCount: Int,
    val previousSegmentIds: List<String>,
    val headMessageIds: List<String>,
    val pinned: Boolean,
    val storedAtMs: Long,
    val byteLength: Long,
)

sealed interface ArchiveConversationResult {
    data class Created(val summary: ArchiveSegmentSummary) : ArchiveConversationResult
    data object NoChanges : ArchiveConversationResult
}

data class RestoreReport(
    val segmentCount: Int,
    val contactDeviceCount: Int,
    val conversationCount: Int,
    val uniqueMessageCount: Int,
    val duplicateMessageCopies: Int,
)

class ArchiveCoordinator(
    private val repository: MessengerRepository,
    private val archiveStore: ArchiveStore,
    private val localIdentity: DevicePrivateCrypto,
    private val random: SecureRandom = SecureRandom(),
) {
    fun archiveConversation(conversationId: String, storedAtMs: Long): ArchiveConversationResult {
        require(storedAtMs >= 0)
        val conversationRecord = repository.conversations().firstOrNull { it.conversationId == conversationId }
            ?: error("Unknown conversation $conversationId")
        val conversation = ConversationParser.parseCanonical(conversationRecord.descriptorCanonicalJson)
        val messages = repository.messages(conversationId)
        if (messages.isEmpty()) return ArchiveConversationResult.NoChanges

        val existingSegments = archiveStore.list(conversationId)
        val existingArtifacts = existingSegments.map { ArchiveSegmentCodec.toArtifact(it.blob) }
        val covered = existingArtifacts.flatMapTo(linkedSetOf()) { it.manifest.messageEntries.map { e -> e.messageId } }
        val fresh = messages.filter { it.messageId !in covered }
        if (fresh.isEmpty()) return ArchiveConversationResult.NoChanges

        val freshIds = fresh.mapTo(linkedSetOf()) { it.messageId }
        val unresolvedOrArchivedParents = fresh.flatMap { it.parentMessageIds }.filterTo(linkedSetOf()) { it !in freshIds }
        val previousSegmentHeads = segmentHeads(existingArtifacts)
        val identities = participantIdentityBundles(conversation)

        val artifact = ConversationSegmentBuilder.build(
            conversation = conversation,
            identityBundles = identities,
            messageCanonicalJson = fresh.map { it.envelopeCanonicalJson },
            previousSegmentIds = previousSegmentHeads,
            knownExternalMessageIds = covered + unresolvedOrArchivedParents,
        )
        val blob = ArchiveSegmentCodec.fromArtifact(artifact)
        archiveStore.put(blob, pinned = false, storedAtMs = storedAtMs)
        return ArchiveConversationResult.Created(summary(archiveStore.get(blob.segmentId)!!))
    }

    fun archiveAll(storedAtMs: Long): List<ArchiveConversationResult.Created> =
        repository.conversations().mapNotNull { r ->
            when (val x = archiveConversation(r.conversationId, storedAtMs)) {
                is ArchiveConversationResult.Created -> x
                ArchiveConversationResult.NoChanges -> null
            }
        }

    fun summaries(conversationId: String? = null): List<ArchiveSegmentSummary> =
        archiveStore.list(conversationId).map(::summary)

    fun setPinned(segmentId: String, pinned: Boolean) = archiveStore.setPinned(segmentId, pinned)

    fun exportPackageCanonical(): String {
        repository.conversations().forEach { c ->
            val messageIds = repository.messages(c.conversationId).mapTo(linkedSetOf()) { it.messageId }
            val archivedIds = archiveStore.list(c.conversationId)
                .map { ArchiveSegmentCodec.toArtifact(it.blob) }
                .flatMapTo(linkedSetOf()) { a -> a.manifest.messageEntries.map { it.messageId } }
            require(messageIds.all { it in archivedIds }) {
                "Conversation ${c.conversationId} has unarchived messages; archive before export"
            }
        }
        val contacts = repository.contactDevices().groupBy { it.userId }.values.map { rows ->
            val chosen = rows.minWith(compareBy<ContactDeviceRecord> { it.importedAtMs }.thenBy { it.deviceId })
            val bundles = rows.sortedBy { it.deviceId }.map { it.bundleCanonicalJson }
            ContactPresentationV1(chosen.userId, chosen.alias, bundles)
        }
        val conversations = repository.conversations().map {
            ConversationPresentationV1(it.conversationId, it.title, it.createdAtMs, it.descriptorCanonicalJson)
        }
        val segments = archiveStore.list().map { it.blob }
        val pinned = archiveStore.list().filter { it.pinned }.map { it.blob.segmentId }
        val p = ArchivePackageCodec.withId(
            ownerUserId = localIdentity.user.userId,
            recoveryDeviceId = localIdentity.certificate.deviceId,
            recoveryEncryptionKeyId = localIdentity.certificate.body.encryptionKeyId,
            contacts = contacts,
            conversations = conversations,
            segments = segments,
            pinnedSegmentIds = pinned,
        )
        return ArchivePackageCodec.json(p)
    }

    /**
     * Full local recovery package. Private keys are never present in the package: the current
     * device must still possess the same R15 decryption key. All segment bytes are verified
     * before either archive storage or messenger repository is mutated.
     */
    fun importAndRestorePackage(canonical: String, storedAtMs: Long): RestoreReport {
        val p = ArchivePackageCodec.parseCanonical(canonical)
        require(p.ownerUserId == localIdentity.user.userId) { "Archive belongs to another user" }
        require(p.recoveryDeviceId == localIdentity.certificate.deviceId) { "Archive requires another device identity" }
        require(p.recoveryEncryptionKeyId == localIdentity.certificate.body.encryptionKeyId) { "Archive requires unavailable historical decryption key" }

        // Verify all immutable blobs before any write.
        p.segments.forEach(ArchiveSegmentCodec::toArtifact)
        preflightArchiveStore(p)

        val presentation = RecoveryPresentation(
            aliases = p.contacts.associate { it.userId to it.alias },
            contactBundles = p.contacts.flatMap { c -> c.deviceBundleCanonicalJson.map { c.userId to it } },
            conversations = p.conversations.associateBy { it.conversationId },
        )
        preflightRepository(p.segments, presentation, storedAtMs)

        p.segments.forEach { blob ->
            archiveStore.put(blob, blob.segmentId in p.pinnedSegmentIds, storedAtMs)
        }
        p.pinnedSegmentIds.forEach { archiveStore.setPinned(it, true) }
        return applyRestore(repository, p.segments, presentation, storedAtMs)
    }

    /**
     * Reconstruct cryptographic conversation state from verified R16 segment blobs only.
     * Human aliases/titles fall back to deterministic identifiers because they are not R16 data.
     */
    fun restoreFromStoredSegments(storedAtMs: Long): RestoreReport {
        val blobs = archiveStore.list().map { it.blob }
        preflightRepository(blobs, RecoveryPresentation.EMPTY, storedAtMs)
        return applyRestore(repository, blobs, RecoveryPresentation.EMPTY, storedAtMs)
    }

    private fun preflightArchiveStore(p: ArchivePackageV1) {
        p.segments.forEach { incoming ->
            val old = archiveStore.get(incoming.segmentId)
            if (old != null) {
                require(ArchiveSegmentCodec.json(old.blob) == ArchiveSegmentCodec.json(incoming)) {
                    "Archive segment identity collision"
                }
            }
        }
    }

    private fun preflightRepository(blobs: List<ArchiveSegmentBlobV1>, presentation: RecoveryPresentation, storedAtMs: Long) {
        val mirror = InMemoryMessengerRepository()
        repository.contactDevices().forEach(mirror::putContact)
        repository.ownDevices().forEach(mirror::putOwnDevice)
        repository.deviceRosters().forEach(mirror::putDeviceRoster)
        repository.keyGrants().forEach(mirror::putKeyGrant)
        repository.conversations().forEach(mirror::putConversation)
        repository.conversations().forEach { c -> repository.messages(c.conversationId).forEach { mirror.insertMessage(it) } }
        applyRestore(mirror, blobs, presentation, storedAtMs)
    }

    private fun applyRestore(
        target: MessengerRepository,
        blobs: List<ArchiveSegmentBlobV1>,
        presentation: RecoveryPresentation,
        storedAtMs: Long,
    ): RestoreReport {
        val artifacts = blobs.sortedBy { it.segmentId }.map(ArchiveSegmentCodec::toArtifact)
        if (artifacts.isEmpty()) return RestoreReport(0, 0, 0, 0, 0)

        val conversations = linkedMapOf<String, String>()
        val identities = linkedMapOf<Pair<String, String>, String>()
        val messageCopies = linkedMapOf<String, MutableList<String>>()

        presentation.conversations.toSortedMap().values.forEach { meta ->
            val d = ConversationParser.parseCanonical(meta.descriptorCanonicalJson)
            require(d.conversationId == meta.conversationId)
            conversations[d.conversationId] = meta.descriptorCanonicalJson
        }
        presentation.contactBundles.sortedWith(compareBy<Pair<String,String>> { it.first }.thenBy { it.second }).forEach { (declaredUser, raw) ->
            val bundle = IdentityParser.parsePublicBundleCanonical(raw)
            require(bundle.user.userId == declaredUser)
            val key = bundle.user.userId to bundle.device.deviceId
            val old = identities[key]
            if (old == null) identities[key] = raw else {
                val oldBundle = IdentityParser.parsePublicBundleCanonical(old)
                require(oldBundle.user == bundle.user && oldBundle.device.body == bundle.device.body && oldBundle.device.deviceId == bundle.device.deviceId)
                identities[key] = minOf(old, raw)
            }
        }

        artifacts.forEach { a ->
            val fileMap = a.files.associateBy { it.path }
            val convRaw = fileMap.getValue("conversation.json").bytes.toString(Charsets.UTF_8)
            val conv = ConversationParser.parseCanonical(convRaw)
            val oldConv = conversations.putIfAbsent(conv.conversationId, convRaw)
            require(oldConv == null || oldConv == convRaw) { "Conflicting conversation descriptors in archive" }

            a.manifest.identityEntries.forEach { e ->
                val raw = fileMap.getValue(e.path).bytes.toString(Charsets.UTF_8)
                val bundle = IdentityParser.parsePublicBundleCanonical(raw)
                val key = bundle.user.userId to bundle.device.deviceId
                val previous = identities[key]
                if (previous == null) identities[key] = raw else {
                    // ECDSA certificate proof bytes may differ while user/device identity is stable.
                    val old = IdentityParser.parsePublicBundleCanonical(previous)
                    require(old.user == bundle.user && old.device.body == bundle.device.body && old.device.deviceId == bundle.device.deviceId)
                    identities[key] = minOf(previous, raw)
                }
            }

            a.manifest.messageEntries.forEach { e ->
                val raw = fileMap.getValue(e.path).bytes.toString(Charsets.UTF_8)
                messageCopies.getOrPut(e.messageId) { mutableListOf() }.add(raw)
            }
        }

        // Contacts first, because conversation and message verification depend on public identities.
        val existingContacts = target.contactDevices().associateBy { it.userId to it.deviceId }
        identities.toSortedMap(compareBy<Pair<String,String>> { it.first }.thenBy { it.second }).forEach { (key, raw) ->
            val bundle = IdentityParser.parsePublicBundleCanonical(raw)
            if (bundle.user.userId == localIdentity.user.userId) {
                target.putOwnDevice(
                    OwnDeviceRecord(
                        bundle.user.userId,
                        bundle.device.deviceId,
                        raw,
                        existingContacts[key]?.importedAtMs ?: storedAtMs,
                    )
                )
                return@forEach
            }
            val existing = existingContacts[key]
            target.putContact(
                ContactDeviceRecord(
                    bundle.user.userId,
                    bundle.device.deviceId,
                    presentation.aliases[bundle.user.userId] ?: existing?.alias ?: bundle.user.userId.take(12),
                    raw,
                    existing?.importedAtMs ?: storedAtMs,
                )
            )
        }

        val existingConversations = target.conversations().associateBy { it.conversationId }
        conversations.toSortedMap().forEach { (id, raw) ->
            val d = ConversationParser.parseCanonical(raw)
            require(localIdentity.user.userId in d.participantUserIds)
            val existing = existingConversations[id]
            val meta = presentation.conversations[id]
            val fallback = d.participantUserIds.filter { it != localIdentity.user.userId }.joinToString(", ") { uid ->
                target.contactDevices().firstOrNull { it.userId == uid }?.alias ?: uid.take(12)
            }
            target.putConversation(
                ConversationRecord(id, raw, meta?.title ?: existing?.title ?: fallback, meta?.createdAtMs ?: existing?.createdAtMs ?: storedAtMs)
            )
        }

        // Verify every distinct proof and canonical body before choosing a deterministic proof copy.
        val chosenMessages = linkedMapOf<String, String>()
        var duplicates = 0
        messageCopies.toSortedMap().forEach { (id, copies) ->
            val parsed = copies.map { raw -> raw to MessageParser.parseCanonical(raw) }
            parsed.forEach { (_, m) ->
                require(m.messageId == id)
                val conversation = ConversationParser.parseCanonical(conversations[m.body.aad.conversationId]
                    ?: target.conversations().first { it.conversationId == m.body.aad.conversationId }.descriptorCanonicalJson)
                val sender = if (m.body.aad.senderUserId == localIdentity.user.userId && m.body.aad.senderDeviceId == localIdentity.certificate.deviceId) {
                    PublicIdentityBundleV1(localIdentity.user, localIdentity.certificate)
                } else {
                    val senderRaw = identities[m.body.aad.senderUserId to m.body.aad.senderDeviceId]
                        ?: target.ownDevices().firstOrNull { it.userId == m.body.aad.senderUserId && it.deviceId == m.body.aad.senderDeviceId }?.bundleCanonicalJson
                        ?: target.contactDevices().firstOrNull { it.userId == m.body.aad.senderUserId && it.deviceId == m.body.aad.senderDeviceId }?.bundleCanonicalJson
                        ?: error("Missing archived sender identity")
                    IdentityParser.parsePublicBundleCanonical(senderRaw)
                }
                require(MessageCrypto.verify(m, sender.user, sender.device, conversation))
                MessageCrypto.open(m, sender.user, sender.device, localIdentity, conversation)
            }
            val bodies = parsed.map { MessageCanonical.bodyJson(it.second.body) }.distinct()
            require(bodies.size == 1) { "Same message_id used for different bodies" }
            duplicates += copies.size - 1
            chosenMessages[id] = parsed.minOf { it.first }
        }

        val ordered = topological(chosenMessages.values.map(MessageParser::parseCanonical))
        ordered.forEach { m ->
            val raw = chosenMessages.getValue(m.messageId)
            val outgoing = m.body.aad.senderUserId == localIdentity.user.userId
            target.insertMessage(
                StoredMessageRecord(
                    m.messageId,
                    m.body.aad.conversationId,
                    m.body.aad.senderUserId,
                    m.body.aad.senderDeviceId,
                    m.body.aad.senderSeq,
                    m.body.aad.createdAtMs,
                    m.body.aad.parentMessageIds,
                    raw,
                    if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                    MessageLocalState.ARCHIVED,
                    storedAtMs,
                )
            )
        }

        return RestoreReport(
            artifacts.size,
            identities.keys.count { it.first != localIdentity.user.userId },
            conversations.size,
            chosenMessages.size,
            duplicates,
        )
    }

    private fun participantIdentityBundles(conversation: ConversationDescriptorV1): List<PublicIdentityBundleV1> {
        val out = mutableListOf(PublicIdentityBundleV1(localIdentity.user, localIdentity.certificate))
        repository.ownDevices().filter { it.userId == localIdentity.user.userId }.forEach {
            out += IdentityParser.parsePublicBundleCanonical(it.bundleCanonicalJson)
        }
        repository.contactDevices().filter { it.userId in conversation.participantUserIds }.forEach {
            out += IdentityParser.parsePublicBundleCanonical(it.bundleCanonicalJson)
        }
        return out.distinctBy { it.device.deviceId }
    }

    private fun segmentHeads(artifacts: List<ConversationSegmentArtifactV1>): List<String> {
        val ids = artifacts.mapTo(linkedSetOf()) { it.manifest.segmentId }
        val consumed = artifacts.flatMap { it.manifest.previousSegmentIds }.filterTo(linkedSetOf()) { it in ids }
        return (ids - consumed).sorted()
    }

    private fun summary(row: StoredArchiveSegment): ArchiveSegmentSummary {
        val artifact = ArchiveSegmentCodec.toArtifact(row.blob)
        return ArchiveSegmentSummary(
            artifact.manifest.segmentId,
            artifact.manifest.conversationId,
            artifact.torrent.infoHashV2Hex,
            artifact.manifest.messageEntries.size,
            artifact.manifest.previousSegmentIds,
            artifact.manifest.headMessageIds,
            row.pinned,
            row.storedAtMs,
            artifact.files.sumOf { it.bytes.size.toLong() } + artifact.torrent.metainfoBytes.size,
        )
    }

    private fun topological(messages: Collection<EidogramMessageV1>): List<EidogramMessageV1> {
        val byId = messages.associateBy { it.messageId }
        require(byId.size == messages.size)
        val children = byId.keys.associateWith { mutableListOf<String>() }
        val indegree = byId.keys.associateWith { 0 }.toMutableMap()
        messages.forEach { child ->
            child.body.aad.parentMessageIds.filter { it in byId }.forEach { parent ->
                children.getValue(parent).add(child.messageId)
                indegree[child.messageId] = indegree.getValue(child.messageId) + 1
            }
        }
        val cmp = compareBy<String>(
            { byId.getValue(it).body.aad.createdAtMs },
            { byId.getValue(it).body.aad.senderUserId },
            { byId.getValue(it).body.aad.senderSeq },
            { it },
        )
        val q = PriorityQueue(cmp)
        indegree.filterValues { it == 0 }.keys.forEach(q::add)
        val out = mutableListOf<EidogramMessageV1>()
        while (q.isNotEmpty()) {
            val id = q.remove(); out += byId.getValue(id)
            children.getValue(id).sorted().forEach { child ->
                val n = indegree.getValue(child) - 1
                indegree[child] = n
                if (n == 0) q.add(child)
            }
        }
        require(out.size == messages.size) { "Archived message graph contains a cycle" }
        return out
    }

    private data class RecoveryPresentation(
        val aliases: Map<String, String>,
        val contactBundles: List<Pair<String, String>>,
        val conversations: Map<String, ConversationPresentationV1>,
    ) {
        companion object { val EMPTY = RecoveryPresentation(emptyMap(), emptyList(), emptyMap()) }
    }
}
