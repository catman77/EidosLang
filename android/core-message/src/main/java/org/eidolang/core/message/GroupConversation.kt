package org.eidolang.core.message

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import java.security.SecureRandom

data class GroupMemberV1(
    val userId: String,
    val identityCanonical: String,
    val displayName: String,
    val deliveryCardCanonical: String = "",
)

/** A group keeps its identity when its membership changes; messages retain immutable epoch IDs. */
data class GroupStateV1(
    val groupId: String,
    val seedB64: String,
    val revision: Int,
    val previousStateId: String,
    val ownerUserId: String,
    val title: String,
    val members: List<GroupMemberV1>,
    val createdAtMs: Long,
    val closed: Boolean,
    val issuerCanonical: String,
    val stateId: String,
    val signatureB64: String,
) {
    fun conversation(): ConversationDescriptorV1 = ConversationFactory.fromSeed(
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("EidoGroupEpochV1|$groupId|$revision".toByteArray()).copyOf(16),
        members.map { it.userId },
    )
}

object GroupStateCodec {
    const val MAX_MEMBERS = 64
    const val MAX_REVISIONS = 4096

    fun memberJson(m: GroupMemberV1): String = CanonicalJson.obj(mapOf(
        "user_id" to CanonicalJson.string(m.userId),
        "identity" to CanonicalJson.string(m.identityCanonical),
        "name" to CanonicalJson.string(m.displayName),
        "delivery_card" to CanonicalJson.string(m.deliveryCardCanonical),
    ))

    fun body(s: GroupStateV1): String = CanonicalJson.obj(mapOf(
        "type" to CanonicalJson.string("EidoGroupStateV1"),
        "group_id" to CanonicalJson.string(s.groupId), "seed" to CanonicalJson.string(s.seedB64),
        "revision" to CanonicalJson.int(s.revision), "previous" to CanonicalJson.string(s.previousStateId),
        "owner" to CanonicalJson.string(s.ownerUserId), "title" to CanonicalJson.string(s.title),
        "members" to CanonicalJson.arr(s.members.map(::memberJson)),
        "created_at_ms" to CanonicalJson.long(s.createdAtMs), "closed" to CanonicalJson.int(if (s.closed) 1 else 0),
        "issuer" to CanonicalJson.string(s.issuerCanonical),
    ))

    fun json(s: GroupStateV1): String = CanonicalJson.obj(mapOf(
        "body" to body(s), "state_id" to CanonicalJson.string(s.stateId),
        "signature" to CanonicalJson.string(s.signatureB64),
    ))

    fun issue(
        owner: DevicePrivateCrypto, previous: GroupStateV1?, title: String,
        members: Collection<GroupMemberV1>, atMs: Long, closed: Boolean = false,
        seed: ByteArray = JcaCrypto.randomBytes(16, SecureRandom()),
    ): GroupStateV1 {
        require(previous == null || (!previous.closed && previous.ownerUserId == owner.user.userId)) {
            "Изменять группу может только её создатель"
        }
        val seedB64 = previous?.seedB64 ?: B64Url.encode(seed)
        val groupId = previous?.groupId ?: HexSha256.ofUtf8("EidoGroupV1|${owner.user.userId}|$seedB64")
        val draft = GroupStateV1(groupId, seedB64, (previous?.revision ?: -1) + 1,
            previous?.stateId.orEmpty(), owner.user.userId, title.trim(), members.sortedBy { it.userId },
            atMs, closed, IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(owner.user, owner.certificate)), "", "")
        val bytes = body(draft).toByteArray(Charsets.UTF_8)
        val signed = draft.copy(stateId = HexSha256.of(bytes), signatureB64 = B64Url.encode(owner.signMessage(bytes)))
        return parse(json(signed))
    }

    fun parse(raw: String): GroupStateV1 {
        require(raw.length <= 1024 * 1024) { "Слишком большой состав группы" }
        val root = StrictJsonParser(raw).parse().groupObj()
        root.groupKeys("body", "state_id", "signature")
        val b = root.fields.getValue("body").groupObj()
        b.groupKeys("type", "group_id", "seed", "revision", "previous", "owner", "title", "members", "created_at_ms", "closed", "issuer")
        require(b.groupString("type") == "EidoGroupStateV1")
        val memberValues = b.groupArray("members")
        require(memberValues.size in 1..MAX_MEMBERS) { "В группе может быть не более $MAX_MEMBERS участников" }
        val members = memberValues.map {
            val m = it.groupObj(); m.groupKeys("user_id", "identity", "name", "delivery_card")
            GroupMemberV1(m.groupString("user_id"), m.groupString("identity"), m.groupString("name"), m.groupString("delivery_card"))
        }
        val closed = b.groupLong("closed"); require(closed in 0L..1L)
        val revision = b.groupLong("revision"); require(revision in 0L until MAX_REVISIONS.toLong())
        val s = GroupStateV1(b.groupString("group_id"), b.groupString("seed"), revision.toInt(),
            b.groupString("previous"), b.groupString("owner"), b.groupString("title"), members,
            b.groupLong("created_at_ms"), closed == 1L, b.groupString("issuer"), root.groupString("state_id"), root.groupString("signature"))
        require(s.createdAtMs >= 0 && s.title.isNotBlank() && s.title == s.title.trim() && s.title.length <= 120)
        require(s.members.map { it.userId } == s.members.map { it.userId }.sorted().distinct())
        require(s.ownerUserId in s.members.map { it.userId })
        require(s.closed || s.members.size >= 2) { "Выберите хотя бы одного собеседника" }
        require(B64Url.decode(s.seedB64).size == 16)
        require(s.groupId == HexSha256.ofUtf8("EidoGroupV1|${s.ownerUserId}|${s.seedB64}"))
        require(if (s.revision == 0) s.previousStateId.isEmpty() else s.previousStateId.matches(Regex("[0-9a-f]{64}")))
        s.members.forEach {
            require(it.displayName.length <= 120 && it.deliveryCardCanonical.length <= 128 * 1024)
            require(IdentityParser.parsePublicBundleCanonical(it.identityCanonical).user.userId == it.userId)
        }
        val issuer = IdentityParser.parsePublicBundleCanonical(s.issuerCanonical)
        require(issuer.user.userId == s.ownerUserId) { "Подпись не принадлежит создателю группы" }
        require(s.stateId == HexSha256.ofUtf8(body(s)))
        require(JcaCrypto.verify(PublicKeyCodec.ec(issuer.device.body.signingPublicKeyB64),
            body(s).toByteArray(Charsets.UTF_8), B64Url.decode(s.signatureB64))) { "Неверная подпись состава группы" }
        require(json(s) == raw) { "Non-canonical group state" }
        return s
    }

    fun verifyChain(chain: List<String>): List<GroupStateV1> {
        require(chain.size in 1..MAX_REVISIONS)
        val states = chain.map(::parse)
        states.forEachIndexed { i, s ->
            require(s.revision == i) { "Пропуск ревизии группы" }
            if (i > 0) {
                val prev = states[i - 1]
                require(!prev.closed && prev.groupId == s.groupId && prev.ownerUserId == s.ownerUserId &&
                    prev.seedB64 == s.seedB64 && prev.stateId == s.previousStateId) { "Разрыв цепочки группы" }
            }
        }
        return states
    }
}

data class GroupControlV1(
    val kind: String,
    val groupId: String,
    val chain: List<String> = emptyList(),
    val envelopeCanonical: String = "",
    val seenStateId: String = "",
)

object GroupControlCodec {
    private fun fields(c: GroupControlV1, chain: List<String>) = mapOf(
        "type" to CanonicalJson.string("EidoGroupControlV1"), "kind" to CanonicalJson.string(c.kind),
        "group_id" to CanonicalJson.string(c.groupId), "chain" to CanonicalJson.arr(chain),
        "envelope" to CanonicalJson.string(c.envelopeCanonical), "seen_state" to CanonicalJson.string(c.seenStateId),
    )

    /** Repeated public cards are sent once without changing the signed state bytes. */
    fun json(c: GroupControlV1): String {
        val cards = linkedMapOf<String, String>()
        val compact = c.chain.map { raw ->
            val state = StrictJsonParser(raw).parse().groupObj()
            val body = state.fields.getValue("body").groupObj()
            val references = body.groupArray("members").map { member ->
                val canonical = CanonicalReserialize.of(member)
                val hash = HexSha256.ofUtf8(canonical)
                cards[hash] = canonical
                CanonicalJson.string(hash)
            }
            CanonicalJson.obj(state.fields.mapValues { (key, value) ->
                if (key == "body") CanonicalJson.obj(body.fields.mapValues { (name, field) ->
                    if (name == "members") CanonicalJson.arr(references) else CanonicalReserialize.of(field)
                }) else CanonicalReserialize.of(value)
            })
        }
        return CanonicalJson.obj(fields(c, compact) + ("member_cards" to CanonicalJson.obj(cards)))
    }

    fun parse(raw: String): GroupControlV1 {
        require(raw.length <= 4 * 1024 * 1024) { "Слишком большое обновление группы" }
        val o = StrictJsonParser(raw).parse().groupObj()
        val compact = "member_cards" in o.fields
        if (compact) o.groupKeys("type", "kind", "group_id", "chain", "envelope", "seen_state", "member_cards")
        else o.groupKeys("type", "kind", "group_id", "chain", "envelope", "seen_state")
        require(o.groupString("type") == "EidoGroupControlV1")
        val cards = if (compact) o.fields.getValue("member_cards").groupObj().fields else emptyMap()
        cards.forEach { (id, value) -> require(id == HexSha256.ofUtf8(CanonicalReserialize.of(value))) }
        var expandedCharacters = 0L
        val chain = o.groupArray("chain").map { value ->
            if (!compact) CanonicalReserialize.of(value) else {
                val state = value.groupObj()
                val body = state.fields.getValue("body").groupObj()
                val members = body.groupArray("members").map {
                    val member = CanonicalReserialize.of(cards[(it as JValue.Str).value] ?: error("Missing group member card"))
                    expandedCharacters += member.length
                    require(expandedCharacters <= 32L * 1024 * 1024) { "Слишком большая история изменений группы" }
                    member
                }
                CanonicalJson.obj(state.fields.mapValues { (key, field) ->
                    if (key == "body") CanonicalJson.obj(body.fields.mapValues { (name, v) ->
                        if (name == "members") CanonicalJson.arr(members) else CanonicalReserialize.of(v)
                    }) else CanonicalReserialize.of(field)
                })
            }
        }
        val c = GroupControlV1(o.groupString("kind"), o.groupString("group_id"),
            chain, o.groupString("envelope"), o.groupString("seen_state"))
        require(c.groupId.matches(Regex("[0-9a-f]{64}")))
        require(c.kind in setOf("update", "message", "leave"))
        if (c.kind == "leave") {
            require(c.chain.isEmpty() && c.envelopeCanonical.isEmpty() && c.seenStateId.matches(Regex("[0-9a-f]{64}")))
        } else {
            val states = GroupStateCodec.verifyChain(c.chain)
            require(states.last().groupId == c.groupId && c.seenStateId.isEmpty())
            require((c.kind == "message") == c.envelopeCanonical.isNotEmpty())
        }
        require((if (compact) json(c) else CanonicalJson.obj(fields(c, c.chain))) == raw)
        return c
    }
}

private fun JValue.groupObj() = this as? JValue.Obj ?: error("Expected group object")
private fun JValue.Obj.groupString(key: String) = (fields[key] as? JValue.Str)?.value ?: error("Missing $key")
private fun JValue.Obj.groupLong(key: String) = (fields[key] as? JValue.IntNum)?.value ?: error("Missing $key")
private fun JValue.Obj.groupArray(key: String) = (fields[key] as? JValue.Arr)?.items ?: error("Missing $key")
private fun JValue.Obj.groupKeys(vararg keys: String) = require(fields.keys == keys.toSet()) { "Unexpected group fields" }
