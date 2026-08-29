package org.eidolang.core.crypto

import org.eidolang.core.canonical.CanonicalJson
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom

data class UserIdentityV1(
    val userId: String,
    val rootSigningPublicKeyB64: String,
    val signatureAlgorithm: String = CryptoSuiteV1.SIGNATURE,
)

data class DeviceCertificateBodyV1(
    val userId: String,
    val signingKeyId: String,
    val signingPublicKeyB64: String,
    val encryptionKeyId: String,
    val encryptionPublicKeyB64: String,
    val signatureAlgorithm: String = CryptoSuiteV1.SIGNATURE,
    val encryptionKeyAlgorithm: String = CryptoSuiteV1.ENCRYPTION_KEY,
)

data class DeviceCertificateV1(
    val body: DeviceCertificateBodyV1,
    val deviceId: String,
    val rootSignatureB64: String,
)

data class PublicIdentityBundleV1(
    val user: UserIdentityV1,
    val device: DeviceCertificateV1,
)

object IdentityCanonical {
    fun userBodyJson(rootSigningPublicKeyB64: String): String = CanonicalJson.obj(mapOf(
        "identity_type" to CanonicalJson.string("EidoUserIdentityV1"),
        "identity_version" to CanonicalJson.string("1.0.0"),
        "root_signing_public_key_b64" to CanonicalJson.string(rootSigningPublicKeyB64),
        "signature_algorithm" to CanonicalJson.string(CryptoSuiteV1.SIGNATURE),
    ))

    fun deviceBodyJson(b: DeviceCertificateBodyV1): String = CanonicalJson.obj(mapOf(
        "device_certificate_type" to CanonicalJson.string("EidoDeviceCertificateV1"),
        "device_certificate_version" to CanonicalJson.string("1.0.0"),
        "encryption_key_algorithm" to CanonicalJson.string(b.encryptionKeyAlgorithm),
        "encryption_key_id" to CanonicalJson.string(b.encryptionKeyId),
        "encryption_public_key_b64" to CanonicalJson.string(b.encryptionPublicKeyB64),
        "signature_algorithm" to CanonicalJson.string(b.signatureAlgorithm),
        "signing_key_id" to CanonicalJson.string(b.signingKeyId),
        "signing_public_key_b64" to CanonicalJson.string(b.signingPublicKeyB64),
        "user_id" to CanonicalJson.string(b.userId),
    ))

    fun userIdentityJson(u: UserIdentityV1): String = CanonicalJson.obj(mapOf(
        "root_signing_public_key_b64" to CanonicalJson.string(u.rootSigningPublicKeyB64),
        "signature_algorithm" to CanonicalJson.string(u.signatureAlgorithm),
        "user_id" to CanonicalJson.string(u.userId),
    ))

    fun deviceCertificateJson(c: DeviceCertificateV1): String = CanonicalJson.obj(mapOf(
        "body" to deviceBodyJson(c.body),
        "device_id" to CanonicalJson.string(c.deviceId),
        "root_signature_b64" to CanonicalJson.string(c.rootSignatureB64),
    ))

    fun publicBundleJson(b: PublicIdentityBundleV1): String = CanonicalJson.obj(mapOf(
        "bundle_type" to CanonicalJson.string("EidoPublicIdentityBundleV1"),
        "bundle_version" to CanonicalJson.string("1.0.0"),
        "device" to deviceCertificateJson(b.device),
        "user" to userIdentityJson(b.user),
    ))
}

object IdentityFactory {
    fun user(rootPublic: PublicKey): UserIdentityV1 {
        val pub = PublicKeyCodec.encode(rootPublic)
        val id = HexSha256.ofUtf8(IdentityCanonical.userBodyJson(pub))
        return UserIdentityV1(id, pub)
    }

    fun deviceBody(user: UserIdentityV1, signingPublic: PublicKey, encryptionPublic: PublicKey): DeviceCertificateBodyV1 {
        val signingB64 = PublicKeyCodec.encode(signingPublic)
        val encryptionB64 = PublicKeyCodec.encode(encryptionPublic)
        return DeviceCertificateBodyV1(
            userId = user.userId,
            signingKeyId = "sig:" + HexSha256.of(signingPublic.encoded),
            signingPublicKeyB64 = signingB64,
            encryptionKeyId = "enc:" + HexSha256.of(encryptionPublic.encoded),
            encryptionPublicKeyB64 = encryptionB64,
        )
    }

    fun certificate(body: DeviceCertificateBodyV1, rootPrivate: PrivateKey, random: SecureRandom = SecureRandom()): DeviceCertificateV1 {
        val bytes = IdentityCanonical.deviceBodyJson(body).toByteArray(Charsets.UTF_8)
        return DeviceCertificateV1(
            body = body,
            deviceId = "dev:" + HexSha256.of(bytes),
            rootSignatureB64 = B64Url.encode(JcaCrypto.sign(rootPrivate, bytes, random)),
        )
    }
}

object IdentityVerifier {
    fun verifyBundle(bundle: PublicIdentityBundleV1): Boolean =
        verifyDevice(bundle.user, bundle.device)

    fun verifyUser(user: UserIdentityV1): Boolean {
        if (user.signatureAlgorithm != CryptoSuiteV1.SIGNATURE) return false
        val expected = HexSha256.ofUtf8(IdentityCanonical.userBodyJson(user.rootSigningPublicKeyB64))
        return expected == user.userId
    }

    fun verifyDevice(user: UserIdentityV1, cert: DeviceCertificateV1): Boolean {
        if (!verifyUser(user)) return false
        if (cert.body.userId != user.userId) return false
        if (cert.body.signatureAlgorithm != CryptoSuiteV1.SIGNATURE) return false
        if (cert.body.encryptionKeyAlgorithm != CryptoSuiteV1.ENCRYPTION_KEY) return false
        val bodyBytes = IdentityCanonical.deviceBodyJson(cert.body).toByteArray(Charsets.UTF_8)
        if (cert.deviceId != "dev:" + HexSha256.of(bodyBytes)) return false
        val signingPublic = runCatching { PublicKeyCodec.ec(cert.body.signingPublicKeyB64) }.getOrNull() ?: return false
        val encryptionPublic = runCatching { PublicKeyCodec.rsa(cert.body.encryptionPublicKeyB64) }.getOrNull() ?: return false
        if (cert.body.signingKeyId != "sig:" + HexSha256.of(signingPublic.encoded)) return false
        if (cert.body.encryptionKeyId != "enc:" + HexSha256.of(encryptionPublic.encoded)) return false
        return JcaCrypto.verify(
            PublicKeyCodec.ec(user.rootSigningPublicKeyB64),
            bodyBytes,
            runCatching { B64Url.decode(cert.rootSignatureB64) }.getOrNull() ?: return false,
        )
    }
}

interface DevicePrivateCrypto {
    val user: UserIdentityV1
    val certificate: DeviceCertificateV1
    fun signMessage(bytes: ByteArray): ByteArray
    fun unwrapMessageKey(wrapped: ByteArray): ByteArray
}

class JvmPrivateIdentity(
    val rootSigning: KeyPair,
    val deviceSigning: KeyPair,
    val deviceEncryption: KeyPair,
    override val user: UserIdentityV1,
    override val certificate: DeviceCertificateV1,
    private val random: SecureRandom = SecureRandom(),
) : DevicePrivateCrypto {
    override fun signMessage(bytes: ByteArray): ByteArray =
        JcaCrypto.sign(deviceSigning.private, bytes, random)

    override fun unwrapMessageKey(wrapped: ByteArray): ByteArray =
        JcaCrypto.unwrapKey(deviceEncryption.private, wrapped)

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): JvmPrivateIdentity {
            val root = JcaCrypto.p256(random)
            val signing = JcaCrypto.p256(random)
            val encryption = JcaCrypto.rsa2048(random)
            val user = IdentityFactory.user(root.public)
            val body = IdentityFactory.deviceBody(user, signing.public, encryption.public)
            val cert = IdentityFactory.certificate(body, root.private, random)
            return JvmPrivateIdentity(root, signing, encryption, user, cert, random)
        }

        fun fromEncoded(
            rootPrivateB64: String,
            rootPublicB64: String,
            deviceSigningPrivateB64: String,
            deviceSigningPublicB64: String,
            deviceEncryptionPrivateB64: String,
            deviceEncryptionPublicB64: String,
            certificate: DeviceCertificateV1,
            random: SecureRandom = SecureRandom(),
        ): JvmPrivateIdentity {
            val root = KeyPair(PublicKeyCodec.ec(rootPublicB64), PublicKeyCodec.privateEc(rootPrivateB64))
            val sign = KeyPair(PublicKeyCodec.ec(deviceSigningPublicB64), PublicKeyCodec.privateEc(deviceSigningPrivateB64))
            val enc = KeyPair(PublicKeyCodec.rsa(deviceEncryptionPublicB64), PublicKeyCodec.privateRsa(deviceEncryptionPrivateB64))
            return JvmPrivateIdentity(root, sign, enc, IdentityFactory.user(root.public), certificate, random)
        }
    }
}
