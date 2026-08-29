package org.eidolang.feature.editor

import android.content.Context
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.canonical.EidogramParser
import org.eidolang.core.hardening.AndroidLocalSecretBox
import org.eidolang.core.model.EditorDraft
import org.eidolang.core.model.EditorDraftFactory
import java.security.MessageDigest

object DraftStore {
    private fun fileName(key: String): String {
        val h = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(20)
        return "eidogram-draft-$h.bin"
    }

    fun load(context: Context, key: String = "standalone"): Result<EditorDraft> = runCatching {
        val name=fileName(key)
        val f=context.filesDir.resolve(name)
        val legacy=context.filesDir.resolve(name.removeSuffix(".bin")+".json")
        val box=AndroidLocalSecretBox(context)

        if(f.exists()){
            val raw=box.open("eidogram-draft",name,f.readText(Charsets.US_ASCII))
            return@runCatching try{
                EditorDraftFactory.fromDocument(
                    EidogramParser.parseCanonical(raw.toString(Charsets.UTF_8))
                )
            }finally{raw.fill(0)}
        }

        // One-time fail-closed migration from the pre-R21 plaintext draft file.
        if(legacy.exists()){
            val parsed=EidogramParser.parseCanonical(legacy.readText(Charsets.UTF_8))
            val draft=EditorDraftFactory.fromDocument(parsed)
            save(context,draft,key).getOrThrow()
            require(legacy.delete()){"Failed to remove migrated plaintext draft"}
            return@runCatching draft
        }

        EditorDraft()
    }

    fun save(context: Context, draft: EditorDraft, key: String = "standalone"): Result<Unit> = runCatching {
        val name=fileName(key)
        val f=context.filesDir.resolve(name)
        val tmp=context.filesDir.resolve("$name.tmp")
        val raw=EidogramCanonical.documentJson(draft.document).toByteArray(Charsets.UTF_8)
        try{
            val encoded=AndroidLocalSecretBox(context).seal("eidogram-draft",name,raw)
            tmp.writeText(encoded,Charsets.US_ASCII)
            if(!tmp.renameTo(f)){
                f.writeText(tmp.readText(Charsets.US_ASCII),Charsets.US_ASCII)
                tmp.delete()
            }
        }finally{raw.fill(0)}
    }

    fun clear(context: Context, key: String = "standalone") {
        val name=fileName(key)
        context.filesDir.resolve(name).delete()
        context.filesDir.resolve("$name.tmp").delete()
        context.filesDir.resolve(name.removeSuffix(".bin")+".json").delete()
    }
}
