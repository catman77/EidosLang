package org.eidolang.core.archive

import org.eidolang.core.crypto.*
import java.security.MessageDigest
import java.security.Signature

/** Compact BEP44 value. All 32-byte hashes are stored as raw binary bencode strings. */
data class ConversationHeadValueV1(
    val conversationId: String,
    val publisherKeyId: String,
    val publisherDeviceId: String,
    val segmentId: String,
    val torrentInfoHashV2: String,
    val headMessageIds: List<String>,
)

data class Bep44MutableHeadV1(
    val publicKeyRaw: ByteArray,
    val salt: ByteArray,
    val seq: Long,
    val value: BValue.Dict,
    val signature: ByteArray,
    val targetSha1Hex: String,
)

object ConversationHeadCodec {
    fun value(h: ConversationHeadValueV1): BValue.Dict {
        require(h.conversationId.matches(Regex("[0-9a-f]{64}")))
        require(h.segmentId.matches(Regex("[0-9a-f]{64}")))
        require(h.torrentInfoHashV2.matches(Regex("[0-9a-f]{64}")))
        require(h.publisherKeyId.startsWith("dht:"))
        require(h.publisherDeviceId.startsWith("dev:"))
        val heads = h.headMessageIds.toSortedSet().toList()
        require(heads.isNotEmpty() && heads.size <= 8)
        heads.forEach { require(it.matches(Regex("[0-9a-f]{64}"))) }
        return Bencode.dict(
            "c" to Bencode.bytes(hex(h.conversationId)),
            "d" to Bencode.text(h.publisherDeviceId),
            "h" to BValue.ListVal(heads.map { Bencode.bytes(hex(it)) }),
            "i" to Bencode.bytes(hex(h.torrentInfoHashV2)),
            "k" to Bencode.text(h.publisherKeyId),
            "s" to Bencode.bytes(hex(h.segmentId)),
            "v" to Bencode.int(1),
        )
    }


    fun decodeValue(value: BValue.Dict): ConversationHeadValueV1 {
        val keys = value.value.keys.map { it.utf8() }.toSet()
        require(keys == setOf("c", "d", "h", "i", "k", "s", "v"))
        fun field(name: String): BValue = value.value[ByteKey.utf8(name)] ?: error("missing $name")
        fun rawHash(name: String): String {
            val b = (field(name) as? BValue.Bytes)?.value ?: error("bad $name")
            require(b.size == 32)
            return b.joinToString("") { "%02x".format(it) }
        }
        val version = (field("v") as? BValue.IntVal)?.value ?: error("bad version")
        require(version == 1L)
        val heads = (field("h") as? BValue.ListVal)?.value?.map { item ->
            val b = (item as? BValue.Bytes)?.value ?: error("bad head")
            require(b.size == 32)
            b.joinToString("") { "%02x".format(it) }
        } ?: error("bad heads")
        val out = ConversationHeadValueV1(
            conversationId = rawHash("c"),
            publisherKeyId = ((field("k") as BValue.Bytes).value.toString(Charsets.UTF_8)),
            publisherDeviceId = ((field("d") as BValue.Bytes).value.toString(Charsets.UTF_8)),
            segmentId = rawHash("s"),
            torrentInfoHashV2 = rawHash("i"),
            headMessageIds = heads,
        )
        require(value(out) == value) { "noncanonical conversation-head value" }
        return out
    }
    fun signable(salt: ByteArray, seq: Long, value: BValue.Dict): ByteArray {
        require(salt.size <= 64)
        require(seq in 0..Long.MAX_VALUE)
        val prefix = Bencode.encode(Bencode.bytes("salt".toByteArray())) + Bencode.encode(Bencode.bytes(salt)) +
            Bencode.encode(Bencode.bytes("seq".toByteArray())) + Bencode.encode(Bencode.int(seq)) +
            Bencode.encode(Bencode.bytes("v".toByteArray()))
        return prefix + Bencode.encode(value)
    }

    fun create(
        head: ConversationHeadValueV1,
        seq: Long,
        publisher: JcaArchivePublisher,
    ): Bep44MutableHeadV1 {
        require(head.publisherKeyId == publisher.certificate.publisherKeyId)
        require(head.publisherDeviceId == publisher.certificate.deviceId)
        val pub = publisher.publicKeyRaw
        val salt = hex(head.conversationId)
        val value = value(head)
        val encodedV = Bencode.encode(value)
        require(encodedV.size <= 1000) { "BEP44 v exceeds 1000-byte interoperability budget" }
        val signable = signable(salt, seq, value)
        val sig = publisher.signBep44(signable)
        require(sig.size == 64)
        val target = MessageDigest.getInstance("SHA-1").digest(pub + salt).joinToString("") { "%02x".format(it) }
        return Bep44MutableHeadV1(pub, salt, seq, value, sig, target)
    }

    fun verify(
        item: Bep44MutableHeadV1,
        publisherCertificate: ArchivePublisherCertificateV1,
        identity: PublicIdentityBundleV1,
    ): Boolean = runCatching {
        require(ArchivePublisherVerifier.verify(publisherCertificate, identity))
        require(item.publicKeyRaw.contentEquals(B64Url.decode(publisherCertificate.dhtPublicKeyRawB64)))
        require(item.salt.size <= 64 && item.seq >= 0)
        require(Bencode.encode(item.value).size <= 1000)
        val target = MessageDigest.getInstance("SHA-1").digest(item.publicKeyRaw + item.salt).joinToString("") { "%02x".format(it) }
        require(target == item.targetSha1Hex)
        require(Ed25519Raw.verify(item.publicKeyRaw, signable(item.salt, item.seq, item.value), item.signature))
        true
    }.getOrDefault(false)

    fun mutablePutFields(item: Bep44MutableHeadV1): BValue.Dict = Bencode.dict(
        "k" to Bencode.bytes(item.publicKeyRaw),
        "salt" to Bencode.bytes(item.salt),
        "seq" to Bencode.int(item.seq),
        "sig" to Bencode.bytes(item.signature),
        "v" to item.value,
    )

    fun parseMutablePutFields(bytes: ByteArray): Bep44MutableHeadV1 {
        val d = Bencode.decode(bytes) as? BValue.Dict ?: error("BEP44 fields must be dictionary")
        val keys = d.value.keys.map { it.utf8() }.toSet()
        require(keys == setOf("k", "salt", "seq", "sig", "v"))
        fun field(name: String): BValue = d.value[ByteKey.utf8(name)] ?: error("missing $name")
        val k = (field("k") as? BValue.Bytes)?.value ?: error("bad k")
        val salt = (field("salt") as? BValue.Bytes)?.value ?: error("bad salt")
        val seq = (field("seq") as? BValue.IntVal)?.value ?: error("bad seq")
        val sig = (field("sig") as? BValue.Bytes)?.value ?: error("bad sig")
        val v = field("v") as? BValue.Dict ?: error("bad v")
        require(k.size == 32 && sig.size == 64 && salt.size <= 64 && seq >= 0)
        val target = MessageDigest.getInstance("SHA-1").digest(k + salt).joinToString("") { "%02x".format(it) }
        return Bep44MutableHeadV1(k, salt, seq, v, sig, target)
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
