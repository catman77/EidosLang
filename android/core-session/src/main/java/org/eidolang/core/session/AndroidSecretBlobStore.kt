package org.eidolang.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.eidolang.core.crypto.B64Url
import org.eidolang.core.crypto.CryptoSuiteV1
import org.eidolang.core.crypto.JcaCrypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * minSdk-26 persistence adapter for software-generated session/prekey secret state.
 *
 * AndroidKeyStore ECDH PURPOSE_AGREE_KEY itself requires API 31, while the wire protocol is
 * deliberately API-independent. This store encrypts opaque canonical secret-state bytes with
 * an AES-GCM key held by AndroidKeyStore.
 */
class AndroidSecretBlobStore(private val context:Context){
    private val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}

    fun put(name:String,plaintext:ByteArray){
        require(name.matches(Regex("[A-Za-z0-9._-]{1,120}")))
        val key=ensureKey()
        val cipher=Cipher.getInstance(CryptoSuiteV1.AES_JCA)
        cipher.init(Cipher.ENCRYPT_MODE,key)
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val nonce=cipher.iv
        val ct=cipher.doFinal(plaintext)
        val body=B64Url.encode(nonce)+"."+B64Url.encode(ct)
        atomicWrite(file(name),body)
    }

    fun get(name:String):ByteArray?{
        val f=file(name);if(!f.exists())return null
        val parts=f.readText(Charsets.US_ASCII).split(".")
        require(parts.size==2)
        val nonce=B64Url.decode(parts[0]); val ct=B64Url.decode(parts[1])
        val cipher=Cipher.getInstance(CryptoSuiteV1.AES_JCA)
        cipher.init(Cipher.DECRYPT_MODE,ensureKey(),GCMParameterSpec(CryptoSuiteV1.GCM_TAG_BITS,nonce))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ct)
    }

    fun delete(name:String){file(name).delete()}

    private fun ensureKey():SecretKey{
        (ks.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run{
            init(
                KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private fun file(name:String)=context.filesDir.resolve("fs-secret-$name.bin")
    private fun atomicWrite(f:java.io.File,text:String){
        val tmp=context.filesDir.resolve(f.name+".tmp")
        tmp.writeText(text,Charsets.US_ASCII)
        if(!tmp.renameTo(f)){
            f.writeText(tmp.readText(Charsets.US_ASCII),Charsets.US_ASCII);tmp.delete()
        }
    }

    companion object{
        const val ALIAS="eidolang.forward-session-state-wrap.v1"
    }
}

class AndroidSessionStateStore(private val blobs:AndroidSecretBlobStore){
    fun save(session:DoubleRatchetSession){
        val raw=SessionStateCanonical.json(session.exportSecretSnapshot()).toByteArray(Charsets.UTF_8)
        try{blobs.put("session-"+session.sessionId,raw)}finally{raw.fill(0)}
    }

    fun load(sessionId:String):DoubleRatchetSession?{
        val raw=blobs.get("session-"+sessionId)?:return null
        return try{
            val snapshot=SessionStateCanonical.parseCanonical(raw.toString(Charsets.UTF_8))
            require(snapshot.sessionId==sessionId){"Session-state blob substitution detected"}
            DoubleRatchetSession.restore(snapshot)
        }finally{raw.fill(0)}
    }

    fun delete(sessionId:String)=blobs.delete("session-"+sessionId)
}

class AndroidPreKeyStateStore(private val blobs:AndroidSecretBlobStore){
    fun save(store:JvmPreKeyStore){
        val raw=PreKeyStateCanonical.json(store.exportSecretSnapshot()).toByteArray(Charsets.UTF_8)
        try{blobs.put("prekeys-v1",raw)}finally{raw.fill(0)}
    }

    fun load(identity:org.eidolang.core.crypto.DevicePrivateCrypto):JvmPreKeyStore?{
        val raw=blobs.get("prekeys-v1")?:return null
        return try{
            JvmPreKeyStore.restore(
                identity,
                PreKeyStateCanonical.parseCanonical(raw.toString(Charsets.UTF_8))
            )
        }finally{raw.fill(0)}
    }

    fun delete()=blobs.delete("prekeys-v1")
}

