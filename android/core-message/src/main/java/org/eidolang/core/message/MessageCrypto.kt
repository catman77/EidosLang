package org.eidolang.core.message

import org.eidolang.core.canonical.EidoMessagePayloadCanonical
import org.eidolang.core.canonical.EidoMessagePayloadV1
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.canonical.EidogramParser
import org.eidolang.core.crypto.*
import org.eidolang.core.model.EidogramDocumentV1
import java.security.SecureRandom

object MessageCrypto {
    fun recipient(cert: DeviceCertificateV1): RecipientPublicDeviceV1 =
        RecipientPublicDeviceV1(
            userId = cert.body.userId,
            deviceId = cert.deviceId,
            encryptionKeyId = cert.body.encryptionKeyId,
            encryptionPublicKeyB64 = cert.body.encryptionPublicKeyB64,
        )

    fun seal(
        document: EidogramDocumentV1,
        /** Author's own words for the eidogram; travels inside the ciphertext. */
        caption: String = "",
        conversation: ConversationDescriptorV1,
        sender: DevicePrivateCrypto,
        recipients: Collection<RecipientPublicDeviceV1>,
        senderSeq: Int,
        createdAtMs: Long,
        parentMessageIds: Collection<String> = emptyList(),
        random: SecureRandom = SecureRandom(),
    ): EidogramMessageV1 {
        require(senderSeq >= 0)
        require(createdAtMs >= 0)
        require(sender.user.userId in conversation.participantUserIds) { "Sender is not a conversation participant" }
        check(IdentityVerifier.verifyDevice(sender.user, sender.certificate)) { "Invalid sender device certificate" }

        val parents = parentMessageIds.toSortedSet().toList()
        require(parents.size <= 16) { "Too many message parents" }
        parents.forEach { require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid parent message id" } }

        val allRecipients = (recipients + recipient(sender.certificate))
            .associateBy { it.encryptionKeyId }
            .values
            .sortedBy { it.encryptionKeyId }

        require(allRecipients.isNotEmpty())
        allRecipients.forEach { r ->
            require(r.userId in conversation.participantUserIds) { "Recipient user is not in conversation" }
            require(r.deviceId.startsWith("dev:")) { "Invalid recipient device id" }
            require(r.encryptionKeyId.startsWith("enc:")) { "Invalid recipient encryption key id" }
        }

        val aad = MessageAadV1(
            conversationId = conversation.conversationId,
            createdAtMs = createdAtMs,
            senderUserId = sender.user.userId,
            senderDeviceId = sender.certificate.deviceId,
            senderSigningKeyId = sender.certificate.body.signingKeyId,
            senderSeq = senderSeq,
            parentMessageIds = parents,
            recipientEncryptionKeyIds = allRecipients.map { it.encryptionKeyId },
        )
        val aadBytes = MessageCanonical.aadJson(aad).toByteArray(Charsets.UTF_8)
        val plaintext = EidoMessagePayloadCanonical
            .json(EidoMessagePayloadV1(document, caption))
            .toByteArray(Charsets.UTF_8)

        val messageKey = JcaCrypto.randomBytes(CryptoSuiteV1.AES_KEY_BYTES, random)
        val nonce = JcaCrypto.randomBytes(CryptoSuiteV1.GCM_NONCE_BYTES, random)
        val ciphertext = JcaCrypto.encryptAesGcm(messageKey, nonce, aadBytes, plaintext)

        val boxes = allRecipients.map { r ->
            RecipientKeyBoxV1(
                userId = r.userId,
                deviceId = r.deviceId,
                encryptionKeyId = r.encryptionKeyId,
                wrappedKeyB64 = B64Url.encode(
                    JcaCrypto.wrapKey(PublicKeyCodec.rsa(r.encryptionPublicKeyB64), messageKey, random)
                ),
            )
        }

        val body = EncryptedMessageBodyV1(
            aad = aad,
            nonceB64 = B64Url.encode(nonce),
            ciphertextB64 = B64Url.encode(ciphertext),
            recipientBoxes = boxes,
        )
        val bodyBytes = MessageCanonical.bodyJson(body).toByteArray(Charsets.UTF_8)
        val messageId = HexSha256.of(bodyBytes)
        val sig = sender.signMessage(bodyBytes)
        return EidogramMessageV1(
            body = body,
            messageId = messageId,
            signature = MessageSignatureV1(CryptoSuiteV1.SIGNATURE, B64Url.encode(sig)),
        )
    }

    fun verify(
        message: EidogramMessageV1,
        senderUser: UserIdentityV1,
        senderDevice: DeviceCertificateV1,
        expectedConversation: ConversationDescriptorV1? = null,
    ): Boolean {
        if (!IdentityVerifier.verifyDevice(senderUser, senderDevice)) return false
        if (message.body.aad.senderUserId != senderUser.userId) return false
        if (message.body.aad.senderDeviceId != senderDevice.deviceId) return false
        if (message.body.aad.senderSigningKeyId != senderDevice.body.signingKeyId) return false
        if (message.signature.algorithm != CryptoSuiteV1.SIGNATURE) return false
        if (expectedConversation != null) {
            if (message.body.aad.conversationId != expectedConversation.conversationId) return false
            if (senderUser.userId !in expectedConversation.participantUserIds) return false
            if (message.body.recipientBoxes.any { it.userId !in expectedConversation.participantUserIds }) return false
        }
        val body = MessageCanonical.bodyJson(message.body)
        if (HexSha256.ofUtf8(body) != message.messageId) return false
        if (message.body.recipientBoxes.map { it.encryptionKeyId } != message.body.aad.recipientEncryptionKeyIds) return false
        if (message.body.recipientBoxes != message.body.recipientBoxes.sortedBy { it.encryptionKeyId }) return false
        if (message.body.aad.parentMessageIds != message.body.aad.parentMessageIds.sorted().distinct()) return false
        return runCatching {
            JcaCrypto.verify(
                PublicKeyCodec.ec(senderDevice.body.signingPublicKeyB64),
                body.toByteArray(Charsets.UTF_8),
                B64Url.decode(message.signature.valueB64),
            )
        }.getOrDefault(false)
    }

    fun open(
        message: EidogramMessageV1,
        senderUser: UserIdentityV1,
        senderDevice: DeviceCertificateV1,
        recipient: DevicePrivateCrypto,
        conversation: ConversationDescriptorV1,
    ): EidogramDocumentV1 =
        openPayload(message, senderUser, senderDevice, recipient, conversation).document

    /** Same as [open] but keeps the caption. Pre-R22.2 messages yield an empty one. */
    fun openPayload(
        message: EidogramMessageV1,
        senderUser: UserIdentityV1,
        senderDevice: DeviceCertificateV1,
        recipient: DevicePrivateCrypto,
        conversation: ConversationDescriptorV1,
    ): EidoMessagePayloadV1 {
        require(verify(message, senderUser, senderDevice, conversation)) { "Message authentication failed" }
        require(recipient.user.userId in conversation.participantUserIds) { "Recipient is not a conversation participant" }
        val keyId = recipient.certificate.body.encryptionKeyId
        val box = message.body.recipientBoxes.singleOrNull { it.encryptionKeyId == keyId }
            ?: error("No recipient key box for this device")
        require(box.deviceId == recipient.certificate.deviceId)
        require(box.userId == recipient.user.userId)

        val key = recipient.unwrapMessageKey(B64Url.decode(box.wrappedKeyB64))
        try {
            val plaintext = JcaCrypto.decryptAesGcm(
                key,
                B64Url.decode(message.body.nonceB64),
                MessageCanonical.aadJson(message.body.aad).toByteArray(Charsets.UTF_8),
                B64Url.decode(message.body.ciphertextB64),
            )
            return EidoMessagePayloadCanonical.parseCanonical(plaintext.toString(Charsets.UTF_8))
        } finally {
            key.fill(0)
        }
    }
}
