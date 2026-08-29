package org.eidolang.core.crypto

import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

object CryptoSuiteV1 {
    const val SIGNATURE = "ECDSA_P256_SHA256"
    const val SIGNATURE_JCA = "SHA256withECDSA"
    const val ENCRYPTION_KEY = "RSA_2048_OAEP_SHA256_MGF1_SHA1"
    const val RSA_JCA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    const val PAYLOAD = "AES_256_GCM"
    const val AES_JCA = "AES/GCM/NoPadding"
    const val AES_KEY_BYTES = 32
    const val GCM_NONCE_BYTES = 12
    const val GCM_TAG_BITS = 128
}

object B64Url {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    fun encode(bytes: ByteArray): String = enc.encodeToString(bytes)
    fun decode(text: String): ByteArray = dec.decode(text)
}

object HexSha256 {
    fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun ofUtf8(text: String): String = of(text.toByteArray(Charsets.UTF_8))
}

object PublicKeyCodec {
    fun encode(key: PublicKey): String = B64Url.encode(key.encoded)
    fun ec(text: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(B64Url.decode(text)))
    fun rsa(text: String): PublicKey =
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(B64Url.decode(text)))

    fun privateEc(text: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(B64Url.decode(text)))
    fun privateRsa(text: String): PrivateKey =
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(B64Url.decode(text)))
}

object JcaCrypto {
    val oaepSpec = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA1,
        PSource.PSpecified.DEFAULT,
    )

    fun p256(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair()

    fun rsa2048(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()

    fun sign(privateKey: PrivateKey, bytes: ByteArray, random: SecureRandom = SecureRandom()): ByteArray =
        Signature.getInstance(CryptoSuiteV1.SIGNATURE_JCA).run {
            initSign(privateKey, random)
            update(bytes)
            sign()
        }

    fun verify(publicKey: PublicKey, bytes: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance(CryptoSuiteV1.SIGNATURE_JCA).run {
            initVerify(publicKey)
            update(bytes)
            verify(signature)
        }

    fun wrapKey(publicKey: PublicKey, rawKey: ByteArray, random: SecureRandom = SecureRandom()): ByteArray =
        Cipher.getInstance(CryptoSuiteV1.RSA_JCA).run {
            init(Cipher.ENCRYPT_MODE, publicKey, oaepSpec, random)
            doFinal(rawKey)
        }

    fun unwrapKey(privateKey: PrivateKey, wrapped: ByteArray): ByteArray =
        Cipher.getInstance(CryptoSuiteV1.RSA_JCA).run {
            init(Cipher.DECRYPT_MODE, privateKey, oaepSpec)
            doFinal(wrapped)
        }

    fun encryptAesGcm(rawKey: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(rawKey.size == CryptoSuiteV1.AES_KEY_BYTES)
        require(nonce.size == CryptoSuiteV1.GCM_NONCE_BYTES)
        return Cipher.getInstance(CryptoSuiteV1.AES_JCA).run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(CryptoSuiteV1.GCM_TAG_BITS, nonce))
            updateAAD(aad)
            doFinal(plaintext)
        }
    }

    fun decryptAesGcm(rawKey: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        require(rawKey.size == CryptoSuiteV1.AES_KEY_BYTES)
        require(nonce.size == CryptoSuiteV1.GCM_NONCE_BYTES)
        return Cipher.getInstance(CryptoSuiteV1.AES_JCA).run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(rawKey, "AES"), GCMParameterSpec(CryptoSuiteV1.GCM_TAG_BITS, nonce))
            updateAAD(aad)
            doFinal(ciphertext)
        }
    }

    fun randomBytes(n: Int, random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(n).also(random::nextBytes)
}
