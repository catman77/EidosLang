package org.eidolang.core.recovery

import org.eidolang.core.archive.*
import org.eidolang.core.canonical.CanonicalJson
import org.eidolang.core.canonical.JValue
import org.eidolang.core.canonical.StrictJsonParser
import org.eidolang.core.crypto.B64Url
import org.eidolang.core.crypto.HexSha256
import org.eidolang.core.crypto.IdentityParser
import org.eidolang.core.message.ConversationParser

data class ArchiveFileBlobV1(
    val path: String,
    val byteLength: Int,
    val sha256: String,
    val bytesB64: String,
)

data class ArchiveSegmentBlobV1(
    val segmentId: String,
    val conversationId: String,
    val torrentName: String,
    val pieceLength: Int,
    val torrentInfoHashV2: String,
    val torrentMetainfoB64: String,
    val files: List<ArchiveFileBlobV1>,
)

data class ContactPresentationV1(
    val userId: String,
    val alias: String,
    val deviceBundleCanonicalJson: List<String>,
)
data class ConversationPresentationV1(
    val conversationId: String,
    val title: String,
    val createdAtMs: Long,
    val descriptorCanonicalJson: String,
)

data class ArchivePackageV1(
    val ownerUserId: String,
    val recoveryDeviceId: String,
    val recoveryEncryptionKeyId: String,
    val contacts: List<ContactPresentationV1>,
    val conversations: List<ConversationPresentationV1>,
    val segments: List<ArchiveSegmentBlobV1>,
    val pinnedSegmentIds: List<String>,
    val packageId: String,
)

object ArchiveSegmentCodec {
    fun fromArtifact(a: ConversationSegmentArtifactV1): ArchiveSegmentBlobV1 = ArchiveSegmentBlobV1(
        segmentId = a.manifest.segmentId,
        conversationId = a.manifest.conversationId,
        torrentName = a.torrent.name,
        pieceLength = a.torrent.pieceLength,
        torrentInfoHashV2 = a.torrent.infoHashV2Hex,
        torrentMetainfoB64 = B64Url.encode(a.torrent.metainfoBytes),
        files = a.files.sortedBy { it.path }.map {
            ArchiveFileBlobV1(it.path, it.bytes.size, HexSha256.of(it.bytes), B64Url.encode(it.bytes))
        },
    )

    fun toArtifact(b: ArchiveSegmentBlobV1): ConversationSegmentArtifactV1 {
        require(b.segmentId.matches(Regex("[0-9a-f]{64}")))
        require(b.conversationId.matches(Regex("[0-9a-f]{64}")))
        require(b.torrentInfoHashV2.matches(Regex("[0-9a-f]{64}")))
        val files = b.files.map { f ->
            val bytes = B64Url.decode(f.bytesB64)
            require(bytes.size == f.byteLength) { "Archive file length mismatch: ${f.path}" }
            require(HexSha256.of(bytes) == f.sha256) { "Archive file hash mismatch: ${f.path}" }
            TorrentFileV1(f.path, bytes)
        }
        require(files.map { it.path } == files.map { it.path }.sorted()) { "Archive file order is not canonical" }
        val artifact = ConversationSegmentLoader.loadAndVerify(
            files = files,
            torrentName = b.torrentName,
            pieceLength = b.pieceLength,
            expectedInfoHashV2Hex = b.torrentInfoHashV2,
        )
        require(artifact.manifest.segmentId == b.segmentId)
        require(artifact.manifest.conversationId == b.conversationId)
        require(artifact.torrent.metainfoBytes.contentEquals(B64Url.decode(b.torrentMetainfoB64))) {
            "Stored torrent metainfo does not match deterministic rebuild"
        }
        return artifact
    }

    fun fileJson(f: ArchiveFileBlobV1): String = CanonicalJson.obj(mapOf(
        "byte_length" to CanonicalJson.int(f.byteLength),
        "bytes_b64" to CanonicalJson.string(f.bytesB64),
        "path" to CanonicalJson.string(f.path),
        "sha256" to CanonicalJson.string(f.sha256),
    ))

    fun json(b: ArchiveSegmentBlobV1): String = CanonicalJson.obj(mapOf(
        "conversation_id" to CanonicalJson.string(b.conversationId),
        "files" to CanonicalJson.arr(b.files.map(::fileJson)),
        "piece_length" to CanonicalJson.int(b.pieceLength),
        "segment_id" to CanonicalJson.string(b.segmentId),
        "torrent_infohash_v2" to CanonicalJson.string(b.torrentInfoHashV2),
        "torrent_metainfo_b64" to CanonicalJson.string(b.torrentMetainfoB64),
        "torrent_name" to CanonicalJson.string(b.torrentName),
    ))

    fun parseCanonical(text: String): ArchiveSegmentBlobV1 {
        val o = StrictJsonParser(text).parse().obj()
        o.requireKeys("conversation_id", "files", "piece_length", "segment_id", "torrent_infohash_v2", "torrent_metainfo_b64", "torrent_name")
        val result = ArchiveSegmentBlobV1(
            segmentId = o.str("segment_id"),
            conversationId = o.str("conversation_id"),
            torrentName = o.str("torrent_name"),
            pieceLength = o.int("piece_length"),
            torrentInfoHashV2 = o.str("torrent_infohash_v2"),
            torrentMetainfoB64 = o.str("torrent_metainfo_b64"),
            files = o.arr("files").items.map { raw ->
                val f = raw.obj()
                f.requireKeys("byte_length", "bytes_b64", "path", "sha256")
                ArchiveFileBlobV1(f.str("path"), f.int("byte_length"), f.str("sha256"), f.str("bytes_b64"))
            },
        )
        require(result.files == result.files.sortedBy { it.path })
        require(json(result) == text) { "Archive segment blob is not canonical" }
        toArtifact(result)
        return result
    }
}

object ArchivePackageCodec {
    private fun contactJson(c: ContactPresentationV1) = CanonicalJson.obj(mapOf(
        "alias" to CanonicalJson.string(c.alias),
        "device_bundle_json" to CanonicalJson.arr(c.deviceBundleCanonicalJson.map(CanonicalJson::string)),
        "user_id" to CanonicalJson.string(c.userId),
    ))

    private fun conversationJson(c: ConversationPresentationV1) = CanonicalJson.obj(mapOf(
        "conversation_id" to CanonicalJson.string(c.conversationId),
        "created_at_ms" to CanonicalJson.long(c.createdAtMs),
        "descriptor_json" to CanonicalJson.string(c.descriptorCanonicalJson),
        "title" to CanonicalJson.string(c.title),
    ))

    fun bodyJson(p: ArchivePackageV1): String = CanonicalJson.obj(mapOf(
        "contacts" to CanonicalJson.arr(p.contacts.map(::contactJson)),
        "conversations" to CanonicalJson.arr(p.conversations.map(::conversationJson)),
        "owner_user_id" to CanonicalJson.string(p.ownerUserId),
        "package_type" to CanonicalJson.string("EidoArchivePackageV1"),
        "package_version" to CanonicalJson.string("1.0.0"),
        "pinned_segment_ids" to CanonicalJson.arr(p.pinnedSegmentIds.map(CanonicalJson::string)),
        "recovery_device_id" to CanonicalJson.string(p.recoveryDeviceId),
        "recovery_encryption_key_id" to CanonicalJson.string(p.recoveryEncryptionKeyId),
        "segments" to CanonicalJson.arr(p.segments.map(ArchiveSegmentCodec::json)),
    ))

    fun json(p: ArchivePackageV1): String = CanonicalJson.obj(mapOf(
        "body" to bodyJson(p),
        "package_id" to CanonicalJson.string(p.packageId),
    ))

    fun withId(
        ownerUserId: String,
        recoveryDeviceId: String,
        recoveryEncryptionKeyId: String,
        contacts: List<ContactPresentationV1>,
        conversations: List<ConversationPresentationV1>,
        segments: List<ArchiveSegmentBlobV1>,
        pinnedSegmentIds: List<String>,
    ): ArchivePackageV1 {
        val p = ArchivePackageV1(
            ownerUserId,
            recoveryDeviceId,
            recoveryEncryptionKeyId,
            contacts.sortedBy { it.userId },
            conversations.sortedBy { it.conversationId },
            segments.sortedBy { it.segmentId },
            pinnedSegmentIds.sorted().distinct(),
            "",
        )
        return p.copy(packageId = HexSha256.ofUtf8(bodyJson(p)))
    }

    fun parseCanonical(text: String): ArchivePackageV1 {
        val root = StrictJsonParser(text).parse().obj()
        root.requireKeys("body", "package_id")
        val b = root.obj("body")
        b.requireKeys(
            "contacts", "conversations", "owner_user_id", "package_type", "package_version",
            "pinned_segment_ids", "recovery_device_id", "recovery_encryption_key_id", "segments"
        )
        require(b.str("package_type") == "EidoArchivePackageV1")
        require(b.str("package_version") == "1.0.0")
        val p = ArchivePackageV1(
            ownerUserId = b.str("owner_user_id"),
            recoveryDeviceId = b.str("recovery_device_id"),
            recoveryEncryptionKeyId = b.str("recovery_encryption_key_id"),
            contacts = b.arr("contacts").items.map { raw ->
                val c = raw.obj(); c.requireKeys("alias", "device_bundle_json", "user_id")
                ContactPresentationV1(
                    c.str("user_id"),
                    c.str("alias"),
                    c.arr("device_bundle_json").items.map { it.str() },
                )
            },
            conversations = b.arr("conversations").items.map { raw ->
                val c = raw.obj(); c.requireKeys("conversation_id", "created_at_ms", "descriptor_json", "title")
                ConversationPresentationV1(
                    c.str("conversation_id"),
                    c.str("title"),
                    c.long("created_at_ms"),
                    c.str("descriptor_json"),
                )
            },
            segments = b.arr("segments").items.map { raw -> ArchiveSegmentCodec.parseCanonical(ArchiveSegmentCodec.json(parseSegmentObject(raw.obj()))) },
            pinnedSegmentIds = b.arr("pinned_segment_ids").items.map { it.str() },
            packageId = root.str("package_id"),
        )
        require(p.contacts == p.contacts.sortedBy { it.userId })
        require(p.contacts.map { it.userId }.distinct().size == p.contacts.size)
        p.contacts.forEach { c ->
            val bundles = c.deviceBundleCanonicalJson.map(IdentityParser::parsePublicBundleCanonical)
            require(bundles.all { it.user.userId == c.userId })
            require(bundles.map { it.device.deviceId } == bundles.map { it.device.deviceId }.sorted().distinct())
        }
        require(p.conversations == p.conversations.sortedBy { it.conversationId })
        require(p.conversations.map { it.conversationId }.distinct().size == p.conversations.size)
        p.conversations.forEach { c ->
            val d = ConversationParser.parseCanonical(c.descriptorCanonicalJson)
            require(d.conversationId == c.conversationId)
        }
        require(p.segments == p.segments.sortedBy { it.segmentId })
        require(p.segments.map { it.segmentId }.distinct().size == p.segments.size)
        require(p.pinnedSegmentIds == p.pinnedSegmentIds.sorted().distinct())
        require(p.pinnedSegmentIds.all { id -> p.segments.any { it.segmentId == id } })
        require(p.packageId == HexSha256.ofUtf8(bodyJson(p))) { "Archive package id mismatch" }
        require(json(p) == text) { "Archive package is not canonical" }
        return p
    }

    private fun parseSegmentObject(o: JValue.Obj): ArchiveSegmentBlobV1 {
        o.requireKeys("conversation_id", "files", "piece_length", "segment_id", "torrent_infohash_v2", "torrent_metainfo_b64", "torrent_name")
        return ArchiveSegmentBlobV1(
            segmentId = o.str("segment_id"),
            conversationId = o.str("conversation_id"),
            torrentName = o.str("torrent_name"),
            pieceLength = o.int("piece_length"),
            torrentInfoHashV2 = o.str("torrent_infohash_v2"),
            torrentMetainfoB64 = o.str("torrent_metainfo_b64"),
            files = o.arr("files").items.map { raw ->
                val f = raw.obj(); f.requireKeys("byte_length", "bytes_b64", "path", "sha256")
                ArchiveFileBlobV1(f.str("path"), f.int("byte_length"), f.str("sha256"), f.str("bytes_b64"))
            },
        )
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
