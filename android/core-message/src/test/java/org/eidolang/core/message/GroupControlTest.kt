package org.eidolang.core.message

import org.eidolang.core.canonical.CanonicalJson
import org.eidolang.core.crypto.*
import org.junit.Assert.*
import org.junit.Test

class GroupControlTest {
    @Test fun sharedCardsAreSentOnceAndReconstructTheExactSignedStates() {
        val owner = JvmPrivateIdentity.generate()
        val peer = JvmPrivateIdentity.generate()
        val members = listOf(owner, peer).map { id ->
            GroupMemberV1(id.user.userId, IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(id.user, id.certificate)), "Имя")
        }
        val chain = mutableListOf<String>()
        var previous: GroupStateV1? = null
        repeat(8) { i ->
            previous = GroupStateCodec.issue(owner, previous, "Группа $i", members, i.toLong())
            chain += GroupStateCodec.json(previous!!)
        }
        val control = GroupControlV1("update", previous!!.groupId, chain)
        val wire = GroupControlCodec.json(control)
        val parsed = GroupControlCodec.parse(wire)
        assertEquals(control, parsed)
        assertEquals(2, Regex("\"identity\":").findAll(wire).count())
        assertTrue("Повторяющиеся визитки раздувают каждое сообщение", wire.length < chain.sumOf { it.length })
        assertEquals(chain, parsed.chain)
        val legacy = CanonicalJson.obj(mapOf("type" to CanonicalJson.string("EidoGroupControlV1"),
            "kind" to CanonicalJson.string("update"), "group_id" to CanonicalJson.string(control.groupId),
            "chain" to CanonicalJson.arr(chain), "envelope" to CanonicalJson.string(""), "seen_state" to CanonicalJson.string("")))
        assertEquals(control, GroupControlCodec.parse(legacy))
    }
}
