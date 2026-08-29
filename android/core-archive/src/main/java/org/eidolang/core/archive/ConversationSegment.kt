package org.eidolang.core.archive

import org.eidolang.core.canonical.CanonicalJson
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*

/** A verified immutable archive object; no network semantics are implied here. */
data class SegmentMessageEntryV1(
    val messageId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val senderSeq: Int,
    val createdAtMs: Long,
    val parentMessageIds: List<String>,
    val envelopeSha256: String,
    val path: String,
    val byteLength: Int,
)

data class SegmentIdentityEntryV1(
    val userId: String,
    val deviceId: String,
    val bundleSha256: String,
    val path: String,
    val byteLength: Int,
)

data class ConversationSegmentManifestV1(
    val conversationId: String,
    val previousSegmentIds: List<String>,
    val externalParentMessageIds: List<String>,
    val headMessageIds: List<String>,
    val identityEntries: List<SegmentIdentityEntryV1>,
    val messageEntries: List<SegmentMessageEntryV1>,
    val segmentId: String,
)

data class ConversationSegmentArtifactV1(
    val manifest: ConversationSegmentManifestV1,
    val files: List<TorrentFileV1>,
    val torrent: TorrentV2Artifact,
)

object SegmentCanonical {
    fun identityEntryJson(e: SegmentIdentityEntryV1): String = CanonicalJson.obj(mapOf(
        "bundle_sha256" to CanonicalJson.string(e.bundleSha256),
        "byte_length" to CanonicalJson.int(e.byteLength),
        "device_id" to CanonicalJson.string(e.deviceId),
        "path" to CanonicalJson.string(e.path),
        "user_id" to CanonicalJson.string(e.userId),
    ))

    fun messageEntryJson(e: SegmentMessageEntryV1): String = CanonicalJson.obj(mapOf(
        "byte_length" to CanonicalJson.int(e.byteLength),
        "created_at_ms" to CanonicalJson.long(e.createdAtMs),
        "envelope_sha256" to CanonicalJson.string(e.envelopeSha256),
        "message_id" to CanonicalJson.string(e.messageId),
        "parent_message_ids" to CanonicalJson.arr(e.parentMessageIds.map(CanonicalJson::string)),
        "path" to CanonicalJson.string(e.path),
        "sender_device_id" to CanonicalJson.string(e.senderDeviceId),
        "sender_seq" to CanonicalJson.int(e.senderSeq),
        "sender_user_id" to CanonicalJson.string(e.senderUserId),
    ))

    fun bodyJson(m: ConversationSegmentManifestV1): String = CanonicalJson.obj(mapOf(
        "conversation_id" to CanonicalJson.string(m.conversationId),
        "external_parent_message_ids" to CanonicalJson.arr(m.externalParentMessageIds.map(CanonicalJson::string)),
        "head_message_ids" to CanonicalJson.arr(m.headMessageIds.map(CanonicalJson::string)),
        "identity_entries" to CanonicalJson.arr(m.identityEntries.map(::identityEntryJson)),
        "manifest_type" to CanonicalJson.string("ConversationSegmentV1"),
        "manifest_version" to CanonicalJson.string("1.0.0"),
        "message_entries" to CanonicalJson.arr(m.messageEntries.map(::messageEntryJson)),
        "previous_segment_ids" to CanonicalJson.arr(m.previousSegmentIds.map(CanonicalJson::string)),
    ))

    fun manifestJson(m: ConversationSegmentManifestV1): String = CanonicalJson.obj(mapOf(
        "body" to bodyJson(m),
        "segment_id" to CanonicalJson.string(m.segmentId),
    ))
}

object ConversationSegmentBuilder {
    fun build(
        conversation: ConversationDescriptorV1,
        identityBundles: Collection<PublicIdentityBundleV1>,
        messageCanonicalJson: Collection<String>,
        previousSegmentIds: Collection<String> = emptyList(),
        knownExternalMessageIds: Collection<String> = emptyList(),
        pieceLength: Int = TorrentV2Builder.DEFAULT_PIECE_LENGTH,
    ): ConversationSegmentArtifactV1 {
        val ids = identityBundles.associateBy { it.device.deviceId }
        require(ids.size == identityBundles.size) { "duplicate device identity bundle" }
        ids.values.forEach {
            require(IdentityVerifier.verifyBundle(it)) { "invalid identity bundle" }
            require(it.user.userId in conversation.participantUserIds) { "identity not in conversation" }
        }

        val parsedMessages = messageCanonicalJson.map { raw -> raw to MessageParser.parseCanonical(raw) }
        require(parsedMessages.isNotEmpty()) { "empty segment" }
        val byMessage = parsedMessages.associateBy { it.second.messageId }
        require(byMessage.size == parsedMessages.size) { "duplicate message id" }

        parsedMessages.forEach { (_, message) ->
            val bundle = ids[message.body.aad.senderDeviceId] ?: error("missing sender identity bundle")
            require(MessageCrypto.verify(message, bundle.user, bundle.device, conversation)) { "invalid archived message ${message.messageId}" }
        }

        val local = byMessage.keys
        val externalAllowed = knownExternalMessageIds.toSet()
        val externalParents = sortedSetOf<String>()
        parsedMessages.forEach { (_, m) ->
            m.body.aad.parentMessageIds.forEach { parent ->
                if (parent !in local) {
                    require(parent in externalAllowed) { "unresolved parent $parent" }
                    externalParents += parent
                }
            }
        }
        val consumedLocalParents = parsedMessages.flatMap { it.second.body.aad.parentMessageIds }.filter { it in local }.toSet()
        val heads = (local - consumedLocalParents).sorted()
        require(heads.isNotEmpty())

        val identityEntries = ids.values.sortedWith(compareBy<PublicIdentityBundleV1> { it.user.userId }.thenBy { it.device.deviceId }).map { b ->
            val raw = IdentityCanonical.publicBundleJson(b)
            val path = "identities/${b.user.userId}/${b.device.deviceId.removePrefix("dev:")}.json"
            SegmentIdentityEntryV1(b.user.userId, b.device.deviceId, HexSha256.ofUtf8(raw), path, raw.toByteArray().size)
        }
        val messageEntries = parsedMessages.sortedBy { it.second.messageId }.map { (raw, m) ->
            val path = "messages/${m.messageId}.json"
            SegmentMessageEntryV1(
                m.messageId,
                m.body.aad.senderUserId,
                m.body.aad.senderDeviceId,
                m.body.aad.senderSeq,
                m.body.aad.createdAtMs,
                m.body.aad.parentMessageIds,
                HexSha256.ofUtf8(raw),
                path,
                raw.toByteArray().size,
            )
        }
        val prev = previousSegmentIds.toSortedSet().toList()
        prev.forEach { require(it.matches(Regex("[0-9a-f]{64}"))) }
        val provisional = ConversationSegmentManifestV1(
            conversation.conversationId,
            prev,
            externalParents.toList(),
            heads,
            identityEntries,
            messageEntries,
            segmentId = "",
        )
        val segmentId = HexSha256.ofUtf8(SegmentCanonical.bodyJson(provisional))
        val manifest = provisional.copy(segmentId = segmentId)

        val files = buildList {
            add(TorrentFileV1("conversation.json", ConversationCanonical.descriptorJson(conversation).toByteArray(Charsets.UTF_8)))
            identityEntries.forEach { e ->
                val b = ids.getValue(e.deviceId)
                add(TorrentFileV1(e.path, IdentityCanonical.publicBundleJson(b).toByteArray(Charsets.UTF_8)))
            }
            messageEntries.forEach { e -> add(TorrentFileV1(e.path, byMessage.getValue(e.messageId).first.toByteArray(Charsets.UTF_8))) }
            add(TorrentFileV1("segment-manifest.json", SegmentCanonical.manifestJson(manifest).toByteArray(Charsets.UTF_8)))
        }.sortedBy { it.path }

        val torrentName = "eidolang-${conversation.conversationId.take(12)}-${segmentId.take(12)}"
        val torrent = TorrentV2Builder.build(torrentName, files, pieceLength)
        return ConversationSegmentArtifactV1(manifest, files, torrent)
    }

    fun verifyArtifact(artifact: ConversationSegmentArtifactV1): Boolean = runCatching {
        val fileMap = artifact.files.associateBy { it.path }
        require(fileMap.size == artifact.files.size)
        val manifestBytes = fileMap.getValue("segment-manifest.json").bytes.toString(Charsets.UTF_8)
        require(manifestBytes == SegmentCanonical.manifestJson(artifact.manifest))
        require(artifact.manifest.segmentId == HexSha256.ofUtf8(SegmentCanonical.bodyJson(artifact.manifest)))
        artifact.manifest.messageEntries.forEach { e ->
            val b = fileMap.getValue(e.path).bytes
            require(b.size == e.byteLength && HexSha256.of(b) == e.envelopeSha256)
            require(MessageParser.parseCanonical(b.toString(Charsets.UTF_8)).messageId == e.messageId)
        }
        artifact.manifest.identityEntries.forEach { e ->
            val b = fileMap.getValue(e.path).bytes
            require(b.size == e.byteLength && HexSha256.of(b) == e.bundleSha256)
            require(IdentityVerifier.verifyBundle(IdentityParser.parsePublicBundleCanonical(b.toString(Charsets.UTF_8))))
        }
        val rebuilt = TorrentV2Builder.build(artifact.torrent.name, artifact.files, artifact.torrent.pieceLength)
        require(rebuilt.infoHashV2Hex == artifact.torrent.infoHashV2Hex)
        require(rebuilt.metainfoBytes.contentEquals(artifact.torrent.metainfoBytes))
        true
    }.getOrDefault(false)
}
