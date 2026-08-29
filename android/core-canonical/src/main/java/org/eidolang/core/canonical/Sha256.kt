package org.eidolang.core.canonical

import java.security.MessageDigest

object Sha256 {
    fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun hex(text: String): String = hex(text.toByteArray(Charsets.UTF_8))
}
