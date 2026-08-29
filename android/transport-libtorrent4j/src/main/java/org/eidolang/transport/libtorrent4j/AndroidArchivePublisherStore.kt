package org.eidolang.transport.libtorrent4j

import android.content.Context
import org.eidolang.core.archive.JcaArchivePublisher
import org.eidolang.core.crypto.B64Url
import org.eidolang.core.crypto.DevicePrivateCrypto
import org.eidolang.core.hardening.AndroidLocalSecretBox
import java.io.File

/**
 * Persists the BEP44 publisher key so the DHT target survives process restarts.
 *
 * Before R22.1 `JcaArchivePublisher.create` minted a fresh Ed25519 pair on every call and nothing
 * stored it, so `target = SHA1(pk || salt)` changed each run and a mutable head could never be
 * updated — the whole point of BEP44. The seed is sealed with the R21 `AndroidLocalSecretBox`,
 * the same local AndroidKeyStore domain that already protects the history-vault recovery secret.
 *
 * The public key is stored alongside the seed because JCA cannot derive an Ed25519 public key
 * from a seed; it is not secret.
 */
class AndroidArchivePublisherStore(private val context: Context) {

    private val box = AndroidLocalSecretBox(context)
    private val file: File get() = File(context.filesDir, FILE_NAME)

    fun exists(): Boolean = file.exists()

    /** Load the persisted publisher, or mint and persist one on first use. */
    fun ensure(identity: DevicePrivateCrypto): JcaArchivePublisher {
        loadIfPresent(identity)?.let { return it }
        val fresh = JcaArchivePublisher.create(identity)
        save(fresh)
        return fresh
    }

    fun loadIfPresent(identity: DevicePrivateCrypto): JcaArchivePublisher? {
        if (!file.exists()) return null
        val opened = box.open(NAMESPACE, LOGICAL_ID, file.readText(Charsets.UTF_8))
        try {
            require(opened.size == 32) { "publisher key material must be a 32-byte Ed25519 seed" }
            return JcaArchivePublisher.restore(identity, opened)
        } finally {
            opened.fill(0)
        }
    }

    fun save(publisher: JcaArchivePublisher) {
        val material = publisher.seed.copyOf()
        try {
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(box.seal(NAMESPACE, LOGICAL_ID, material), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
        } finally {
            material.fill(0)
        }
    }

    fun delete() {
        file.delete()
    }

    private companion object {
        const val FILE_NAME = "eidolang-archive-publisher-v1.bin"
        const val NAMESPACE = "archive-publisher"
        const val LOGICAL_ID = "bep44-ed25519-v1"
        @Suppress("unused") val B64 = B64Url
    }
}
