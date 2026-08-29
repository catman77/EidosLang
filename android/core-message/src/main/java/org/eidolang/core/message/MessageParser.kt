package org.eidolang.core.message

import org.eidolang.core.canonical.JValue
import org.eidolang.core.canonical.StrictJsonParser
import org.eidolang.core.crypto.CryptoSuiteV1

object MessageParser {
    fun parseCanonical(text: String): EidogramMessageV1 {
        val root = StrictJsonParser(text).parse().obj()
        root.requireKeys("body", "message_id", "signature")
        val body = parseBody(root.obj("body"))
        val sigObj = root.obj("signature")
        sigObj.requireKeys("algorithm", "value_b64")
        val result = EidogramMessageV1(
            body = body,
            messageId = root.str("message_id"),
            signature = MessageSignatureV1(sigObj.str("algorithm"), sigObj.str("value_b64")),
        )
        require(result.signature.algorithm == CryptoSuiteV1.SIGNATURE)
        require(MessageCanonical.envelopeJson(result) == text) { "Message JSON is not canonical" }
        return result
    }

    private fun parseBody(o: JValue.Obj): EncryptedMessageBodyV1 {
        o.requireKeys("aad", "ciphertext_b64", "nonce_b64", "recipient_boxes")
        return EncryptedMessageBodyV1(
            aad = parseAad(o.obj("aad")),
            nonceB64 = o.str("nonce_b64"),
            ciphertextB64 = o.str("ciphertext_b64"),
            recipientBoxes = o.arr("recipient_boxes").items.map { raw ->
                val b = raw.obj()
                b.requireKeys("device_id", "encryption_key_id", "user_id", "wrapped_key_b64")
                RecipientKeyBoxV1(
                    userId = b.str("user_id"),
                    deviceId = b.str("device_id"),
                    encryptionKeyId = b.str("encryption_key_id"),
                    wrappedKeyB64 = b.str("wrapped_key_b64"),
                )
            },
        )
    }

    private fun parseAad(o: JValue.Obj): MessageAadV1 {
        o.requireKeys(
            "catalog_hash", "content_type", "conversation_id", "created_at_ms", "master_manifest_hash",
            "message_type", "message_version", "parent_message_ids", "production_binding_hash",
            "production_grammar_hash", "recipient_encryption_key_ids", "sender_device_id",
            "sender_seq", "sender_signing_key_id", "sender_user_id"
        )

        // These fields are checked again by MessageCrypto.verify; parser rejects obvious protocol drift.
        require(o.str("message_type") == MessageCanonical.TYPE)
        require(o.str("message_version") == MessageCanonical.VERSION)

        return MessageAadV1(
            conversationId = o.str("conversation_id"),
            createdAtMs = o.long("created_at_ms"),
            senderUserId = o.str("sender_user_id"),
            senderDeviceId = o.str("sender_device_id"),
            senderSigningKeyId = o.str("sender_signing_key_id"),
            senderSeq = o.int("sender_seq"),
            parentMessageIds = o.arr("parent_message_ids").items.map { it.str() },
            recipientEncryptionKeyIds = o.arr("recipient_encryption_key_ids").items.map { it.str() },
            contentType = o.str("content_type"),
            catalogHash = o.str("catalog_hash"),
        )
    }
}

private fun JValue.obj(): JValue.Obj = this as? JValue.Obj ?: error("Expected JSON object")
private fun JValue.str(): String = (this as? JValue.Str)?.value ?: error("Expected JSON string")
private fun JValue.Obj.str(k: String): String = fields[k]?.str() ?: error("Missing string '$k'")
private fun JValue.Obj.int(k: String): Int {
    val v = (fields[k] as? JValue.IntNum)?.value ?: error("Missing integer '$k'")
    require(v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
    return v.toInt()
}
private fun JValue.Obj.long(k: String): Long = (fields[k] as? JValue.IntNum)?.value ?: error("Missing integer '$k'")
private fun JValue.Obj.obj(k: String): JValue.Obj = fields[k]?.obj() ?: error("Missing object '$k'")
private fun JValue.Obj.arr(k: String): JValue.Arr = fields[k] as? JValue.Arr ?: error("Missing array '$k'")
private fun JValue.Obj.requireKeys(vararg keys: String) {
    require(fields.keys == keys.toSet()) { "Object schema mismatch: got=${fields.keys} expected=${keys.toSet()}" }
}
