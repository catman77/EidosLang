package org.eidolang.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.security.keystore.KeyGenParameterSpec
import android.util.AtomicFile
import org.eidolang.core.canonical.*
import java.security.KeyPair
import java.security.KeyStore
import java.security.cert.Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Runtime keys stay in AndroidKeyStore; a sealed recovery copy is created BEFORE key import. */
class AndroidRecoverableKeyStore(private val context: Context) {
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val file get() = context.filesDir.resolve("eidolang-recoverable-keys-v1.bin")

    @Synchronized fun createPrimary() {
        val aliases = primaryAliases()
        val saved = read()
        if (aliases.all { it in saved }) { install(aliases.associateWith { saved.getValue(it) }); return }
        check(aliases.none(ks::containsAlias)) { "Нельзя заменять существующие ключи профиля" }
        install(mapOf(aliases[0] to JcaCrypto.p256(), aliases[1] to JcaCrypto.p256(), aliases[2] to JcaCrypto.rsa2048()))
    }

    @Synchronized fun createDevice(signAlias: String, encAlias: String) {
        val saved = read()
        check(!ks.containsAlias(signAlias) && !ks.containsAlias(encAlias))
        install(if (signAlias in saved && encAlias in saved)
            mapOf(signAlias to saved.getValue(signAlias), encAlias to saved.getValue(encAlias))
        else mapOf(signAlias to JcaCrypto.p256(), encAlias to JcaCrypto.rsa2048()))
    }

    fun repairIfSaved(aliases: List<String>): Boolean {
        if (!file.exists()) return false
        val saved = read()
        if (!aliases.all { it in saved }) return false
        install(aliases.associateWith { saved.getValue(it) })
        return true
    }

    fun export(bundle: String, signAlias: String, encAlias: String, rootAlias: String? = null): PortableIdentity? {
        val saved = read()
        val sign = saved[signAlias] ?: return null
        val enc = saved[encAlias] ?: return null
        if (rootAlias != null && rootAlias !in saved) return null
        return PortableIdentity(bundle, B64Url.encode(sign.private.encoded), B64Url.encode(enc.private.encoded),
            rootAlias?.let { B64Url.encode(saved.getValue(it).private.encoded) }).also { it.validate() }
    }

    @Synchronized fun install(pairs: Map<String, KeyPair>) {
        val saved = read().toMutableMap()
        pairs.forEach { (alias, pair) ->
            check(!ks.containsAlias(alias) || ks.getCertificate(alias).publicKey.encoded.contentEquals(pair.public.encoded)) {
                "Ключ восстановления не соответствует существующему профилю"
            }
            saved[alias]?.let { check(it.public.encoded.contentEquals(pair.public.encoded)) }
            saved[alias] = pair
        }
        write(saved)
        val signer = pairs.values.first { it.public.algorithm == "EC" }
        pairs.forEach { (alias, pair) ->
            if (!ks.containsAlias(alias)) {
                val protection = if (pair.public.algorithm == "EC")
                    KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).build()
                else KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .also { if (Build.VERSION.SDK_INT >= 35) it.setMgf1Digests(KeyProperties.DIGEST_SHA1) }.build()
                ks.setEntry(alias, KeyStore.PrivateKeyEntry(pair.private,
                    arrayOf<Certificate>(LocalKeyCertificate.create(pair.public, signer))), protection)
            }
        }
    }

    private fun read(): Map<String, KeyPair> {
        if (!file.exists()) return emptyMap()
        val key = ks.getKey(WRAP_ALIAS, null) as? SecretKey ?: error("Ключ локальной копии профиля недоступен")
        val p = file.readText(Charsets.US_ASCII).split('.')
        require(p.size == 3 && p[0] == "v1")
        val cipher = Cipher.getInstance(CryptoSuiteV1.AES_JCA)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, B64Url.decode(p[1])))
        cipher.updateAAD(AAD)
        val raw = cipher.doFinal(B64Url.decode(p[2]))
        return try {
            val obj = StrictJsonParser(raw.toString(Charsets.UTF_8)).parse() as JValue.Obj
            obj.fields.mapValues { (_, value) ->
                val fields = (value as JValue.Obj).fields
                fun str(name: String) = (fields.getValue(name) as JValue.Str).value
                if (str("algorithm") == "EC") KeyPair(PublicKeyCodec.ec(str("public")), PublicKeyCodec.privateEc(str("private")))
                else { require(str("algorithm") == "RSA"); KeyPair(PublicKeyCodec.rsa(str("public")), PublicKeyCodec.privateRsa(str("private"))) }
            }
        } finally { raw.fill(0) }
    }

    private fun write(pairs: Map<String, KeyPair>) {
        val raw = CanonicalJson.obj(pairs.mapValues { (_, pair) -> CanonicalJson.obj(mapOf(
            "algorithm" to CanonicalJson.string(pair.public.algorithm),
            "private" to CanonicalJson.string(B64Url.encode(pair.private.encoded)),
            "public" to CanonicalJson.string(B64Url.encode(pair.public.encoded)),
        )) }).toByteArray(Charsets.UTF_8)
        val key = (ks.getKey(WRAP_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()); generateKey()
        }
        try {
            val cipher = Cipher.getInstance(CryptoSuiteV1.AES_JCA)
            cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(AAD)
            val encoded = "v1." + B64Url.encode(cipher.iv) + "." + B64Url.encode(cipher.doFinal(raw))
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(encoded.toByteArray(Charsets.US_ASCII)); atomic.finishWrite(output) }
            catch (t: Throwable) { atomic.failWrite(output); throw t }
        } finally { raw.fill(0) }
    }

    companion object {
        const val WRAP_ALIAS = "eidolang.recoverable-identity-wrap.v1"
        private val AAD = "EIDOLANG-RECOVERABLE-KEYS-V1".toByteArray(Charsets.US_ASCII)
        fun primaryAliases() = listOf(AndroidKeystoreIdentityStore.ROOT_ALIAS,
            AndroidKeystoreIdentityStore.DEVICE_SIGN_ALIAS, AndroidKeystoreIdentityStore.DEVICE_ENC_ALIAS)
    }
}
