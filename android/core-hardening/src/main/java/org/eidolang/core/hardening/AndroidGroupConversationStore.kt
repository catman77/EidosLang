package org.eidolang.core.hardening

import android.content.Context
import org.eidolang.core.repository.GroupConversationRecord
import org.eidolang.core.repository.GroupConversationRecordCodec
import org.eidolang.core.repository.GroupConversationStore

/** Group history and pending fan-out survive process death and independent account backups. */
class AndroidGroupConversationStore(private val context: Context) : GroupConversationStore {
    private val box = AndroidLocalSecretBox(context)
    private val directory get() = context.filesDir.resolve("group-chats").apply { mkdirs() }

    override fun all(): List<GroupConversationRecord> = synchronized(LOCK) {
        directory.listFiles { f -> f.name.matches(Regex("[0-9a-f]{64}\\.bin")) }
            ?.sortedBy { it.name }?.map { read(it.name.removeSuffix(".bin")) } ?: emptyList()
    }

    override fun get(groupId: String): GroupConversationRecord? = synchronized(LOCK) {
        require(groupId.matches(Regex("[0-9a-f]{64}")))
        if (directory.resolve("$groupId.bin").isFile) read(groupId) else null
    }

    private fun read(id: String): GroupConversationRecord {
        val bytes = box.open("group-chat", id, directory.resolve("$id.bin").readText(Charsets.US_ASCII))
        try {
            return GroupConversationRecordCodec.parse(bytes.toString(Charsets.UTF_8)).also {
                require(it.groupId == id) { "Подмена файла группы" }
            }
        } finally { bytes.fill(0) }
    }

    override fun put(record: GroupConversationRecord) = synchronized(LOCK) {
        val id = record.groupId
        val raw = GroupConversationRecordCodec.json(record).toByteArray(Charsets.UTF_8)
        try {
            AndroidAccountRecovery.atomicWrite(directory.resolve("$id.bin"),
                box.seal("group-chat", id, raw).toByteArray(Charsets.US_ASCII))
        } finally { raw.fill(0) }
    }

    private companion object { val LOCK = Any() }
}
