package org.eidolang.core.hardening

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import org.eidolang.core.vault.HistoryVaultRecoverySecret
import java.security.SecureRandom

data class RecoveryBackupBodyV1(
    val vaultId:String,
    val recoveryKeyId:String,
    val backupKeyId:String,
    val nonceB64:String,
    val ciphertextB64:String,
)

data class RecoveryBackupV1(
    val body:RecoveryBackupBodyV1,
    val backupId:String,
)

class RecoveryBackupCode private constructor(
    private val key:ByteArray,
) {
    val backupKeyId:String get()="hvbk:"+HexSha256.of(key)
    fun copyKey():ByteArray=key.copyOf()

    companion object{
        fun generate(random:SecureRandom=SecureRandom())=
            RecoveryBackupCode(JcaCrypto.randomBytes(32,random))
        fun fromBytes(bytes:ByteArray):RecoveryBackupCode{
            require(bytes.size==32)
            return RecoveryBackupCode(bytes.copyOf())
        }
    }
}

data class RecoveryBackupPair(
    val backup:RecoveryBackupV1,
    val recoveryCode:RecoveryBackupCode,
)

object RecoveryBackupCodec {
    fun aadJson(vaultId:String,recoveryKeyId:String,backupKeyId:String):String=
        CanonicalJson.obj(mapOf(
            "backup_key_id" to CanonicalJson.string(backupKeyId),
            "backup_type" to CanonicalJson.string("EidoHistoryVaultRecoveryBackupV1"),
            "backup_version" to CanonicalJson.string("1.0.0"),
            "recovery_key_id" to CanonicalJson.string(recoveryKeyId),
            "vault_id" to CanonicalJson.string(vaultId),
        ))

    fun bodyJson(b:RecoveryBackupBodyV1):String=CanonicalJson.obj(mapOf(
        "backup_key_id" to CanonicalJson.string(b.backupKeyId),
        "backup_type" to CanonicalJson.string("EidoHistoryVaultRecoveryBackupV1"),
        "backup_version" to CanonicalJson.string("1.0.0"),
        "ciphertext_b64" to CanonicalJson.string(b.ciphertextB64),
        "nonce_b64" to CanonicalJson.string(b.nonceB64),
        "recovery_key_id" to CanonicalJson.string(b.recoveryKeyId),
        "vault_id" to CanonicalJson.string(b.vaultId),
    ))

    fun json(b:RecoveryBackupV1):String=CanonicalJson.obj(mapOf(
        "backup_id" to CanonicalJson.string(b.backupId),
        "body" to bodyJson(b.body),
    ))

    fun parseCanonical(text:String):RecoveryBackupV1{
        val root=StrictJsonParser(text).parse().obj()
        root.requireKeys("backup_id","body")
        val b=root.obj("body")
        b.requireKeys(
            "backup_key_id","backup_type","backup_version","ciphertext_b64",
            "nonce_b64","recovery_key_id","vault_id"
        )
        require(b.str("backup_type")=="EidoHistoryVaultRecoveryBackupV1")
        require(b.str("backup_version")=="1.0.0")
        val out=RecoveryBackupV1(
            RecoveryBackupBodyV1(
                b.str("vault_id"),b.str("recovery_key_id"),b.str("backup_key_id"),
                b.str("nonce_b64"),b.str("ciphertext_b64")
            ),
            root.str("backup_id")
        )
        require(out.body.vaultId.matches(Regex("[0-9a-f]{64}")))
        require(out.body.recoveryKeyId.matches(Regex("hvr:[0-9a-f]{64}")))
        require(out.body.backupKeyId.matches(Regex("hvbk:[0-9a-f]{64}")))
        require(out.backupId==HexSha256.ofUtf8(bodyJson(out.body)))
        require(json(out)==text){"Recovery backup is not canonical"}
        return out
    }
}

object RecoveryBackupCrypto {
    fun create(
        vaultId:String,
        recoverySecret:HistoryVaultRecoverySecret,
        random:SecureRandom=SecureRandom(),
    ):RecoveryBackupPair{
        require(vaultId.matches(Regex("[0-9a-f]{64}")))
        val code=RecoveryBackupCode.generate(random)
        val key=code.copyKey()
        val secret=recoverySecret.copyBytes()
        try{
            val nonce=JcaCrypto.randomBytes(CryptoSuiteV1.GCM_NONCE_BYTES,random)
            val aad=RecoveryBackupCodec.aadJson(
                vaultId,recoverySecret.recoveryKeyId,code.backupKeyId
            ).toByteArray(Charsets.UTF_8)
            val ct=JcaCrypto.encryptAesGcm(key,nonce,aad,secret)
            val body=RecoveryBackupBodyV1(
                vaultId,recoverySecret.recoveryKeyId,code.backupKeyId,
                B64Url.encode(nonce),B64Url.encode(ct)
            )
            return RecoveryBackupPair(
                RecoveryBackupV1(body,HexSha256.ofUtf8(RecoveryBackupCodec.bodyJson(body))),
                code
            )
        }finally{
            key.fill(0);secret.fill(0)
        }
    }

    fun open(backup:RecoveryBackupV1,code:RecoveryBackupCode):HistoryVaultRecoverySecret{
        require(backup.body.backupKeyId==code.backupKeyId){"Wrong recovery backup code"}
        val key=code.copyKey()
        return try{
            val aad=RecoveryBackupCodec.aadJson(
                backup.body.vaultId,backup.body.recoveryKeyId,backup.body.backupKeyId
            ).toByteArray(Charsets.UTF_8)
            val raw=JcaCrypto.decryptAesGcm(
                key,B64Url.decode(backup.body.nonceB64),aad,B64Url.decode(backup.body.ciphertextB64)
            )
            try{
                val secret=HistoryVaultRecoverySecret.fromBytes(raw)
                require(secret.recoveryKeyId==backup.body.recoveryKeyId)
                secret
            }finally{raw.fill(0)}
        }finally{key.fill(0)}
    }
}

object RecoveryBackupCodeCodec {
    /**
     * This is the second secret channel, intended for QR/offline transfer.
     * It MUST NOT be stored next to the encrypted backup file.
     */
    fun encode(vaultId:String,code:RecoveryBackupCode):String{
        require(vaultId.matches(Regex("[0-9a-f]{64}")))
        val key=code.copyKey()
        return try{
            "eidolang-recovery-v1:$vaultId:${code.backupKeyId}:${B64Url.encode(key)}"
        }finally{key.fill(0)}
    }

    fun decode(text:String):Pair<String,RecoveryBackupCode>{
        val p=text.split(":")
        require(p.size==5 && p[0]=="eidolang-recovery-v1")
        val vaultId=p[1]
        val keyId="${p[2]}:${p[3]}"
        val code=RecoveryBackupCode.fromBytes(B64Url.decode(p[4]))
        require(vaultId.matches(Regex("[0-9a-f]{64}")))
        require(code.backupKeyId==keyId)
        return vaultId to code
    }
}

private fun JValue.obj():JValue.Obj=this as? JValue.Obj?:error("Expected object")
private fun JValue.str():String=(this as? JValue.Str)?.value?:error("Expected string")
private fun JValue.Obj.str(k:String):String=fields[k]?.str()?:error("Missing $k")
private fun JValue.Obj.obj(k:String):JValue.Obj=fields[k]?.obj()?:error("Missing $k")
private fun JValue.Obj.requireKeys(vararg keys:String){require(fields.keys==keys.toSet()){"Schema mismatch"}}
