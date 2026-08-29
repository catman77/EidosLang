package org.eidolang.core.multidevice

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.eidolang.core.crypto.*
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher

/**
 * Root-authority adapter for the primary Android identity created by
 * AndroidKeystoreIdentityStore. The root private key remains non-exportable.
 */
class AndroidRootAuthority : RootAuthority {
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    override val user: UserIdentityV1

    init {
        require(ks.containsAlias(AndroidKeystoreIdentityStore.ROOT_ALIAS)) {
            "Primary root identity is not present in AndroidKeyStore"
        }
        user = IdentityFactory.user(ks.getCertificate(AndroidKeystoreIdentityStore.ROOT_ALIAS).publicKey)
    }

    override fun signRoot(bytes: ByteArray): ByteArray =
        Signature.getInstance(CryptoSuiteV1.SIGNATURE_JCA).run {
            initSign(ks.getKey(AndroidKeystoreIdentityStore.ROOT_ALIAS, null) as PrivateKey)
            update(bytes)
            sign()
        }
}

/**
 * Secondary-device onboarding store.
 *
 * This store never creates a second user root. It imports only the owner's public root
 * identity, generates fresh device signing/decryption keys locally, exports a possession-
 * signed enrollment request, and later installs the root-authorized public certificate.
 */
class AndroidSecondaryDeviceIdentityStore(private val context: Context) {
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val bundleFile = context.filesDir.resolve("eidolang-secondary-device-bundle-v1.json")

    fun createEnrollmentRequest(ownerPublicBundleCanonical: String): String {
        val owner = IdentityParser.parsePublicBundleCanonical(ownerPublicBundleCanonical).user
        ensureDeviceKeys()
        val signingPublic = ks.getCertificate(SIGN_ALIAS).publicKey
        val encryptionPublic = ks.getCertificate(ENC_ALIAS).publicKey
        val body = DeviceEnrollmentRequestBodyV1(
            userId = owner.userId,
            signingKeyId = "sig:" + HexSha256.of(signingPublic.encoded),
            signingPublicKeyB64 = PublicKeyCodec.encode(signingPublic),
            encryptionKeyId = "enc:" + HexSha256.of(encryptionPublic.encoded),
            encryptionPublicKeyB64 = PublicKeyCodec.encode(encryptionPublic),
        )
        val bytes = EnrollmentCanonical.bodyJson(body).toByteArray(Charsets.UTF_8)
        val proof = Signature.getInstance(CryptoSuiteV1.SIGNATURE_JCA).run {
            initSign(ks.getKey(SIGN_ALIAS, null) as PrivateKey)
            update(bytes)
            sign()
        }
        return EnrollmentCanonical.json(
            DeviceEnrollmentRequestV1(body, HexSha256.of(bytes), B64Url.encode(proof))
        )
    }

    fun installAuthorizedBundle(canonical: String): DevicePrivateCrypto {
        val bundle = IdentityParser.parsePublicBundleCanonical(canonical)
        ensureDeviceKeys()
        val localSigning = ks.getCertificate(SIGN_ALIAS).publicKey
        val localEncryption = ks.getCertificate(ENC_ALIAS).publicKey
        require(bundle.device.body.signingKeyId == "sig:" + HexSha256.of(localSigning.encoded))
        require(bundle.device.body.encryptionKeyId == "enc:" + HexSha256.of(localEncryption.encoded))
        atomicWrite(canonical)
        return load()!!
    }

    fun load(): DevicePrivateCrypto? {
        if (!bundleFile.exists()) return null
        val bundle = IdentityParser.parsePublicBundleCanonical(bundleFile.readText(Charsets.UTF_8))
        ensureDeviceKeys()
        return AndroidSecondaryPrivateIdentity(ks, bundle.user, bundle.device)
    }

    private fun ensureDeviceKeys() {
        if (!ks.containsAlias(SIGN_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(SIGN_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
                generateKeyPair()
            }
        }
        if (!ks.containsAlias(ENC_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(ENC_ALIAS, KeyProperties.PURPOSE_DECRYPT)
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
    }

    private fun atomicWrite(text: String) {
        val tmp = context.filesDir.resolve(bundleFile.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(bundleFile)) {
            bundleFile.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val SIGN_ALIAS = "eidolang.secondary-device-signing.v1"
        const val ENC_ALIAS = "eidolang.secondary-device-decrypt.v1"
    }
}

private class AndroidSecondaryPrivateIdentity(
    private val ks: KeyStore,
    override val user: UserIdentityV1,
    override val certificate: DeviceCertificateV1,
) : DevicePrivateCrypto {
    override fun signMessage(bytes: ByteArray): ByteArray =
        Signature.getInstance(CryptoSuiteV1.SIGNATURE_JCA).run {
            initSign(ks.getKey(AndroidSecondaryDeviceIdentityStore.SIGN_ALIAS, null) as PrivateKey)
            update(bytes)
            sign()
        }

    override fun unwrapMessageKey(wrapped: ByteArray): ByteArray {
        val key = ks.getKey(AndroidSecondaryDeviceIdentityStore.ENC_ALIAS, null) as PrivateKey
        val cipher = Cipher.getInstance(CryptoSuiteV1.RSA_JCA)
        runCatching { cipher.init(Cipher.DECRYPT_MODE, key, JcaCrypto.oaepSpec) }
            .getOrElse { cipher.init(Cipher.DECRYPT_MODE, key) }
        return cipher.doFinal(wrapped)
    }
}
