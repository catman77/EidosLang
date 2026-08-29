package org.eidolang.core.canonical

import org.eidolang.core.model.*

/**
 * Strict parser for canonical EidogramDocumentV1.
 *
 * Import accepts only the exact canonical representation emitted by
 * [EidogramCanonical.documentJson]. This simultaneously rejects unknown fields,
 * duplicate keys, non-canonical key order/escaping/integers, floats, and lineage drift.
 */
object EidogramParser {
    fun parseCanonical(text: String): EidogramDocumentV1 {
        val root = StrictJsonParser(text).parse().obj()
        root.requireKeys("actions", "canvas", "catalog_hash", "catalog_id", "document_type", "document_version", "lineage")

        require(root.str("catalog_id") == ProtocolLineage.GLYPH_CATALOG_ID) { "Catalog id mismatch" }
        // Format only. Pinning this to the current build's catalogue would reject every eidogram
        // drawn before the catalogue last grew, which for a research catalogue is constantly.
        val catalogHash = root.str("catalog_hash")
        require(catalogHash.matches(Regex("[0-9a-f]{64}"))) { "Catalog hash is not a SHA-256" }
        require(root.str("document_type") == ProtocolLineage.DOCUMENT_TYPE) { "Document type mismatch" }
        require(root.str("document_version") == ProtocolLineage.DOCUMENT_VERSION) { "Document version mismatch" }

        val lineage = root.obj("lineage")
        lineage.requireKeys("master_manifest_hash", "production_binding_hash", "production_grammar_hash")
        require(lineage.str("master_manifest_hash") == ProtocolLineage.MASTER_MANIFEST_HASH) { "Master lineage mismatch" }
        require(lineage.str("production_binding_hash") == ProtocolLineage.PRODUCTION_BINDING_HASH) { "Binding lineage mismatch" }
        require(lineage.str("production_grammar_hash") == ProtocolLineage.PRODUCTION_GRAMMAR_HASH) { "Grammar lineage mismatch" }

        val canvas = root.obj("canvas")
        canvas.requireKeys("height_fp", "width_fp")
        val h = canvas.int("height_fp")
        val w = canvas.int("width_fp")
        require(w == ProtocolLineage.CANVAS_W_FP && h == ProtocolLineage.CANVAS_H_FP) { "Canvas mismatch" }

        val actions = root.arr("actions").items.mapIndexed { expected, raw ->
            parseAction(raw.obj(), expected)
        }
        val d = EidogramDocumentV1(actions, w, h, catalogHash)
        // Replay now so malformed histories fail during import rather than later.
        EidogramReplay.replay(d)

        // Canonical bytes are part of content identity.
        require(EidogramCanonical.documentJson(d) == text) { "Input is valid JSON but not canonical EidogramDocumentV1 bytes" }
        return d
    }

    private fun parseAction(o: JValue.Obj, expectedSeq: Int): EidogramAction {
        val op = o.str("op")
        val seq = o.int("seq")
        require(seq == expectedSeq) { "Non-contiguous action sequence" }
        return when (op) {
            "ADD" -> {
                o.requireKeys("glyph_id", "instance_id", "op", "seq", "transform", "z_index")
                EidogramAction.Add(seq, o.str("instance_id"), o.str("glyph_id"), parseTransform(o.obj("transform")), o.int("z_index"))
            }
            "SET_GLYPH" -> {
                o.requireKeys("glyph_id", "instance_id", "op", "seq")
                EidogramAction.SetGlyph(seq, o.str("instance_id"), o.str("glyph_id"))
            }
            "SET_TRANSFORM" -> {
                o.requireKeys("instance_id", "op", "seq", "transform")
                EidogramAction.SetTransform(seq, o.str("instance_id"), parseTransform(o.obj("transform")))
            }
            "MOVE_Z" -> {
                o.requireKeys("instance_id", "op", "seq", "z_index")
                EidogramAction.MoveZ(seq, o.str("instance_id"), o.int("z_index"))
            }
            "REMOVE" -> {
                o.requireKeys("instance_id", "op", "seq")
                EidogramAction.Remove(seq, o.str("instance_id"))
            }
            "GROUP" -> {
                o.requireKeys("group_id", "instance_ids", "op", "seq")
                val ids = o.arr("instance_ids").items.map { it.str() }
                EidogramAction.Group(seq, o.str("group_id"), ids)
            }
            "UNGROUP" -> {
                o.requireKeys("group_id", "op", "seq")
                EidogramAction.Ungroup(seq, o.str("group_id"))
            }
            else -> error("Unknown Eidogram action '$op'")
        }
    }

    private fun parseTransform(o: JValue.Obj): FixedTransform {
        o.requireKeys("cx_fp", "cy_fp", "rotation_mdeg", "scale_x_fp", "scale_y_fp")
        return FixedTransform(
            cxFp = o.int("cx_fp"),
            cyFp = o.int("cy_fp"),
            scaleXFp = o.int("scale_x_fp"),
            scaleYFp = o.int("scale_y_fp"),
            rotationMdeg = o.int("rotation_mdeg"),
        )
    }
}

private fun JValue.obj(): JValue.Obj = this as? JValue.Obj ?: error("Expected JSON object")
private fun JValue.str(): String = (this as? JValue.Str)?.value ?: error("Expected JSON string")
private fun JValue.Obj.str(k: String): String = fields[k]?.str() ?: error("Missing string '$k'")
private fun JValue.Obj.int(k: String): Int {
    val v = (fields[k] as? JValue.IntNum)?.value ?: error("Missing integer '$k'")
    require(v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Integer out of Int range: $k" }
    return v.toInt()
}
private fun JValue.Obj.obj(k: String): JValue.Obj = fields[k]?.obj() ?: error("Missing object '$k'")
private fun JValue.Obj.arr(k: String): JValue.Arr = fields[k] as? JValue.Arr ?: error("Missing array '$k'")
private fun JValue.Obj.requireKeys(vararg keys: String) {
    require(fields.keys == keys.toSet()) { "Object schema mismatch: got=${fields.keys} expected=${keys.toSet()}" }
}
