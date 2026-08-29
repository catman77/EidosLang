package org.eidolang.core.multidevice

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.EidogramDocumentV1

data class MessageKeyGrantBodyV1(
    val messageId: String,
    val ownerUserId: String,
    val grantorDeviceId: String,
    val grantorSigningKeyId: String,
    val targetDeviceId: String,
    val targetEncryptionKeyId: String,
    val wrappedKeyB64: String,
)

data class MessageKeyGrantV1(
    val body: MessageKeyGrantBodyV1,
    val grantId: String,
    val signatureB64: String,
)

object MessageKeyGrantCanonical {
    fun bodyJson(b: MessageKeyGrantBodyV1): String = CanonicalJson.obj(mapOf(
        "grant_type" to CanonicalJson.string("EidoMessageKeyGrantV1"),
        "grant_version" to CanonicalJson.string("1.0.0"),
        "grantor_device_id" to CanonicalJson.string(b.grantorDeviceId),
        "grantor_signing_key_id" to CanonicalJson.string(b.grantorSigningKeyId),
        "message_id" to CanonicalJson.string(b.messageId),
        "owner_user_id" to CanonicalJson.string(b.ownerUserId),
        "target_device_id" to CanonicalJson.string(b.targetDeviceId),
        "target_encryption_key_id" to CanonicalJson.string(b.targetEncryptionKeyId),
        "wrapped_key_b64" to CanonicalJson.string(b.wrappedKeyB64),
    ))

    fun json(g: MessageKeyGrantV1): String = CanonicalJson.obj(mapOf(
        "body" to bodyJson(g.body),
        "grant_id" to CanonicalJson.string(g.grantId),
        "signature_b64" to CanonicalJson.string(g.signatureB64),
    ))

    fun parseCanonical(text: String): MessageKeyGrantV1 {
        val root = StrictJsonParser(text).parse().obj()
        root.requireKeys("body", "grant_id", "signature_b64")
        val b = root.obj("body")
        b.requireKeys(
            "grant_type", "grant_version", "grantor_device_id", "grantor_signing_key_id",
            "message_id", "owner_user_id", "target_device_id", "target_encryption_key_id",
            "wrapped_key_b64"
        )
        require(b.str("grant_type") == "EidoMessageKeyGrantV1")
        require(b.str("grant_version") == "1.0.0")
        val g = MessageKeyGrantV1(
            MessageKeyGrantBodyV1(
                b.str("message_id"), b.str("owner_user_id"), b.str("grantor_device_id"),
                b.str("grantor_signing_key_id"), b.str("target_device_id"),
                b.str("target_encryption_key_id"), b.str("wrapped_key_b64"),
            ),
            root.str("grant_id"),
            root.str("signature_b64"),
        )
        require(json(g) == text) { "Message key grant is not canonical" }
        return g
    }
}

object MessageKeyGrantCrypto {
    fun issue(
        message: EidogramMessageV1,
        grantor: DevicePrivateCrypto,
        target: PublicIdentityBundleV1,
    ): MessageKeyGrantV1 {
        require(grantor.user.userId == target.user.userId) { "Historical key grants stay within one user identity" }
        val sourceBox = message.body.recipientBoxes.singleOrNull {
            it.encryptionKeyId == grantor.certificate.body.encryptionKeyId
        } ?: error("Grantor device cannot decrypt this message")
        val raw = grantor.unwrapMessageKey(B64Url.decode(sourceBox.wrappedKeyB64))
        try {
            val wrapped = JcaCrypto.wrapKey(
                PublicKeyCodec.rsa(target.device.body.encryptionPublicKeyB64),
                raw,
            )
            val body = MessageKeyGrantBodyV1(
                messageId = message.messageId,
                ownerUserId = grantor.user.userId,
                grantorDeviceId = grantor.certificate.deviceId,
                grantorSigningKeyId = grantor.certificate.body.signingKeyId,
                targetDeviceId = target.device.deviceId,
                targetEncryptionKeyId = target.device.body.encryptionKeyId,
                wrappedKeyB64 = B64Url.encode(wrapped),
            )
            val bytes = MessageKeyGrantCanonical.bodyJson(body).toByteArray(Charsets.UTF_8)
            return MessageKeyGrantV1(
                body,
                HexSha256.of(bytes),
                B64Url.encode(grantor.signMessage(bytes)),
            )
        } finally {
            raw.fill(0)
        }
    }

    fun verify(
        grant: MessageKeyGrantV1,
        message: EidogramMessageV1,
        grantor: PublicIdentityBundleV1,
        target: PublicIdentityBundleV1,
    ): Boolean = runCatching {
        if (!IdentityVerifier.verifyBundle(grantor) || !IdentityVerifier.verifyBundle(target)) return false
        if (grant.body.messageId != message.messageId) return false
        if (grant.body.ownerUserId != grantor.user.userId || grantor.user.userId != target.user.userId) return false
        if (grant.body.grantorDeviceId != grantor.device.deviceId) return false
        if (grant.body.grantorSigningKeyId != grantor.device.body.signingKeyId) return false
        if (grant.body.targetDeviceId != target.device.deviceId) return false
        if (grant.body.targetEncryptionKeyId != target.device.body.encryptionKeyId) return false
        val bytes = MessageKeyGrantCanonical.bodyJson(grant.body).toByteArray(Charsets.UTF_8)
        if (grant.grantId != HexSha256.of(bytes)) return false
        JcaCrypto.verify(
            PublicKeyCodec.ec(grantor.device.body.signingPublicKeyB64),
            bytes,
            B64Url.decode(grant.signatureB64),
        )
    }.getOrDefault(false)

    fun open(
        message: EidogramMessageV1,
        sender: PublicIdentityBundleV1,
        conversation: ConversationDescriptorV1,
        grant: MessageKeyGrantV1,
        grantor: PublicIdentityBundleV1,
        target: DevicePrivateCrypto,
    ): EidogramDocumentV1 = openPayload(message, sender, conversation, grant, grantor, target).document

    /** Same as [open] but keeps the caption a R22.2 sender wrote. */
    fun openPayload(
        message: EidogramMessageV1,
        sender: PublicIdentityBundleV1,
        conversation: ConversationDescriptorV1,
        grant: MessageKeyGrantV1,
        grantor: PublicIdentityBundleV1,
        target: DevicePrivateCrypto,
    ): EidoMessagePayloadV1 {
        require(MessageCrypto.verify(message, sender.user, sender.device, conversation)) { "Message authentication failed" }
        val targetBundle = PublicIdentityBundleV1(target.user, target.certificate)
        require(verify(grant, message, grantor, targetBundle)) { "Historical key grant verification failed" }
        val raw = target.unwrapMessageKey(B64Url.decode(grant.body.wrappedKeyB64))
        try {
            val plaintext = JcaCrypto.decryptAesGcm(
                raw,
                B64Url.decode(message.body.nonceB64),
                MessageCanonical.aadJson(message.body.aad).toByteArray(Charsets.UTF_8),
                B64Url.decode(message.body.ciphertextB64),
            )
            return EidoMessagePayloadCanonical.parseCanonical(plaintext.toString(Charsets.UTF_8))
        } finally {
            raw.fill(0)
        }
    }
}

private fun JValue.obj(): JValue.Obj = this as? JValue.Obj ?: error("Expected object")
private fun JValue.str(): String = (this as? JValue.Str)?.value ?: error("Expected string")
private fun JValue.Obj.str(k: String): String = fields[k]?.str() ?: error("Missing '$k'")
private fun JValue.Obj.obj(k: String): JValue.Obj = fields[k]?.obj() ?: error("Missing '$k'")
private fun JValue.Obj.requireKeys(vararg keys: String) {
    require(fields.keys == keys.toSet()) { "Object schema mismatch" }
}
