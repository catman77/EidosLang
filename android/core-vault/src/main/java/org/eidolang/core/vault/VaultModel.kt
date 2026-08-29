package org.eidolang.core.vault

import org.eidolang.core.recovery.ContactPresentationV1
import org.eidolang.core.recovery.ConversationPresentationV1

data class HistoryVaultDescriptorV1(
    val ownerUserId: String,
    val seedB64: String,
    val vaultId: String,
)

class HistoryVaultRecoverySecret private constructor(
    private val bytes: ByteArray,
) {
    fun copyBytes(): ByteArray = bytes.copyOf()
    val recoveryKeyId: String
        get() = "hvr:" + org.eidolang.core.crypto.HexSha256.of(bytes)

    companion object {
        fun generate(random: java.security.SecureRandom = java.security.SecureRandom()): HistoryVaultRecoverySecret =
            HistoryVaultRecoverySecret(org.eidolang.core.crypto.JcaCrypto.randomBytes(32, random))

        fun fromBytes(bytes: ByteArray): HistoryVaultRecoverySecret {
            require(bytes.size == 32)
            return HistoryVaultRecoverySecret(bytes.copyOf())
        }
    }
}

data class RecoverySecretExportV1(
    val vaultId: String,
    val recoveryKeyId: String,
    val secretB64: String,
    val exportId: String,
)

data class HistoryVaultEpochBodyV1(
    val vaultId: String,
    val epoch: Int,
    val previousEpochId: String?,
    val activeDeviceIds: List<String>,
    val recoveryKeyId: String,
    val recoveryNonceB64: String,
    val recoveryCiphertextB64: String,
)

data class HistoryVaultEpochV1(
    val body: HistoryVaultEpochBodyV1,
    val epochId: String,
)

data class HistoryVaultEntryPayloadV1(
    val messageEnvelopeCanonicalJson: String,
    val documentCanonicalJson: String,
    val documentContentHash: String,
)

data class HistoryVaultEntryBodyV1(
    val vaultId: String,
    val epoch: Int,
    val epochId: String,
    val messageId: String,
    val conversationId: String,
    val nonceB64: String,
    val ciphertextB64: String,
)

data class HistoryVaultEntryV1(
    val body: HistoryVaultEntryBodyV1,
    val entryId: String,
)

data class HistoryVaultDeviceGrantBodyV1(
    val vaultId: String,
    val epoch: Int,
    val epochId: String,
    val ownerUserId: String,
    val grantorDeviceId: String,
    val grantorSigningKeyId: String,
    val targetDeviceId: String,
    val targetEncryptionKeyId: String,
    val wrappedEpochKeyB64: String,
)

data class HistoryVaultDeviceGrantV1(
    val body: HistoryVaultDeviceGrantBodyV1,
    val grantId: String,
    val signatureB64: String,
)

data class HistoryVaultArchivePackageV1(
    val ownerUserId: String,
    val vaultDescriptorCanonicalJson: String,
    val exporterIdentityBundleCanonicalJson: String,
    val ownDeviceBundleCanonicalJson: List<String>,
    val deviceRosterCanonicalJson: List<String>,
    val contacts: List<ContactPresentationV1>,
    val conversations: List<ConversationPresentationV1>,
    val epochCanonicalJson: List<String>,
    val entryCanonicalJson: List<String>,
    val packageId: String,
    val exporterSignatureB64: String,
)

data class HistoryVaultRestoreReport(
    val contactDeviceCount: Int,
    val ownDeviceCount: Int,
    val conversationCount: Int,
    val messageCount: Int,
    val epochCount: Int,
)

data class HistoryVaultRecoveredState(
    val report: HistoryVaultRestoreReport,
    val documentProvider: org.eidolang.core.repository.HistoricalDocumentProvider,
)
