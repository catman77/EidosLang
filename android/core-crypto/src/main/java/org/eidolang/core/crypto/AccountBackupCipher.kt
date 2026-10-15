package org.eidolang.core.crypto

import java.nio.ByteBuffer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The file is independent of AndroidKeyStore, package UID and APK signing keys. */
object AccountBackupCipher {
    private val magic = "EIDOBK01".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 600_000
    const val MAX_BYTES = 128 * 1024 * 1024
    private const val HEADER_BYTES = 8 + 4 + 16 + 12

    fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        require(salt.size == 16)
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 12) { "Пароль копии должен содержать не менее 12 символов" }
        val salt = JcaCrypto.randomBytes(16)
        val key = deriveKey(password, salt)
        return try { encryptWithKey(plaintext, key, salt) } finally { key.fill(0) }
    }

    fun encryptWithKey(plaintext: ByteArray, key: ByteArray, salt: ByteArray): ByteArray {
        require(plaintext.size <= MAX_BYTES - HEADER_BYTES - 16)
        require(salt.size == 16 && key.size == 32)
        val nonce = JcaCrypto.randomBytes(12)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(magic).putInt(ITERATIONS).put(salt).put(nonce).array()
        return header + JcaCrypto.encryptAesGcm(key, nonce, header, plaintext)
    }

    fun decrypt(encoded: ByteArray, password: CharArray): ByteArray {
        require(encoded.size in (HEADER_BYTES + 16)..MAX_BYTES) { "Неверный размер копии" }
        val input = ByteBuffer.wrap(encoded)
        val actualMagic = ByteArray(8).also(input::get)
        require(actualMagic.contentEquals(magic) && input.int == ITERATIONS) { "Неподдерживаемый формат копии" }
        val salt = ByteArray(16).also(input::get)
        val nonce = ByteArray(12).also(input::get)
        val key = deriveKey(password, salt)
        return try {
            JcaCrypto.decryptAesGcm(key, nonce, encoded.copyOfRange(0, HEADER_BYTES),
                encoded.copyOfRange(HEADER_BYTES, encoded.size))
        } finally { key.fill(0) }
    }
}
