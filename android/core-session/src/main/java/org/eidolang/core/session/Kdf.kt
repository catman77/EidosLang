package org.eidolang.core.session

import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.KeyPairGenerator
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SessionSuiteV1 {
    const val CURVE = "P256"
    const val KDF = "HKDF_SHA256"
    const val AEAD = "AES_256_GCM"
    const val PROTOCOL = "EidoForwardSessionV1"
    const val VERSION = "1.0.0"
    const val HASH_BYTES = 32
}

object HkdfSha256 {
    private const val HASH_LEN = 32

    fun extract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val effectiveSalt = salt ?: ByteArray(HASH_LEN)
        return hmac(effectiveSalt, ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LEN))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    fun derive(salt: ByteArray?, ikm: ByteArray, info: String, length: Int): ByteArray =
        expand(extract(salt, ikm), info.toByteArray(Charsets.UTF_8), length)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

object P256Dh {
    fun generate(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair()

    fun agree(privateKey: PrivateKey, publicKey: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }
}

data class RootChainStep(
    val rootKey: ByteArray,
    val chainKey: ByteArray,
)

object RatchetKdf {
    fun rootStep(rootKey: ByteArray, dhOutput: ByteArray): RootChainStep {
        val prk = HkdfSha256.extract(rootKey, dhOutput)
        val out = HkdfSha256.expand(
            prk,
            "EIDOLANG-R20.1-ROOT".toByteArray(Charsets.UTF_8),
            64,
        )
        return RootChainStep(out.copyOfRange(0,32), out.copyOfRange(32,64))
    }

    fun messageStep(chainKey: ByteArray): Pair<ByteArray,ByteArray> {
        require(chainKey.size == 32)
        val messageKey = HkdfSha256.hmac(chainKey, byteArrayOf(0x01))
        val nextChain = HkdfSha256.hmac(chainKey, byteArrayOf(0x02))
        return messageKey to nextChain
    }

    fun initialChains(sharedSecret: ByteArray, sessionId: String): Triple<ByteArray,ByteArray,ByteArray> {
        val out = HkdfSha256.derive(
            salt = null,
            ikm = sharedSecret,
            info = "EIDOLANG-R20.1-INIT|$sessionId",
            length = 96,
        )
        return Triple(
            out.copyOfRange(0,32),
            out.copyOfRange(32,64),
            out.copyOfRange(64,96),
        )
    }
}
