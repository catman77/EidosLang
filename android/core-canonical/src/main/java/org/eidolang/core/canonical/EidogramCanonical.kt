package org.eidolang.core.canonical

import org.eidolang.core.model.*

object EidogramCanonical {
    fun documentJson(d: EidogramDocumentV1): String = CanonicalJson.obj(mapOf(
        "actions" to CanonicalJson.arr(d.actions.map(::actionJson)),
        "canvas" to CanonicalJson.obj(mapOf("height_fp" to CanonicalJson.int(d.canvasHFp), "width_fp" to CanonicalJson.int(d.canvasWFp))),
        "catalog_hash" to CanonicalJson.string(d.catalogHash),
        "catalog_id" to CanonicalJson.string(ProtocolLineage.GLYPH_CATALOG_ID),
        "document_type" to CanonicalJson.string(ProtocolLineage.DOCUMENT_TYPE),
        "document_version" to CanonicalJson.string(ProtocolLineage.DOCUMENT_VERSION),
        "lineage" to CanonicalJson.obj(mapOf(
            "master_manifest_hash" to CanonicalJson.string(ProtocolLineage.MASTER_MANIFEST_HASH),
            "production_binding_hash" to CanonicalJson.string(ProtocolLineage.PRODUCTION_BINDING_HASH),
            "production_grammar_hash" to CanonicalJson.string(ProtocolLineage.PRODUCTION_GRAMMAR_HASH),
        )),
    ))

    fun snapshotJson(s: EidogramSnapshot): String = CanonicalJson.obj(mapOf(
        "instances" to CanonicalJson.arr(s.instances.map(::instanceJson))
    ))

    fun contentHash(d: EidogramDocumentV1): String = Sha256.hex(documentJson(d))
    fun snapshotHash(d: EidogramDocumentV1): String = Sha256.hex(snapshotJson(EidogramReplay.replay(d)))

    private fun transformJson(t: FixedTransform): String = CanonicalJson.obj(mapOf(
        "cx_fp" to CanonicalJson.int(t.cxFp),
        "cy_fp" to CanonicalJson.int(t.cyFp),
        "rotation_mdeg" to CanonicalJson.int(t.rotationMdeg),
        "scale_x_fp" to CanonicalJson.int(t.scaleXFp),
        "scale_y_fp" to CanonicalJson.int(t.scaleYFp),
    ))

    private fun instanceJson(i: GlyphInstance): String = CanonicalJson.obj(mapOf(
        "glyph_id" to CanonicalJson.string(i.glyphId),
        "group_id" to CanonicalJson.nullableString(i.groupId),
        "instance_id" to CanonicalJson.string(i.instanceId),
        "transform" to transformJson(i.transform),
        "z_index" to CanonicalJson.int(i.zIndex),
    ))

    private fun actionJson(a: EidogramAction): String = when (a) {
        is EidogramAction.Add -> CanonicalJson.obj(mapOf(
            "glyph_id" to CanonicalJson.string(a.glyphId), "instance_id" to CanonicalJson.string(a.instanceId),
            "op" to CanonicalJson.string("ADD"), "seq" to CanonicalJson.int(a.seq),
            "transform" to transformJson(a.transform), "z_index" to CanonicalJson.int(a.zIndex)))
        is EidogramAction.SetGlyph -> CanonicalJson.obj(mapOf(
            "glyph_id" to CanonicalJson.string(a.glyphId), "instance_id" to CanonicalJson.string(a.instanceId),
            "op" to CanonicalJson.string("SET_GLYPH"), "seq" to CanonicalJson.int(a.seq)))
        is EidogramAction.SetTransform -> CanonicalJson.obj(mapOf(
            "instance_id" to CanonicalJson.string(a.instanceId), "op" to CanonicalJson.string("SET_TRANSFORM"),
            "seq" to CanonicalJson.int(a.seq), "transform" to transformJson(a.transform)))
        is EidogramAction.MoveZ -> CanonicalJson.obj(mapOf(
            "instance_id" to CanonicalJson.string(a.instanceId), "op" to CanonicalJson.string("MOVE_Z"),
            "seq" to CanonicalJson.int(a.seq), "z_index" to CanonicalJson.int(a.zIndex)))
        is EidogramAction.Remove -> CanonicalJson.obj(mapOf(
            "instance_id" to CanonicalJson.string(a.instanceId), "op" to CanonicalJson.string("REMOVE"), "seq" to CanonicalJson.int(a.seq)))
        is EidogramAction.Group -> CanonicalJson.obj(mapOf(
            "group_id" to CanonicalJson.string(a.groupId), "instance_ids" to CanonicalJson.arr(a.instanceIds.map(CanonicalJson::string)),
            "op" to CanonicalJson.string("GROUP"), "seq" to CanonicalJson.int(a.seq)))
        is EidogramAction.Ungroup -> CanonicalJson.obj(mapOf(
            "group_id" to CanonicalJson.string(a.groupId), "op" to CanonicalJson.string("UNGROUP"), "seq" to CanonicalJson.int(a.seq)))
    }
}
