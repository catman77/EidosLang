package org.eidolang.core.crypto

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Minimal X.509 carrier required by AndroidKeyStore's standard private-key import API. */
object LocalKeyCertificate {
    fun create(publicKey: PublicKey, signer: KeyPair): X509Certificate {
        val algorithm = byteArrayOf(0x30, 0x0a, 0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(),
            0x3d, 0x04, 0x03, 0x02) // ecdsa-with-SHA256
        val commonName = seq(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03),
            tlv(0x0c, "EidoLang local key".toByteArray(Charsets.UTF_8)))
        val name = seq(tlv(0x31, commonName))
        val validity = seq(tlv(0x18, "20200101000000Z".toByteArray()),
            tlv(0x18, "21200101000000Z".toByteArray()))
        val serial = BigInteger(1, JcaCrypto.randomBytes(16)).add(BigInteger.ONE).toByteArray()
        val body = seq(tlv(0xa0, tlv(2, byteArrayOf(2))), tlv(2, serial), algorithm,
            name, validity, name, publicKey.encoded)
        val encoded = seq(body, algorithm, tlv(3, byteArrayOf(0) + JcaCrypto.sign(signer.private, body)))
        return (CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate)
            .also { it.verify(signer.public); check(it.publicKey.encoded.contentEquals(publicKey.encoded)) }
    }

    private fun seq(vararg values: ByteArray) = tlv(0x30, values.fold(ByteArray(0)) { a, b -> a + b })
    private fun tlv(tag: Int, bytes: ByteArray): ByteArray {
        val length = if (bytes.size < 128) byteArrayOf(bytes.size.toByte()) else {
            val raw = BigInteger.valueOf(bytes.size.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            byteArrayOf((0x80 or raw.size).toByte()) + raw
        }
        return byteArrayOf(tag.toByte()) + length + bytes
    }
}
