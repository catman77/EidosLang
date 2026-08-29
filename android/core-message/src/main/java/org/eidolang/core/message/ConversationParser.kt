package org.eidolang.core.message

import org.eidolang.core.canonical.JValue
import org.eidolang.core.canonical.StrictJsonParser
import org.eidolang.core.crypto.B64Url

object ConversationParser {
    fun parseCanonical(text: String): ConversationDescriptorV1 {
        val root = StrictJsonParser(text).parse().obj()
        root.requireKeys("body", "conversation_id")
        val b = root.obj("body")
        b.requireKeys("conversation_type", "conversation_version", "participant_user_ids", "seed_b64")
        require(b.str("conversation_type") == "EidoConversationV1")
        require(b.str("conversation_version") == "1.0.0")
        val participants = b.arr("participant_user_ids").items.map { it.str() }
        require(participants == participants.sorted().distinct())
        val result = ConversationFactory.fromSeed(B64Url.decode(b.str("seed_b64")), participants)
        require(result.conversationId == root.str("conversation_id"))
        require(ConversationCanonical.descriptorJson(result) == text)
        return result
    }
}

private fun JValue.obj(): JValue.Obj = this as? JValue.Obj ?: error("Expected object")
private fun JValue.str(): String = (this as? JValue.Str)?.value ?: error("Expected string")
private fun JValue.Obj.str(k: String): String = fields[k]?.str() ?: error("Missing '$k'")
private fun JValue.Obj.obj(k: String): JValue.Obj = fields[k]?.obj() ?: error("Missing '$k'")
private fun JValue.Obj.arr(k: String): JValue.Arr = fields[k] as? JValue.Arr ?: error("Missing '$k'")
private fun JValue.Obj.requireKeys(vararg keys: String) {
    require(fields.keys == keys.toSet()) { "Object schema mismatch" }
}
