package org.eidolang.core.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.eidolang.core.crypto.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Separate at-rest key domain from R20.1 active ratchet/prekey state.
 *
 * This store makes recovery convenient on one Android installation. Exporting the recovery
 * secret to an independent/offline medium remains necessary for recovery after loss of the
 * AndroidKeyStore itself.
 */
class AndroidHistoryVaultSecretStore(private val context:Context){
    private val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}

    fun save(vaultId:String,secret:HistoryVaultRecoverySecret){
        require(vaultId.matches(Regex("[0-9a-f]{64}")))
        val raw=secret.copyBytes()
        try{
            val cipher=Cipher.getInstance(CryptoSuiteV1.AES_JCA)
            cipher.init(Cipher.ENCRYPT_MODE,key())
            cipher.updateAAD(vaultId.toByteArray(Charsets.UTF_8))
            val payload=B64Url.encode(cipher.iv)+"."+B64Url.encode(cipher.doFinal(raw))
            atomicWrite(file(vaultId),payload)
        }finally{raw.fill(0)}
    }

    fun load(vaultId:String):HistoryVaultRecoverySecret?{
        val f=file(vaultId);if(!f.exists())return null
        val parts=f.readText(Charsets.US_ASCII).split(".")
        require(parts.size==2)
        val cipher=Cipher.getInstance(CryptoSuiteV1.AES_JCA)
        cipher.init(
            Cipher.DECRYPT_MODE,key(),
            GCMParameterSpec(CryptoSuiteV1.GCM_TAG_BITS,B64Url.decode(parts[0]))
        )
        cipher.updateAAD(vaultId.toByteArray(Charsets.UTF_8))
        val raw=cipher.doFinal(B64Url.decode(parts[1]))
        return try{HistoryVaultRecoverySecret.fromBytes(raw)}finally{raw.fill(0)}
    }

    fun delete(vaultId:String){file(vaultId).delete()}

    private fun key():SecretKey{
        (ks.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run{
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private fun file(vaultId:String)=context.filesDir.resolve("history-vault-secret-$vaultId.bin")

    private fun atomicWrite(f:java.io.File,text:String){
        val tmp=context.filesDir.resolve(f.name+".tmp")
        tmp.writeText(text,Charsets.US_ASCII)
        if(!tmp.renameTo(f)){
            f.writeText(tmp.readText(Charsets.US_ASCII),Charsets.US_ASCII)
            tmp.delete()
        }
    }

    companion object{
        const val ALIAS="eidolang.history-vault-secret-wrap.v1"
    }
}
