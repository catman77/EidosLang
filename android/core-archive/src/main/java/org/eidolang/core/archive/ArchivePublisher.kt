package org.eidolang.core.archive

import org.eidolang.core.canonical.CanonicalJson
import org.eidolang.core.crypto.*
import java.security.*
import java.security.SecureRandom

/** Binds a BEP44 Ed25519 public key to an already authorized R15 device identity. */
data class ArchivePublisherCertificateV1(
    val userId: String,
    val deviceId: String,
    val dhtPublicKeyRawB64: String,
    val publisherKeyId: String,
    val deviceSignatureB64: String,
)

object Ed25519Raw {
    // SubjectPublicKeyInfo prefix for Ed25519: 302a300506032b6570032100
    private val X509_PREFIX = byteArrayOf(0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00)

    /**
     * Ed25519 is handled with Bouncy Castle's low-level signer rather than JCA.
     *
     * Android has no general-purpose Ed25519 `KeyFactory` or `KeyPairGenerator` below API 35: on
     * API 34 the only registered service is `AndroidKeyStoreBCWorkaround`'s `Signature`, which
     * rejects any key that is not keystore-backed, so head verification failed outright. The
     * low-level API needs no provider registration and works from minSdk 26. It also derives the
     * public key from a seed, which JCA cannot do.
     */
    fun publicFromSeed(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        return org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
            .generatePublicKey().encoded
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
        signer.init(true, org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(publicRaw: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        require(publicRaw.size == 32)
        val v = org.bouncycastle.crypto.signers.Ed25519Signer()
        v.init(false, org.bouncycastle.crypto.params.Ed25519PublicKeyParameters(publicRaw, 0))
        v.update(message, 0, message.size)
        v.verifySignature(signature)
    }.getOrDefault(false)

    /** X.509 SubjectPublicKeyInfo wrapper, kept for artifacts that carry the encoded form. */
    fun x509(publicRaw: ByteArray): ByteArray {
        require(publicRaw.size == 32)
        return X509_PREFIX + publicRaw
    }

    fun rawFromX509(encoded: ByteArray): ByteArray {
        require(encoded.size == X509_PREFIX.size + 32 &&
            encoded.copyOfRange(0, X509_PREFIX.size).contentEquals(X509_PREFIX)) {
            "unexpected Ed25519 X509 encoding"
        }
        return encoded.copyOfRange(X509_PREFIX.size, encoded.size)
    }
}

object ArchivePublisherCanonical {
    fun bodyJson(userId: String, deviceId: String, dhtPublicKeyRawB64: String): String = CanonicalJson.obj(mapOf(
        "algorithm" to CanonicalJson.string("ED25519_BEP44"),
        "device_id" to CanonicalJson.string(deviceId),
        "dht_public_key_raw_b64" to CanonicalJson.string(dhtPublicKeyRawB64),
        "publisher_certificate_type" to CanonicalJson.string("ArchivePublisherCertificateV1"),
        "publisher_certificate_version" to CanonicalJson.string("1.0.0"),
        "user_id" to CanonicalJson.string(userId),
    ))

    fun certificateJson(c: ArchivePublisherCertificateV1): String = CanonicalJson.obj(mapOf(
        "body" to bodyJson(c.userId, c.deviceId, c.dhtPublicKeyRawB64),
        "device_signature_b64" to CanonicalJson.string(c.deviceSignatureB64),
        "publisher_key_id" to CanonicalJson.string(c.publisherKeyId),
    ))
}

class JcaArchivePublisher private constructor(
    /** Raw 32-byte Ed25519 seed. Persist it (sealed) to keep a stable DHT target. */
    val seed: ByteArray,
    val publicKeyRaw: ByteArray,
    val certificate: ArchivePublisherCertificateV1,
) {
    fun signBep44(bytes: ByteArray): ByteArray = Ed25519Raw.sign(seed, bytes)

    companion object {
        /** Recreate the exact publisher from a stored seed, keeping the same DHT target. */
        fun restore(identity: DevicePrivateCrypto, seed: ByteArray): JcaArchivePublisher =
            build(identity, seed.copyOf())

        fun create(identity: DevicePrivateCrypto, random: SecureRandom = SecureRandom()): JcaArchivePublisher =
            build(identity, ByteArray(32).also { random.nextBytes(it) })

        private fun build(identity: DevicePrivateCrypto, seed: ByteArray): JcaArchivePublisher {
            val raw = Ed25519Raw.publicFromSeed(seed)
            val rawB64 = B64Url.encode(raw)
            val body = ArchivePublisherCanonical.bodyJson(identity.user.userId, identity.certificate.deviceId, rawB64)
            val keyId = "dht:" + HexSha256.of(raw)
            val sig = identity.signMessage(body.toByteArray(Charsets.UTF_8))
            return JcaArchivePublisher(seed, raw, ArchivePublisherCertificateV1(
                identity.user.userId, identity.certificate.deviceId, rawB64, keyId, B64Url.encode(sig)
            ))
        }
    }
}

object ArchivePublisherVerifier {
    fun verify(cert: ArchivePublisherCertificateV1, identity: PublicIdentityBundleV1): Boolean {
        if (!IdentityVerifier.verifyBundle(identity)) return false
        if (cert.userId != identity.user.userId || cert.deviceId != identity.device.deviceId) return false
        val raw = runCatching { B64Url.decode(cert.dhtPublicKeyRawB64) }.getOrNull() ?: return false
        if (raw.size != 32 || cert.publisherKeyId != "dht:" + HexSha256.of(raw)) return false
        val body = ArchivePublisherCanonical.bodyJson(cert.userId, cert.deviceId, cert.dhtPublicKeyRawB64)
        return runCatching {
            JcaCrypto.verify(
                PublicKeyCodec.ec(identity.device.body.signingPublicKeyB64),
                body.toByteArray(Charsets.UTF_8),
                B64Url.decode(cert.deviceSignatureB64),
            )
        }.getOrDefault(false)
    }
}
