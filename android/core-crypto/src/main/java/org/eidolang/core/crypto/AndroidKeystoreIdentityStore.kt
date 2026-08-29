package org.eidolang.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher

/**
 * Production Android private-key holder.
 *
 * Private key material remains non-exportable in AndroidKeyStore. Public identity
 * objects are reproducible from the stable aliases on every process start.
 */
class AndroidKeystoreIdentityStore(
    private val context: Context,
) {
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(): Boolean =
        ks.containsAlias(ROOT_ALIAS) &&
            ks.containsAlias(DEVICE_SIGN_ALIAS) &&
            ks.containsAlias(DEVICE_ENC_ALIAS)

    fun loadIfPresent(): AndroidPrivateIdentity? =
        if (exists()) ensure() else null

    fun ensure(): AndroidPrivateIdentity {
        ensureEcSigning(ROOT_ALIAS)
        ensureEcSigning(DEVICE_SIGN_ALIAS)
        ensureRsaDecrypt(DEVICE_ENC_ALIAS)

        val rootPublic = ks.getCertificate(ROOT_ALIAS).publicKey
        val deviceSignPublic = ks.getCertificate(DEVICE_SIGN_ALIAS).publicKey
        val deviceEncPublic = ks.getCertificate(DEVICE_ENC_ALIAS).publicKey
        val user = IdentityFactory.user(rootPublic)
        val body = IdentityFactory.deviceBody(user, deviceSignPublic, deviceEncPublic)
        val bodyBytes = IdentityCanonical.deviceBodyJson(body).toByteArray(Charsets.UTF_8)
        val bodyHash = HexSha256.of(bodyBytes)
        val deviceId = "dev:" + bodyHash
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedSigB64 = prefs.getString(PREF_ROOT_SIG, null)
        val cachedBodyHash = prefs.getString(PREF_BODY_HASH, null)
        val reusable = cachedBodyHash == bodyHash && cachedSigB64 != null && runCatching {
            JcaCrypto.verify(rootPublic, bodyBytes, B64Url.decode(cachedSigB64))
        }.getOrDefault(false)
        val rootSigB64 = if (reusable) {
            cachedSigB64!!
        } else {
            val created = Signature.getInstance(CryptoSuiteV1.SIGNATURE_JCA).run {
                initSign(ks.getKey(ROOT_ALIAS, null) as PrivateKey)
                update(bodyBytes)
                B64Url.encode(sign())
            }
            prefs.edit().putString(PREF_BODY_HASH, bodyHash).putString(PREF_ROOT_SIG, created).apply()
            created
        }
        val cert = DeviceCertificateV1(
            body = body,
            deviceId = deviceId,
            rootSignatureB64 = rootSigB64,
        )
        check(IdentityVerifier.verifyDevice(user, cert))
        return AndroidPrivateIdentity(ks, user, cert)
    }

    private fun ensureEcSigning(alias: String) {
        if (ks.containsAlias(alias)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generateKeyPair()
        }
    }

    private fun ensureRsaDecrypt(alias: String) {
        if (ks.containsAlias(alias)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .also { builder ->
                        if (Build.VERSION.SDK_INT >= 35) {
                            builder.setMgf1Digests(KeyProperties.DIGEST_SHA1)
                        }
                    }
                    .build()
            )
            generateKeyPair()
        }
    }

    companion object {
        const val ROOT_ALIAS = "eidolang.root-signing.v1"
        const val DEVICE_SIGN_ALIAS = "eidolang.device-signing.v1"
        const val DEVICE_ENC_ALIAS = "eidolang.device-decrypt.v1"
        private const val PREFS_NAME = "eidolang-public-identity-v1"
        private const val PREF_BODY_HASH = "device_certificate_body_hash"
        private const val PREF_ROOT_SIG = "device_certificate_root_signature"
    }
}

class AndroidPrivateIdentity(
    private val ks: KeyStore,
    override val user: UserIdentityV1,
    override val certificate: DeviceCertificateV1,
) : DevicePrivateCrypto {
    override fun signMessage(bytes: ByteArray): ByteArray =
        Signature.getInstance(CryptoSuiteV1.SIGNATURE_JCA).run {
            initSign(ks.getKey(AndroidKeystoreIdentityStore.DEVICE_SIGN_ALIAS, null) as PrivateKey)
            update(bytes)
            sign()
        }

    override fun unwrapMessageKey(wrapped: ByteArray): ByteArray {
        val privateKey = ks.getKey(AndroidKeystoreIdentityStore.DEVICE_ENC_ALIAS, null) as PrivateKey
        val cipher = Cipher.getInstance(CryptoSuiteV1.RSA_JCA)
        // Pre-API 35 AndroidKeyStore uses SHA-1 as the MGF1 default for RSA-OAEP.
        // Try the fully explicit suite first; default initialization is the compatible fallback.
        runCatching { cipher.init(Cipher.DECRYPT_MODE, privateKey, JcaCrypto.oaepSpec) }
            .getOrElse { cipher.init(Cipher.DECRYPT_MODE, privateKey) }
        return cipher.doFinal(wrapped)
    }
}
