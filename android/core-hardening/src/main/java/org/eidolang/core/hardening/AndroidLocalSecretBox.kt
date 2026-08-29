package org.eidolang.core.hardening

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
 * Small AndroidKeyStore-backed primitive for local files that are plaintext in pre-R21 builds
 * (drafts, rollback anchors and cutover receipts).
 *
 * The namespace + logical id are authenticated as AAD, so a ciphertext from one local object
 * cannot be substituted for another.
 */
class AndroidLocalSecretBox(private val context:Context){
    private val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}

    fun seal(namespace:String,logicalId:String,plaintext:ByteArray):String{
        validate(namespace,logicalId)
        val cipher=Cipher.getInstance(CryptoSuiteV1.AES_JCA)
        cipher.init(Cipher.ENCRYPT_MODE,key())
        cipher.updateAAD(aad(namespace,logicalId))
        return "v1."+B64Url.encode(cipher.iv)+"."+B64Url.encode(cipher.doFinal(plaintext))
    }

    fun open(namespace:String,logicalId:String,encoded:String):ByteArray{
        validate(namespace,logicalId)
        val p=encoded.split(".")
        require(p.size==3 && p[0]=="v1"){"Unsupported local secret-box format"}
        val nonce=B64Url.decode(p[1])
        val cipher=Cipher.getInstance(CryptoSuiteV1.AES_JCA)
        cipher.init(
            Cipher.DECRYPT_MODE,key(),
            GCMParameterSpec(CryptoSuiteV1.GCM_TAG_BITS,nonce)
        )
        cipher.updateAAD(aad(namespace,logicalId))
        return cipher.doFinal(B64Url.decode(p[2]))
    }

    private fun aad(namespace:String,logicalId:String)=
        "EIDOLANG-R21-LOCAL|$namespace|$logicalId".toByteArray(Charsets.UTF_8)

    private fun validate(namespace:String,logicalId:String){
        require(namespace.matches(Regex("[A-Za-z0-9._-]{1,60}")))
        require(logicalId.matches(Regex("[A-Za-z0-9._-]{1,160}")))
    }

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

    companion object{
        const val ALIAS="eidolang.local-sensitive-wrap.v1"
    }
}

class AndroidVaultRollbackAnchorStore(private val context:Context){
    private val box=AndroidLocalSecretBox(context)

    fun save(anchor:VaultRollbackAnchorV1){
        val raw=VaultRollbackAnchorCodec.json(anchor).toByteArray(Charsets.UTF_8)
        try{
            atomicWrite(file(anchor.vaultId),box.seal("vault-anchor",anchor.vaultId,raw))
        }finally{raw.fill(0)}
    }

    fun load(vaultId:String):VaultRollbackAnchorV1?{
        val f=file(vaultId);if(!f.exists())return null
        val raw=box.open("vault-anchor",vaultId,f.readText(Charsets.US_ASCII))
        return try{
            val a=VaultRollbackAnchorCodec.parseCanonical(raw.toString(Charsets.UTF_8))
            require(a.vaultId==vaultId){"Rollback-anchor file substitution"}
            a
        }finally{raw.fill(0)}
    }

    fun delete(vaultId:String){file(vaultId).delete()}

    private fun file(vaultId:String)=context.filesDir.resolve("vault-anchor-$vaultId.bin")
    private fun atomicWrite(f:java.io.File,text:String){
        val tmp=context.filesDir.resolve(f.name+".tmp")
        tmp.writeText(text,Charsets.US_ASCII)
        if(!tmp.renameTo(f)){
            f.writeText(tmp.readText(Charsets.US_ASCII),Charsets.US_ASCII);tmp.delete()
        }
    }
}

class AndroidLegacyCutoverReceiptStore(private val context:Context){
    private val box=AndroidLocalSecretBox(context)

    fun save(receipt:LegacyCutoverReceiptV1){
        val raw=LegacyCutoverCodec.json(receipt).toByteArray(Charsets.UTF_8)
        try{
            atomicWrite(
                file(receipt.body.vaultId),
                box.seal("legacy-cutover",receipt.body.vaultId,raw)
            )
        }finally{raw.fill(0)}
    }

    fun load(vaultId:String):LegacyCutoverReceiptV1?{
        val f=file(vaultId);if(!f.exists())return null
        val raw=box.open("legacy-cutover",vaultId,f.readText(Charsets.US_ASCII))
        return try{
            val r=LegacyCutoverParser.parseCanonical(raw.toString(Charsets.UTF_8))
            require(r.body.vaultId==vaultId)
            r
        }finally{raw.fill(0)}
    }

    private fun file(vaultId:String)=context.filesDir.resolve("legacy-cutover-$vaultId.bin")
    private fun atomicWrite(f:java.io.File,text:String){
        val tmp=context.filesDir.resolve(f.name+".tmp")
        tmp.writeText(text,Charsets.US_ASCII)
        if(!tmp.renameTo(f)){
            f.writeText(tmp.readText(Charsets.US_ASCII),Charsets.US_ASCII);tmp.delete()
        }
    }
}

