package org.eidolang.core.multidevice

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import java.security.KeyPair
import java.security.PrivateKey
import java.security.SecureRandom

interface RootAuthority {
    val user: UserIdentityV1
    fun signRoot(bytes: ByteArray): ByteArray
}

class JvmRootAuthority(
    private val rootPrivate: PrivateKey,
    override val user: UserIdentityV1,
    private val random: SecureRandom = SecureRandom(),
) : RootAuthority {
    override fun signRoot(bytes: ByteArray): ByteArray = JcaCrypto.sign(rootPrivate, bytes, random)

    companion object {
        fun fromPrimary(primary: JvmPrivateIdentity, random: SecureRandom = SecureRandom()) =
            JvmRootAuthority(primary.rootSigning.private, primary.user, random)
    }
}

data class DeviceEnrollmentRequestBodyV1(
    val userId: String,
    val signingKeyId: String,
    val signingPublicKeyB64: String,
    val encryptionKeyId: String,
    val encryptionPublicKeyB64: String,
)

data class DeviceEnrollmentRequestV1(
    val body: DeviceEnrollmentRequestBodyV1,
    val requestId: String,
    val possessionSignatureB64: String,
)

object EnrollmentCanonical {
    fun bodyJson(b: DeviceEnrollmentRequestBodyV1): String = CanonicalJson.obj(mapOf(
        "encryption_key_id" to CanonicalJson.string(b.encryptionKeyId),
        "encryption_public_key_b64" to CanonicalJson.string(b.encryptionPublicKeyB64),
        "request_type" to CanonicalJson.string("EidoDeviceEnrollmentRequestV1"),
        "request_version" to CanonicalJson.string("1.0.0"),
        "signing_key_id" to CanonicalJson.string(b.signingKeyId),
        "signing_public_key_b64" to CanonicalJson.string(b.signingPublicKeyB64),
        "user_id" to CanonicalJson.string(b.userId),
    ))

    fun json(r: DeviceEnrollmentRequestV1): String = CanonicalJson.obj(mapOf(
        "body" to bodyJson(r.body),
        "possession_signature_b64" to CanonicalJson.string(r.possessionSignatureB64),
        "request_id" to CanonicalJson.string(r.requestId),
    ))

    fun parseCanonical(text: String): DeviceEnrollmentRequestV1 {
        val root = StrictJsonParser(text).parse() as? JValue.Obj ?: error("Expected object")
        require(root.fields.keys == setOf("body","possession_signature_b64","request_id"))
        val b = root.fields.getValue("body") as? JValue.Obj ?: error("Expected body")
        require(b.fields.keys == setOf(
            "encryption_key_id","encryption_public_key_b64","request_type","request_version",
            "signing_key_id","signing_public_key_b64","user_id"
        ))
        fun str(o:JValue.Obj,k:String)=(o.fields.getValue(k) as? JValue.Str)?.value ?: error("Expected string $k")
        require(str(b,"request_type")=="EidoDeviceEnrollmentRequestV1")
        require(str(b,"request_version")=="1.0.0")
        val r=DeviceEnrollmentRequestV1(
            DeviceEnrollmentRequestBodyV1(
                str(b,"user_id"),str(b,"signing_key_id"),str(b,"signing_public_key_b64"),
                str(b,"encryption_key_id"),str(b,"encryption_public_key_b64")
            ),
            str(root,"request_id"),
            str(root,"possession_signature_b64"),
        )
        require(json(r)==text) { "Enrollment request is not canonical" }
        return r
    }
}

class PendingJvmDevice private constructor(
    val signing: KeyPair,
    val encryption: KeyPair,
    private val random: SecureRandom,
) {
    fun enrollmentRequest(user: UserIdentityV1): DeviceEnrollmentRequestV1 {
        val body = DeviceEnrollmentRequestBodyV1(
            userId = user.userId,
            signingKeyId = "sig:" + HexSha256.of(signing.public.encoded),
            signingPublicKeyB64 = PublicKeyCodec.encode(signing.public),
            encryptionKeyId = "enc:" + HexSha256.of(encryption.public.encoded),
            encryptionPublicKeyB64 = PublicKeyCodec.encode(encryption.public),
        )
        val bytes = EnrollmentCanonical.bodyJson(body).toByteArray(Charsets.UTF_8)
        return DeviceEnrollmentRequestV1(
            body,
            HexSha256.of(bytes),
            B64Url.encode(JcaCrypto.sign(signing.private, bytes, random)),
        )
    }

    fun install(user: UserIdentityV1, certificate: DeviceCertificateV1): JvmSecondaryDevice {
        val expected = DeviceCertificateBodyV1(
            userId = user.userId,
            signingKeyId = "sig:" + HexSha256.of(signing.public.encoded),
            signingPublicKeyB64 = PublicKeyCodec.encode(signing.public),
            encryptionKeyId = "enc:" + HexSha256.of(encryption.public.encoded),
            encryptionPublicKeyB64 = PublicKeyCodec.encode(encryption.public),
        )
        require(certificate.body == expected) { "Certificate does not belong to pending device keys" }
        require(IdentityVerifier.verifyDevice(user, certificate))
        return JvmSecondaryDevice(signing, encryption, user, certificate, random)
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()) =
            PendingJvmDevice(JcaCrypto.p256(random), JcaCrypto.rsa2048(random), random)
    }
}

class JvmSecondaryDevice(
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
}

object DeviceEnrollmentAuthority {
    fun verifyRequest(r: DeviceEnrollmentRequestV1, user: UserIdentityV1): Boolean = runCatching {
        if (r.body.userId != user.userId) return false
        val signing = PublicKeyCodec.ec(r.body.signingPublicKeyB64)
        val encryption = PublicKeyCodec.rsa(r.body.encryptionPublicKeyB64)
        if (r.body.signingKeyId != "sig:" + HexSha256.of(signing.encoded)) return false
        if (r.body.encryptionKeyId != "enc:" + HexSha256.of(encryption.encoded)) return false
        val bytes = EnrollmentCanonical.bodyJson(r.body).toByteArray(Charsets.UTF_8)
        if (r.requestId != HexSha256.of(bytes)) return false
        JcaCrypto.verify(signing, bytes, B64Url.decode(r.possessionSignatureB64))
    }.getOrDefault(false)

    fun authorize(
        authority: RootAuthority,
        request: DeviceEnrollmentRequestV1,
    ): DeviceCertificateV1 {
        require(DeviceEnrollmentAuthority.verifyRequest(request, authority.user)) { "Invalid enrollment request" }
        val body = DeviceCertificateBodyV1(
            userId = request.body.userId,
            signingKeyId = request.body.signingKeyId,
            signingPublicKeyB64 = request.body.signingPublicKeyB64,
            encryptionKeyId = request.body.encryptionKeyId,
            encryptionPublicKeyB64 = request.body.encryptionPublicKeyB64,
        )
        val bytes = IdentityCanonical.deviceBodyJson(body).toByteArray(Charsets.UTF_8)
        val cert = DeviceCertificateV1(
            body = body,
            deviceId = "dev:" + HexSha256.of(bytes),
            rootSignatureB64 = B64Url.encode(authority.signRoot(bytes)),
        )
        require(IdentityVerifier.verifyDevice(authority.user, cert))
        return cert
    }
}
