package org.eidolang.core.admission

import android.content.Context
import android.os.Build
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.AndroidLocalSecretBox
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.vault.AndroidHistoryVaultSecretStore
import org.eidolang.core.vault.HistoryVaultRecoverySecret
import java.security.SecureRandom

data class AdmissionCheck(
    val id:String,
    val passed:Boolean,
    val detail:String,
)

data class AndroidAdmissionReport(
    val sdkInt:Int,
    val deviceId:String,
    val checks:List<AdmissionCheck>,
) {
    val passed:Boolean get()=checks.all{it.passed}
}

class AndroidAdmissionProbe(
    private val context:Context,
    private val random:SecureRandom=SecureRandom(),
) {
    fun run(
        identity:DevicePrivateCrypto,
        repository:AndroidSqliteMessengerRepository,
    ):AndroidAdmissionReport{
        val checks=mutableListOf<AdmissionCheck>()

        fun check(id:String,block:()->String){
            val result=runCatching(block)
            checks += AdmissionCheck(
                id,result.isSuccess,
                result.fold({it},{it.message ?: it::class.java.simpleName})
            )
        }

        val publicBundle=PublicIdentityBundleV1(identity.user,identity.certificate)

        check("identity.bundle"){
            require(IdentityVerifier.verifyBundle(publicBundle))
            "root/device certificate chain verifies"
        }

        check("identity.sign"){
            val bytes="R22-ANDROID-ADMISSION".toByteArray(Charsets.UTF_8)
            val sig=identity.signMessage(bytes)
            require(
                JcaCrypto.verify(
                    PublicKeyCodec.ec(identity.certificate.body.signingPublicKeyB64),
                    bytes,sig
                )
            )
            "device signing key sign/verify round-trip"
        }

        check("identity.rsa_oaep"){
            val key=JcaCrypto.randomBytes(32,random)
            try{
                val wrapped=JcaCrypto.wrapKey(
                    PublicKeyCodec.rsa(identity.certificate.body.encryptionPublicKeyB64),
                    key,random
                )
                val opened=identity.unwrapMessageKey(wrapped)
                try{require(opened.contentEquals(key))}
                finally{opened.fill(0)}
            }finally{key.fill(0)}
            "device RSA-OAEP wrap/unwrap round-trip"
        }

        check("local.secret_box"){
            val box=AndroidLocalSecretBox(context)
            val logical="probe-"+System.nanoTime()
            val raw=JcaCrypto.randomBytes(32,random)
            try{
                val sealed=box.seal("admission",logical,raw)
                val opened=box.open("admission",logical,sealed)
                try{require(opened.contentEquals(raw))}
                finally{opened.fill(0)}
                require(runCatching{box.open("admission",logical+"x",sealed)}.isFailure)
            }finally{raw.fill(0)}
            "AndroidKeyStore AES-GCM local box + AAD substitution rejection"
        }

        check("vault.secret_store"){
            val store=AndroidHistoryVaultSecretStore(context)
            val secret=HistoryVaultRecoverySecret.generate(random)
            val vaultId=HexSha256.of(JcaCrypto.randomBytes(32,random))
            store.save(vaultId,secret)
            val opened=store.load(vaultId) ?: error("vault secret missing after save")
            try{require(opened.recoveryKeyId==secret.recoveryKeyId)}
            finally{store.delete(vaultId)}
            "separate history-vault secret store round-trip"
        }

        check("repository.sensitive_text"){
            val audit=repository.sensitiveTextAudit()
            require(audit.pass){
                "plaintext rows remain: aliases=${audit.contactRows-audit.protectedContactAliases}, " +
                    "titles=${audit.conversationRows-audit.protectedConversationTitles}"
            }
            "all stored contact aliases and conversation titles use v3 protected representation"
        }

        return AndroidAdmissionReport(
            Build.VERSION.SDK_INT,
            identity.certificate.deviceId,
            checks
        )
    }
}
