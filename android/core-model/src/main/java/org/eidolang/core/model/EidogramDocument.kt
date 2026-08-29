package org.eidolang.core.model

data class FixedTransform(
    val cxFp: Int,
    val cyFp: Int,
    val scaleXFp: Int = ProtocolLineage.FIXED_POINT_ONE,
    val scaleYFp: Int = ProtocolLineage.FIXED_POINT_ONE,
    val rotationMdeg: Int = 0,
) {
    init {
        require(cxFp in -2_000_000..3_000_000)
        require(cyFp in -2_000_000..3_000_000)
        require(scaleXFp in 10_000..10_000_000)
        require(scaleYFp in 10_000..10_000_000)
        require(rotationMdeg in -360_000..360_000)
    }
}

data class GlyphInstance(
    val instanceId: String,
    val glyphId: String,
    val transform: FixedTransform,
    val zIndex: Int,
    val groupId: String? = null,
)

sealed interface EidogramAction {
    val seq: Int
    data class Add(
        override val seq: Int,
        val instanceId: String,
        val glyphId: String,
        val transform: FixedTransform,
        val zIndex: Int,
    ) : EidogramAction
    data class SetGlyph(override val seq: Int, val instanceId: String, val glyphId: String) : EidogramAction
    data class SetTransform(override val seq: Int, val instanceId: String, val transform: FixedTransform) : EidogramAction
    data class MoveZ(override val seq: Int, val instanceId: String, val zIndex: Int) : EidogramAction
    data class Remove(override val seq: Int, val instanceId: String) : EidogramAction
    data class Group(override val seq: Int, val groupId: String, val instanceIds: List<String>) : EidogramAction
    data class Ungroup(override val seq: Int, val groupId: String) : EidogramAction
}

data class EidogramDocumentV1(
    val actions: List<EidogramAction>,
    val canvasWFp: Int = ProtocolLineage.CANVAS_W_FP,
    val canvasHFp: Int = ProtocolLineage.CANVAS_H_FP,
    /**
     * Which catalogue this eidogram was drawn against.
     *
     * The catalogue is a research artefact and keeps growing, so its hash is provenance rather than
     * an admission gate. Carrying it on the document means an eidogram written under an older
     * catalogue re-serialises to exactly the bytes it had — its content hash, and every archive and
     * signature built on it, survive the catalogue changing underneath.
     */
    val catalogHash: String = ProtocolLineage.GLYPH_CATALOG_HASH,
)

data class EidogramSnapshot(val instances: List<GlyphInstance>)

object EidogramReplay {
    fun replay(document: EidogramDocumentV1): EidogramSnapshot {
        val m = linkedMapOf<String, GlyphInstance>()
        document.actions.forEachIndexed { expected, action ->
            require(action.seq == expected) { "Non-contiguous action seq: expected=$expected actual=${action.seq}" }
            when (action) {
                is EidogramAction.Add -> {
                    require(action.instanceId !in m) { "Duplicate instance_id" }
                    EidoGlyphCatalogV1.requireGlyph(action.glyphId)
                    m[action.instanceId] = GlyphInstance(action.instanceId, action.glyphId, action.transform, action.zIndex)
                }
                is EidogramAction.SetGlyph -> {
                    EidoGlyphCatalogV1.requireGlyph(action.glyphId)
                    val old = requireNotNull(m[action.instanceId]) { "Unknown instance_id" }
                    m[action.instanceId] = old.copy(glyphId = action.glyphId)
                }
                is EidogramAction.SetTransform -> {
                    val old = requireNotNull(m[action.instanceId]) { "Unknown instance_id" }
                    m[action.instanceId] = old.copy(transform = action.transform)
                }
                is EidogramAction.MoveZ -> {
                    val old = requireNotNull(m[action.instanceId]) { "Unknown instance_id" }
                    m[action.instanceId] = old.copy(zIndex = action.zIndex)
                }
                is EidogramAction.Remove -> requireNotNull(m.remove(action.instanceId)) { "Unknown instance_id" }
                is EidogramAction.Group -> {
                    require(action.instanceIds.size >= 2) { "Group requires >=2 instances" }
                    action.instanceIds.forEach { id ->
                        val old = requireNotNull(m[id]) { "Unknown instance_id" }
                        m[id] = old.copy(groupId = action.groupId)
                    }
                }
                is EidogramAction.Ungroup -> m.keys.toList().forEach { id ->
                    val old = m.getValue(id)
                    if (old.groupId == action.groupId) m[id] = old.copy(groupId = null)
                }
            }
        }
        return EidogramSnapshot(m.values.sortedWith(compareBy<GlyphInstance> { it.zIndex }.thenBy { it.instanceId }))
    }
}
