package org.eidolang.core.archive

import org.eidolang.core.canonical.JValue
import org.eidolang.core.canonical.StrictJsonParser
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*

object SegmentParser {
    fun parseCanonical(text: String): ConversationSegmentManifestV1 {
        val root = StrictJsonParser(text).parse().obj()
        root.requireKeys("body", "segment_id")
        val b = root.obj("body")
        b.requireKeys(
            "conversation_id", "external_parent_message_ids", "head_message_ids", "identity_entries",
            "manifest_type", "manifest_version", "message_entries", "previous_segment_ids"
        )
        require(b.str("manifest_type") == "ConversationSegmentV1")
        require(b.str("manifest_version") == "1.0.0")
        val m = ConversationSegmentManifestV1(
            conversationId = b.str("conversation_id"),
            previousSegmentIds = b.arr("previous_segment_ids").items.map { it.str() },
            externalParentMessageIds = b.arr("external_parent_message_ids").items.map { it.str() },
            headMessageIds = b.arr("head_message_ids").items.map { it.str() },
            identityEntries = b.arr("identity_entries").items.map { parseIdentity(it.obj()) },
            messageEntries = b.arr("message_entries").items.map { parseMessage(it.obj()) },
            segmentId = root.str("segment_id"),
        )
        require(m.previousSegmentIds == m.previousSegmentIds.sorted().distinct())
        require(m.externalParentMessageIds == m.externalParentMessageIds.sorted().distinct())
        require(m.headMessageIds == m.headMessageIds.sorted().distinct())
        require(m.identityEntries == m.identityEntries.sortedWith(compareBy<SegmentIdentityEntryV1> { it.userId }.thenBy { it.deviceId }))
        require(m.messageEntries == m.messageEntries.sortedBy { it.messageId })
        require(m.segmentId == HexSha256.ofUtf8(SegmentCanonical.bodyJson(m)))
        require(SegmentCanonical.manifestJson(m) == text) { "segment manifest is not canonical" }
        return m
    }

    private fun parseIdentity(o: JValue.Obj): SegmentIdentityEntryV1 {
        o.requireKeys("bundle_sha256", "byte_length", "device_id", "path", "user_id")
        return SegmentIdentityEntryV1(o.str("user_id"), o.str("device_id"), o.str("bundle_sha256"), o.str("path"), o.int("byte_length"))
    }

    private fun parseMessage(o: JValue.Obj): SegmentMessageEntryV1 {
        o.requireKeys(
            "byte_length", "created_at_ms", "envelope_sha256", "message_id", "parent_message_ids",
            "path", "sender_device_id", "sender_seq", "sender_user_id"
        )
        return SegmentMessageEntryV1(
            messageId = o.str("message_id"),
            senderUserId = o.str("sender_user_id"),
            senderDeviceId = o.str("sender_device_id"),
            senderSeq = o.int("sender_seq"),
            createdAtMs = o.long("created_at_ms"),
            parentMessageIds = o.arr("parent_message_ids").items.map { it.str() },
            envelopeSha256 = o.str("envelope_sha256"),
            path = o.str("path"),
            byteLength = o.int("byte_length"),
        )
    }
}

object ConversationSegmentLoader {
    fun loadAndVerify(
        files: Collection<TorrentFileV1>,
        torrentName: String,
        pieceLength: Int = TorrentV2Builder.DEFAULT_PIECE_LENGTH,
        expectedInfoHashV2Hex: String? = null,
    ): ConversationSegmentArtifactV1 {
        val map = files.associateBy { it.path }
        require(map.size == files.size)
        val manifest = SegmentParser.parseCanonical(map.getValue("segment-manifest.json").bytes.toString(Charsets.UTF_8))
        val conversation = ConversationParser.parseCanonical(map.getValue("conversation.json").bytes.toString(Charsets.UTF_8))
        require(conversation.conversationId == manifest.conversationId)

        val identities = manifest.identityEntries.associate { e ->
            val bytes = map.getValue(e.path).bytes
            require(bytes.size == e.byteLength && HexSha256.of(bytes) == e.bundleSha256)
            val bundle = IdentityParser.parsePublicBundleCanonical(bytes.toString(Charsets.UTF_8))
            require(bundle.user.userId == e.userId && bundle.device.deviceId == e.deviceId)
            e.deviceId to bundle
        }
        require(identities.size == manifest.identityEntries.size)

        val messages = manifest.messageEntries.associate { e ->
            val bytes = map.getValue(e.path).bytes
            require(bytes.size == e.byteLength && HexSha256.of(bytes) == e.envelopeSha256)
            val msg = MessageParser.parseCanonical(bytes.toString(Charsets.UTF_8))
            require(msg.messageId == e.messageId)
            require(msg.body.aad.senderUserId == e.senderUserId && msg.body.aad.senderDeviceId == e.senderDeviceId)
            require(msg.body.aad.senderSeq == e.senderSeq && msg.body.aad.createdAtMs == e.createdAtMs)
            require(msg.body.aad.parentMessageIds == e.parentMessageIds)
            val sender = identities[msg.body.aad.senderDeviceId] ?: error("sender identity absent")
            require(MessageCrypto.verify(msg, sender.user, sender.device, conversation))
            e.messageId to msg
        }
        require(messages.size == manifest.messageEntries.size)

        val local = messages.keys
        val external = manifest.externalParentMessageIds.toSet()
        messages.values.flatMap { it.body.aad.parentMessageIds }.forEach { require(it in local || it in external) }
        val consumed = messages.values.flatMap { it.body.aad.parentMessageIds }.filter { it in local }.toSet()
        require((local - consumed).sorted() == manifest.headMessageIds)

        val rebuilt = TorrentV2Builder.build(torrentName, files, pieceLength)
        if (expectedInfoHashV2Hex != null) require(rebuilt.infoHashV2Hex == expectedInfoHashV2Hex)
        return ConversationSegmentArtifactV1(manifest, files.sortedBy { it.path }, rebuilt)
    }
}

private fun JValue.obj(): JValue.Obj = this as? JValue.Obj ?: error("Expected object")
private fun JValue.str(): String = (this as? JValue.Str)?.value ?: error("Expected string")
private fun JValue.Obj.str(k: String): String = fields[k]?.str() ?: error("Missing '$k'")
private fun JValue.Obj.obj(k: String): JValue.Obj = fields[k]?.obj() ?: error("Missing '$k'")
private fun JValue.Obj.arr(k: String): JValue.Arr = fields[k] as? JValue.Arr ?: error("Missing '$k'")
private fun JValue.Obj.int(k: String): Int {
    val v = (fields[k] as? JValue.IntNum)?.value ?: error("Missing int '$k'")
    require(v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return v.toInt()
}
private fun JValue.Obj.long(k: String): Long = (fields[k] as? JValue.IntNum)?.value ?: error("Missing long '$k'")
private fun JValue.Obj.requireKeys(vararg keys: String) { require(fields.keys == keys.toSet()) { "Object schema mismatch" } }
