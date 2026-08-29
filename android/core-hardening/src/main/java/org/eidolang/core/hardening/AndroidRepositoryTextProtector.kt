package org.eidolang.core.hardening

import android.content.Context
import org.eidolang.core.repository.RepositoryTextProtector

class AndroidRepositoryTextProtector(
    context: Context,
) : RepositoryTextProtector {
    private val box = AndroidLocalSecretBox(context)

    override fun protect(namespace: String, logicalId: String, plaintext: String): String {
        val raw = plaintext.toByteArray(Charsets.UTF_8)
        return try {
            PREFIX + box.seal("repo-$namespace", logicalId, raw)
        } finally {
            raw.fill(0)
        }
    }

    override fun unprotect(namespace: String, logicalId: String, stored: String): String {
        require(isProtected(stored)) { "Repository sensitive text is not protected" }
        val raw = box.open("repo-$namespace", logicalId, stored.removePrefix(PREFIX))
        return try {
            raw.toString(Charsets.UTF_8)
        } finally {
            raw.fill(0)
        }
    }

    override fun isProtected(stored: String): Boolean = stored.startsWith(PREFIX)

    companion object {
        private const val PREFIX = "enc1:"
    }
}
