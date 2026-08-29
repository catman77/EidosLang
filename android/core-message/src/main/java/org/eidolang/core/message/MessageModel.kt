package org.eidolang.core.message

import org.eidolang.core.canonical.CanonicalJson
import org.eidolang.core.crypto.*
import org.eidolang.core.model.ProtocolLineage

data class ConversationDescriptorV1(
    val seedB64: String,
    val participantUserIds: List<String>,
    val conversationId: String,
)

object ConversationFactory {
    fun fromSeed(seed: ByteArray, participants: Collection<String>): ConversationDescriptorV1 {
        require(seed.size == 16) { "Conversation seed must be exactly 128 bits" }
        val ids = participants.toSortedSet().toList()
        require(ids.size >= 2) { "Conversation requires at least two distinct user identities" }
        ids.forEach { require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid user id" } }
        val seedB64 = B64Url.encode(seed)
        val body = ConversationCanonical.bodyJson(seedB64, ids)
        return ConversationDescriptorV1(seedB64, ids, HexSha256.ofUtf8(body))
    }
}

object ConversationCanonical {
    fun bodyJson(seedB64: String, participants: List<String>): String = CanonicalJson.obj(mapOf(
        "conversation_type" to CanonicalJson.string("EidoConversationV1"),
        "conversation_version" to CanonicalJson.string("1.0.0"),
        "participant_user_ids" to CanonicalJson.arr(participants.map(CanonicalJson::string)),
        "seed_b64" to CanonicalJson.string(seedB64),
    ))

    fun descriptorJson(c: ConversationDescriptorV1): String = CanonicalJson.obj(mapOf(
        "body" to bodyJson(c.seedB64, c.participantUserIds),
        "conversation_id" to CanonicalJson.string(c.conversationId),
    ))
}

data class RecipientPublicDeviceV1(
    val userId: String,
    val deviceId: String,
    val encryptionKeyId: String,
    val encryptionPublicKeyB64: String,
)

data class RecipientKeyBoxV1(
    val userId: String,
    val deviceId: String,
    val encryptionKeyId: String,
    val wrappedKeyB64: String,
)

data class MessageAadV1(
    val conversationId: String,
    val createdAtMs: Long,
    val senderUserId: String,
    val senderDeviceId: String,
    val senderSigningKeyId: String,
    val senderSeq: Int,
    val parentMessageIds: List<String>,
    val recipientEncryptionKeyIds: List<String>,
    val contentType: String = "eidogram-document-v1",
    /**
     * The glyph catalogue this message was authored against.
     *
     * A field, not the compile-time constant it used to be. The catalogue hash goes into the AAD,
     * so it is covered by the AES-GCM tag and by the sender's signature: reading it from a constant
     * meant that the day the catalogue gained a glyph, every message ever signed — every golden
     * vector from R15 onwards included — would be re-authenticated against a hash it was never
     * signed with, and fail. Carried with the message, an old message verifies against the
     * catalogue it was written for and a new one against the current default.
     */
    val catalogHash: String = ProtocolLineage.GLYPH_CATALOG_HASH,
)

data class EncryptedMessageBodyV1(
    val aad: MessageAadV1,
    val nonceB64: String,
    val ciphertextB64: String,
    val recipientBoxes: List<RecipientKeyBoxV1>,
)

data class MessageSignatureV1(
    val algorithm: String,
    val valueB64: String,
)

data class EidogramMessageV1(
    val body: EncryptedMessageBodyV1,
    val messageId: String,
    val signature: MessageSignatureV1,
)

object MessageCanonical {
    const val TYPE = "EidogramMessageV1"
    const val VERSION = "1.0.0"

    fun aadJson(a: MessageAadV1): String = CanonicalJson.obj(mapOf(
        "catalog_hash" to CanonicalJson.string(a.catalogHash),
        "content_type" to CanonicalJson.string(a.contentType),
        "conversation_id" to CanonicalJson.string(a.conversationId),
        "created_at_ms" to CanonicalJson.long(a.createdAtMs),
        "master_manifest_hash" to CanonicalJson.string(ProtocolLineage.MASTER_MANIFEST_HASH),
        "message_type" to CanonicalJson.string(TYPE),
        "message_version" to CanonicalJson.string(VERSION),
        "parent_message_ids" to CanonicalJson.arr(a.parentMessageIds.map(CanonicalJson::string)),
        "production_binding_hash" to CanonicalJson.string(ProtocolLineage.PRODUCTION_BINDING_HASH),
        "production_grammar_hash" to CanonicalJson.string(ProtocolLineage.PRODUCTION_GRAMMAR_HASH),
        "recipient_encryption_key_ids" to CanonicalJson.arr(a.recipientEncryptionKeyIds.map(CanonicalJson::string)),
        "sender_device_id" to CanonicalJson.string(a.senderDeviceId),
        "sender_seq" to CanonicalJson.int(a.senderSeq),
        "sender_signing_key_id" to CanonicalJson.string(a.senderSigningKeyId),
        "sender_user_id" to CanonicalJson.string(a.senderUserId),
    ))

    fun recipientBoxJson(b: RecipientKeyBoxV1): String = CanonicalJson.obj(mapOf(
        "device_id" to CanonicalJson.string(b.deviceId),
        "encryption_key_id" to CanonicalJson.string(b.encryptionKeyId),
        "user_id" to CanonicalJson.string(b.userId),
        "wrapped_key_b64" to CanonicalJson.string(b.wrappedKeyB64),
    ))

    fun bodyJson(b: EncryptedMessageBodyV1): String = CanonicalJson.obj(mapOf(
        "aad" to aadJson(b.aad),
        "ciphertext_b64" to CanonicalJson.string(b.ciphertextB64),
        "nonce_b64" to CanonicalJson.string(b.nonceB64),
        "recipient_boxes" to CanonicalJson.arr(b.recipientBoxes.map(::recipientBoxJson)),
    ))

    fun signatureJson(s: MessageSignatureV1): String = CanonicalJson.obj(mapOf(
        "algorithm" to CanonicalJson.string(s.algorithm),
        "value_b64" to CanonicalJson.string(s.valueB64),
    ))

    fun envelopeJson(m: EidogramMessageV1): String = CanonicalJson.obj(mapOf(
        "body" to bodyJson(m.body),
        "message_id" to CanonicalJson.string(m.messageId),
        "signature" to signatureJson(m.signature),
    ))
}
