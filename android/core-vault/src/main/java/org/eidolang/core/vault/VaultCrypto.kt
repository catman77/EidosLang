package org.eidolang.core.vault

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.MessageCanonical
import org.eidolang.core.message.MessageParser
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.multidevice.DeviceRosterCanonical
import org.eidolang.core.multidevice.DeviceRosterVerifier
import org.eidolang.core.multidevice.UserDeviceRosterV1
import org.eidolang.core.session.HkdfSha256
import java.security.SecureRandom

data class OpenedVaultEpoch(
    val epoch: HistoryVaultEpochV1,
    val epochKey: ByteArray,
)

object HistoryVaultCrypto {
    private fun recoveryWrapKey(
        descriptor:HistoryVaultDescriptorV1,
        recoverySecret:HistoryVaultRecoverySecret,
    ):ByteArray{
        val raw=recoverySecret.copyBytes()
        return try{
            HkdfSha256.derive(
                salt=descriptor.vaultId.toByteArray(Charsets.UTF_8),
                ikm=raw,
                info="EIDOLANG-R20.2-HISTORY-RECOVERY-WRAP",
                length=32
            )
        }finally{raw.fill(0)}
    }

    fun createEpoch(
        descriptor:HistoryVaultDescriptorV1,
        recoverySecret:HistoryVaultRecoverySecret,
        previous:HistoryVaultEpochV1?,
        activeDeviceIds:Collection<String>,
        random:SecureRandom=SecureRandom(),
    ):OpenedVaultEpoch{
        val active=activeDeviceIds.toSortedSet().toList()
        require(active.isNotEmpty()){"History-vault epoch requires at least one active device"}
        active.forEach{require(it.startsWith("dev:"))}
        val epoch=(previous?.body?.epoch?:-1)+1
        if(previous==null) require(epoch==0)
        else{
            require(previous.body.vaultId==descriptor.vaultId)
            require(previous.body.epoch==epoch-1)
        }
        val epochKey=JcaCrypto.randomBytes(32,random)
        val nonce=JcaCrypto.randomBytes(CryptoSuiteV1.GCM_NONCE_BYTES,random)
        val aad=HistoryVaultEpochCodec.aadJson(
            descriptor.vaultId,epoch,previous?.epochId,active,recoverySecret.recoveryKeyId
        ).toByteArray(Charsets.UTF_8)
        val wrapKey=recoveryWrapKey(descriptor,recoverySecret)
        val ciphertext=try{
            JcaCrypto.encryptAesGcm(wrapKey,nonce,aad,epochKey)
        }finally{wrapKey.fill(0)}
        val body=HistoryVaultEpochBodyV1(
            descriptor.vaultId,epoch,previous?.epochId,active,recoverySecret.recoveryKeyId,
            B64Url.encode(nonce),B64Url.encode(ciphertext)
        )
        val e=HistoryVaultEpochV1(body,HexSha256.ofUtf8(HistoryVaultEpochCodec.bodyJson(body)))
        return OpenedVaultEpoch(e,epochKey)
    }

    fun openEpoch(
        descriptor:HistoryVaultDescriptorV1,
        recoverySecret:HistoryVaultRecoverySecret,
        epoch:HistoryVaultEpochV1,
    ):ByteArray{
        require(epoch.body.vaultId==descriptor.vaultId)
        require(epoch.body.recoveryKeyId==recoverySecret.recoveryKeyId){"Wrong recovery-secret identifier"}
        val aad=HistoryVaultEpochCodec.aadJson(
            descriptor.vaultId,epoch.body.epoch,epoch.body.previousEpochId,
            epoch.body.activeDeviceIds,epoch.body.recoveryKeyId
        ).toByteArray(Charsets.UTF_8)
        val wrapKey=recoveryWrapKey(descriptor,recoverySecret)
        return try{
            val key=JcaCrypto.decryptAesGcm(
                wrapKey,B64Url.decode(epoch.body.recoveryNonceB64),aad,
                B64Url.decode(epoch.body.recoveryCiphertextB64)
            )
            require(key.size==32)
            key
        }finally{wrapKey.fill(0)}
    }

    private fun entryKey(epochKey:ByteArray,entry:HistoryVaultEntryBodyV1):ByteArray =
        HkdfSha256.derive(
            salt=entry.messageId.toByteArray(Charsets.UTF_8),
            ikm=epochKey,
            info="EIDOLANG-R20.2-HISTORY-ENTRY|${entry.vaultId}|${entry.epochId}",
            length=32
        )

    fun sealEntry(
        descriptor:HistoryVaultDescriptorV1,
        openedEpoch:OpenedVaultEpoch,
        messageEnvelopeCanonicalJson:String,
        document:EidogramDocumentV1,
        random:SecureRandom=SecureRandom(),
    ):HistoryVaultEntryV1{
        val message=MessageParser.parseCanonical(messageEnvelopeCanonicalJson)
        require(openedEpoch.epoch.body.vaultId==descriptor.vaultId)
        val documentRaw=EidogramCanonical.documentJson(document)
        val payload=HistoryVaultEntryPayloadV1(
            messageEnvelopeCanonicalJson,
            documentRaw,
            EidogramCanonical.contentHash(document)
        )
        val nonce=JcaCrypto.randomBytes(CryptoSuiteV1.GCM_NONCE_BYTES,random)
        val provisional=HistoryVaultEntryBodyV1(
            descriptor.vaultId,openedEpoch.epoch.body.epoch,openedEpoch.epoch.epochId,
            message.messageId,message.body.aad.conversationId,B64Url.encode(nonce),""
        )
        val key=entryKey(openedEpoch.epochKey,provisional)
        val aad=HistoryVaultEntryCodec.aadJson(
            provisional.vaultId,provisional.epoch,provisional.epochId,
            provisional.messageId,provisional.conversationId
        ).toByteArray(Charsets.UTF_8)
        val ciphertext=try{
            JcaCrypto.encryptAesGcm(
                key,nonce,aad,HistoryVaultEntryCodec.payloadJson(payload).toByteArray(Charsets.UTF_8)
            )
        }finally{key.fill(0)}
        val body=provisional.copy(ciphertextB64=B64Url.encode(ciphertext))
        return HistoryVaultEntryV1(body,HexSha256.ofUtf8(HistoryVaultEntryCodec.bodyJson(body)))
    }

    fun openEntry(
        descriptor:HistoryVaultDescriptorV1,
        epochKey:ByteArray,
        entry:HistoryVaultEntryV1,
    ):HistoryVaultEntryPayloadV1{
        require(entry.body.vaultId==descriptor.vaultId)
        val key=entryKey(epochKey,entry.body)
        val aad=HistoryVaultEntryCodec.aadJson(
            entry.body.vaultId,entry.body.epoch,entry.body.epochId,
            entry.body.messageId,entry.body.conversationId
        ).toByteArray(Charsets.UTF_8)
        return try{
            val plaintext=JcaCrypto.decryptAesGcm(
                key,B64Url.decode(entry.body.nonceB64),aad,B64Url.decode(entry.body.ciphertextB64)
            )
            val payload=HistoryVaultEntryCodec.parsePayloadCanonical(plaintext.toString(Charsets.UTF_8))
            val message=MessageParser.parseCanonical(payload.messageEnvelopeCanonicalJson)
            require(message.messageId==entry.body.messageId)
            require(message.body.aad.conversationId==entry.body.conversationId)
            payload
        }finally{key.fill(0)}
    }

    fun verifyEpochChain(descriptor:HistoryVaultDescriptorV1,epochs:List<HistoryVaultEpochV1>){
        require(epochs==epochs.sortedBy{it.body.epoch})
        require(epochs.map{it.body.epoch}==epochs.indices.toList())
        epochs.forEachIndexed{i,e->
            require(e.body.vaultId==descriptor.vaultId)
            if(i==0) require(e.body.previousEpochId==null)
            else require(e.body.previousEpochId==epochs[i-1].epochId)
        }
    }

    fun verifyRosterCompatibility(
        epoch:HistoryVaultEpochV1,
        roster:UserDeviceRosterV1,
        user:UserIdentityV1,
    ){
        require(DeviceRosterVerifier.verifySignature(roster,user))
        require(epoch.body.activeDeviceIds.all{it in roster.body.activeDeviceIds}) {
            "Vault epoch grants access to a device outside the supplied active roster"
        }
    }
}

object HistoryVaultDeviceGrantCodec {
    fun bodyJson(b:HistoryVaultDeviceGrantBodyV1):String=org.eidolang.core.canonical.CanonicalJson.obj(mapOf(
        "epoch" to org.eidolang.core.canonical.CanonicalJson.int(b.epoch),
        "epoch_id" to org.eidolang.core.canonical.CanonicalJson.string(b.epochId),
        "grant_type" to org.eidolang.core.canonical.CanonicalJson.string("EidoHistoryVaultDeviceGrantV1"),
        "grant_version" to org.eidolang.core.canonical.CanonicalJson.string("1.0.0"),
        "grantor_device_id" to org.eidolang.core.canonical.CanonicalJson.string(b.grantorDeviceId),
        "grantor_signing_key_id" to org.eidolang.core.canonical.CanonicalJson.string(b.grantorSigningKeyId),
        "owner_user_id" to org.eidolang.core.canonical.CanonicalJson.string(b.ownerUserId),
        "target_device_id" to org.eidolang.core.canonical.CanonicalJson.string(b.targetDeviceId),
        "target_encryption_key_id" to org.eidolang.core.canonical.CanonicalJson.string(b.targetEncryptionKeyId),
        "vault_id" to org.eidolang.core.canonical.CanonicalJson.string(b.vaultId),
        "wrapped_epoch_key_b64" to org.eidolang.core.canonical.CanonicalJson.string(b.wrappedEpochKeyB64),
    ))

    fun json(g:HistoryVaultDeviceGrantV1):String=org.eidolang.core.canonical.CanonicalJson.obj(mapOf(
        "body" to bodyJson(g.body),
        "grant_id" to org.eidolang.core.canonical.CanonicalJson.string(g.grantId),
        "signature_b64" to org.eidolang.core.canonical.CanonicalJson.string(g.signatureB64),
    ))
}

object HistoryVaultDeviceGrantCrypto {
    fun issue(
        descriptor:HistoryVaultDescriptorV1,
        openedEpoch:OpenedVaultEpoch,
        grantor:DevicePrivateCrypto,
        target:PublicIdentityBundleV1,
        random:SecureRandom=SecureRandom(),
    ):HistoryVaultDeviceGrantV1{
        require(grantor.user.userId==descriptor.ownerUserId)
        require(target.user.userId==descriptor.ownerUserId)
        require(target.device.deviceId in openedEpoch.epoch.body.activeDeviceIds){"Target device not active in this vault epoch"}
        require(grantor.certificate.deviceId in openedEpoch.epoch.body.activeDeviceIds){"Grantor device not active in this vault epoch"}
        val wrapped=JcaCrypto.wrapKey(
            PublicKeyCodec.rsa(target.device.body.encryptionPublicKeyB64),openedEpoch.epochKey,random
        )
        val body=HistoryVaultDeviceGrantBodyV1(
            descriptor.vaultId,openedEpoch.epoch.body.epoch,openedEpoch.epoch.epochId,
            descriptor.ownerUserId,grantor.certificate.deviceId,grantor.certificate.body.signingKeyId,
            target.device.deviceId,target.device.body.encryptionKeyId,B64Url.encode(wrapped)
        )
        val bytes=HistoryVaultDeviceGrantCodec.bodyJson(body).toByteArray(Charsets.UTF_8)
        return HistoryVaultDeviceGrantV1(
            body,HexSha256.of(bytes),B64Url.encode(grantor.signMessage(bytes))
        )
    }

    fun verify(
        grant:HistoryVaultDeviceGrantV1,
        descriptor:HistoryVaultDescriptorV1,
        epoch:HistoryVaultEpochV1,
        grantor:PublicIdentityBundleV1,
        target:PublicIdentityBundleV1,
    ):Boolean=runCatching{
        if(!IdentityVerifier.verifyBundle(grantor)||!IdentityVerifier.verifyBundle(target)) return false
        if(grant.body.vaultId!=descriptor.vaultId||grant.body.epochId!=epoch.epochId||grant.body.epoch!=epoch.body.epoch)return false
        if(grant.body.ownerUserId!=descriptor.ownerUserId)return false
        if(grantor.user.userId!=descriptor.ownerUserId||target.user.userId!=descriptor.ownerUserId)return false
        if(grant.body.grantorDeviceId!=grantor.device.deviceId||grant.body.grantorSigningKeyId!=grantor.device.body.signingKeyId)return false
        if(grant.body.targetDeviceId!=target.device.deviceId||grant.body.targetEncryptionKeyId!=target.device.body.encryptionKeyId)return false
        if(target.device.deviceId !in epoch.body.activeDeviceIds||grantor.device.deviceId !in epoch.body.activeDeviceIds)return false
        val bytes=HistoryVaultDeviceGrantCodec.bodyJson(grant.body).toByteArray(Charsets.UTF_8)
        if(grant.grantId!=HexSha256.of(bytes))return false
        JcaCrypto.verify(PublicKeyCodec.ec(grantor.device.body.signingPublicKeyB64),bytes,B64Url.decode(grant.signatureB64))
    }.getOrDefault(false)

    fun open(
        grant:HistoryVaultDeviceGrantV1,
        descriptor:HistoryVaultDescriptorV1,
        epoch:HistoryVaultEpochV1,
        grantor:PublicIdentityBundleV1,
        target:DevicePrivateCrypto,
    ):ByteArray{
        val targetBundle=PublicIdentityBundleV1(target.user,target.certificate)
        require(verify(grant,descriptor,epoch,grantor,targetBundle))
        val key=target.unwrapMessageKey(B64Url.decode(grant.body.wrappedEpochKeyB64))
        require(key.size==32)
        return key
    }
}
