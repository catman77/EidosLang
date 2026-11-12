package org.eidolang.core.repository

import org.eidolang.core.canonical.*
import org.eidolang.core.message.GroupStateCodec
import org.eidolang.core.message.GroupStateV1

/** A full account snapshot must see the message database and group outbox at one commit boundary. */
object GroupPersistence { val lock = Any() }

data class GroupOutboxItem(
    val id: String,
    val peerUserId: String,
    val controlCanonical: String,
    val outerMessageId: String = "",
    val outerCreatedAtMs: Long = 0,
)

data class GroupConversationRecord(
    val chain: List<String>,
    val leftAtRevision: Int? = null,
    val handledControlIds: Set<String> = emptySet(),
    val outbox: List<GroupOutboxItem> = emptyList(),
    val outgoingDeliveries: Map<String, Map<String, Long>> = emptyMap(),
) {
    val state: GroupStateV1 get() = GroupStateCodec.parse(chain.last())
    val groupId: String get() = state.groupId
    fun canWrite(userId: String): Boolean = leftAtRevision == null && !state.closed && state.members.any { it.userId == userId }
}

interface GroupConversationStore {
    fun all(): List<GroupConversationRecord>
    fun put(record: GroupConversationRecord)
    fun get(groupId: String): GroupConversationRecord? = all().firstOrNull { it.groupId == groupId }
}

class InMemoryGroupConversationStore : GroupConversationStore {
    private val records = linkedMapOf<String, GroupConversationRecord>()
    override fun all() = records.values.toList()
    override fun get(groupId: String) = records[groupId]
    override fun put(record: GroupConversationRecord) { records[record.groupId] = record }
}

object GroupConversationRecordCodec {
    fun json(r: GroupConversationRecord): String = CanonicalJson.obj(mapOf(
        "chain" to CanonicalJson.arr(r.chain),
        "left_at_revision" to (r.leftAtRevision?.let(CanonicalJson::int) ?: "null"),
        "handled" to CanonicalJson.arr(r.handledControlIds.sorted().map(CanonicalJson::string)),
        "outbox" to CanonicalJson.arr(r.outbox.map { p -> CanonicalJson.obj(mapOf(
            "id" to CanonicalJson.string(p.id), "peer" to CanonicalJson.string(p.peerUserId),
            "control" to CanonicalJson.string(p.controlCanonical), "message_id" to CanonicalJson.string(p.outerMessageId),
            "created_at_ms" to CanonicalJson.long(p.outerCreatedAtMs),
        )) }),
        "deliveries" to CanonicalJson.obj(r.outgoingDeliveries.mapValues { (_, peers) ->
            CanonicalJson.obj(peers.mapValues { CanonicalJson.long(it.value) })
        }),
    ))

    fun parse(raw: String): GroupConversationRecord {
        val o = StrictJsonParser(raw).parse() as JValue.Obj
        require(o.fields.keys == setOf("chain", "left_at_revision", "handled", "outbox", "deliveries"))
        val chain = (o.fields.getValue("chain") as JValue.Arr).items.map(CanonicalReserialize::of)
        GroupStateCodec.verifyChain(chain)
        val pending = (o.fields.getValue("outbox") as JValue.Arr).items.map { v ->
            val p = (v as JValue.Obj).fields
            GroupOutboxItem((p.getValue("id") as JValue.Str).value, (p.getValue("peer") as JValue.Str).value,
                (p.getValue("control") as JValue.Str).value, (p.getValue("message_id") as JValue.Str).value,
                (p.getValue("created_at_ms") as JValue.IntNum).value)
        }
        val deliveries = (o.fields.getValue("deliveries") as JValue.Obj).fields.mapValues { (_, value) ->
            (value as JValue.Obj).fields.mapValues { (it.value as JValue.IntNum).value }
        }
        return GroupConversationRecord(chain,
            (o.fields["left_at_revision"] as? JValue.IntNum)?.value?.toInt(),
            (o.fields.getValue("handled") as JValue.Arr).items.map { (it as JValue.Str).value }.toSet(), pending, deliveries)
    }
}
