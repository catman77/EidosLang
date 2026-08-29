package org.eidolang.core.model

data class EditorDraft(
    val actions: List<EidogramAction> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val nextInstanceNumber: Int = 1,
    val nextGroupNumber: Int = 1,
    val undo: List<List<EidogramAction>> = emptyList(),
    val redo: List<List<EidogramAction>> = emptyList(),
) {
    val document get() = EidogramDocumentV1(actions)
    val snapshot get() = EidogramReplay.replay(document)
}

sealed interface EditorCommand {
    /**
     * @param rotationMdeg and [scaleXFp] let a line be drawn between two arbitrary points: the
     * catalogue only holds eight fixed poses and three lengths, but an arbitrary angle and length
     * are just a transform over one of them.
     */
    data class AddGlyph(
        val glyphId: String,
        val cxFp: Int = 500_000,
        val cyFp: Int = 500_000,
        val rotationMdeg: Int = 0,
        val scaleXFp: Int = ProtocolLineage.FIXED_POINT_ONE,
    ) : EditorCommand
    data class Select(val ids: Set<String>) : EditorCommand
    data class TranslateSelected(val dxFp: Int, val dyFp: Int) : EditorCommand
    data class ScaleSelected(val multiplierFp: Int) : EditorCommand
    data class RotateSelected(val deltaMdeg: Int) : EditorCommand
    data class SetGlyphSelected(val glyphId: String) : EditorCommand
    data object BringForward : EditorCommand
    data object SendBackward : EditorCommand
    data object DeleteSelected : EditorCommand
    data object GroupSelected : EditorCommand
    data object UngroupSelected : EditorCommand
    data object Undo : EditorCommand
    data object Redo : EditorCommand
}

object EditorReducer {
    private fun withMutation(d: EditorDraft, newActions: List<EidogramAction>, selected: Set<String> = d.selectedIds, nextInstance: Int = d.nextInstanceNumber, nextGroup: Int = d.nextGroupNumber): EditorDraft =
        d.copy(actions = newActions, selectedIds = selected, nextInstanceNumber = nextInstance, nextGroupNumber = nextGroup, undo = d.undo + listOf(d.actions), redo = emptyList())

    private fun nextSeq(actions: List<EidogramAction>) = actions.size

    fun reduce(d: EditorDraft, c: EditorCommand): EditorDraft = when (c) {
        is EditorCommand.Select -> d.copy(selectedIds = c.ids)
        is EditorCommand.AddGlyph -> {
            EidoGlyphCatalogV1.requireGlyph(c.glyphId)
            val id = "g" + d.nextInstanceNumber.toString().padStart(6, '0')
            val z = (d.snapshot.instances.maxOfOrNull { it.zIndex } ?: -1) + 1
            val a = EidogramAction.Add(
                nextSeq(d.actions), id, c.glyphId,
                FixedTransform(c.cxFp, c.cyFp, scaleXFp = c.scaleXFp, rotationMdeg = c.rotationMdeg),
                z,
            )
            withMutation(d, d.actions + a, setOf(id), nextInstance = d.nextInstanceNumber + 1)
        }
        is EditorCommand.TranslateSelected -> transformEach(d) { t -> t.copy(cxFp = t.cxFp + c.dxFp, cyFp = t.cyFp + c.dyFp) }
        is EditorCommand.ScaleSelected -> scaleSelected(d, c.multiplierFp)
        is EditorCommand.RotateSelected -> transformEach(d) { t -> t.copy(rotationMdeg = normalizeRotation(t.rotationMdeg + c.deltaMdeg)) }
        is EditorCommand.SetGlyphSelected -> {
            EidoGlyphCatalogV1.requireGlyph(c.glyphId)
            if (d.selectedIds.isEmpty()) d else {
                var actions = d.actions
                d.selectedIds.sorted().forEach { id -> actions = actions + EidogramAction.SetGlyph(actions.size, id, c.glyphId) }
                withMutation(d, actions)
            }
        }
        EditorCommand.BringForward -> moveZ(d, +1)
        EditorCommand.SendBackward -> moveZ(d, -1)
        EditorCommand.DeleteSelected -> if (d.selectedIds.isEmpty()) d else {
            var actions = d.actions
            d.selectedIds.sorted().forEach { id -> actions = actions + EidogramAction.Remove(actions.size, id) }
            withMutation(d, actions, emptySet())
        }
        EditorCommand.GroupSelected -> if (d.selectedIds.size < 2) d else {
            val gid = "grp" + d.nextGroupNumber.toString().padStart(6, '0')
            val a = EidogramAction.Group(nextSeq(d.actions), gid, d.selectedIds.sorted())
            withMutation(d, d.actions + a, nextGroup = d.nextGroupNumber + 1)
        }
        EditorCommand.UngroupSelected -> {
            val groups = d.snapshot.instances.filter { it.instanceId in d.selectedIds }.mapNotNull { it.groupId }.toSet()
            if (groups.isEmpty()) d else {
                var actions = d.actions
                groups.sorted().forEach { gid -> actions = actions + EidogramAction.Ungroup(actions.size, gid) }
                withMutation(d, actions)
            }
        }
        EditorCommand.Undo -> if (d.undo.isEmpty()) d else d.copy(actions = d.undo.last(), undo = d.undo.dropLast(1), redo = d.redo + listOf(d.actions), selectedIds = emptySet())
        EditorCommand.Redo -> if (d.redo.isEmpty()) d else d.copy(actions = d.redo.last(), redo = d.redo.dropLast(1), undo = d.undo + listOf(d.actions), selectedIds = emptySet())
    }

    /**
     * Resize the selection - and for a line that means its length, not its weight.
     *
     * A stick's thickness is picked in the palette: thin, normal, thick. Scaling both axes made a
     * line fatter every time it was made longer, so the one property the author had deliberately
     * chosen was the one a resize silently overwrote.
     *
     * The catch is where the pose lives. A capsule is drawn along +X and then turned by its own pose
     * angle *inside* the scale, so on anything but pose000 an axis-aligned scale acts in the wrong
     * frame and shears the shape rather than lengthening it. So the pose is first folded into the
     * instance's own rotation - the identical picture, drawn as the pose000 glyph - after which the
     * length genuinely is the X axis. That is the same normalisation a dragged line is born with,
     * which is why dragged lines never showed this and placed ones did.
     */
    private fun scaleSelected(d: EditorDraft, multiplierFp: Int): EditorDraft {
        if (d.selectedIds.isEmpty()) return d
        val byId = d.snapshot.instances.associateBy { it.instanceId }
        var actions = d.actions
        d.selectedIds.sorted().forEach { id ->
            val instance = byId[id] ?: return@forEach
            val capsule = EidoGlyphCatalogV1.requireGlyph(instance.glyphId).render as? GlyphRenderSpec.Capsule
            var t = instance.transform
            if (capsule == null) {
                t = t.copy(scaleXFp = mul(t.scaleXFp, multiplierFp), scaleYFp = mul(t.scaleYFp, multiplierFp))
            } else {
                // Folding the pose in is only faithful while the two axes still agree - otherwise
                // the shape already lives in a sheared frame and rotating it would move it. That
                // cannot arise from this reducer, which never leaves a capsule anisotropic without
                // straightening it first, but a document from elsewhere could carry one.
                val straight = straightened(instance.glyphId)
                if (capsule.baseOrientationMdeg != 0 && straight != null && t.scaleXFp == t.scaleYFp) {
                    actions = actions + EidogramAction.SetGlyph(actions.size, id, straight)
                    t = t.copy(rotationMdeg = normalizeRotation(t.rotationMdeg + capsule.baseOrientationMdeg))
                }
                t = t.copy(scaleXFp = mul(t.scaleXFp, multiplierFp))
            }
            actions = actions + EidogramAction.SetTransform(actions.size, id, t)
        }
        return withMutation(d, actions)
    }

    private fun mul(v: Int, multiplierFp: Int): Int =
        ((v.toLong() * multiplierFp) / ProtocolLineage.FIXED_POINT_ONE).toInt().coerceIn(10_000, 10_000_000)

    /** `stick.black.pose045.l.normal` -> the same stick lying flat. Null if this is not a stick. */
    private fun straightened(glyphId: String): String? {
        val parts = glyphId.split('.')
        if (parts.size != 5 || parts[0] != "stick") return null
        val flat = parts.toMutableList().also { it[2] = "pose000" }.joinToString(".")
        return flat.takeIf { id -> EidoGlyphCatalogV1.glyphs.any { it.glyphId == id } }
    }

    private fun transformEach(d: EditorDraft, f: (FixedTransform) -> FixedTransform): EditorDraft {
        if (d.selectedIds.isEmpty()) return d
        val byId = d.snapshot.instances.associateBy { it.instanceId }
        var actions = d.actions
        d.selectedIds.sorted().forEach { id ->
            val old = byId[id] ?: return@forEach
            actions = actions + EidogramAction.SetTransform(actions.size, id, f(old.transform))
        }
        return withMutation(d, actions)
    }

    private fun moveZ(d: EditorDraft, delta: Int): EditorDraft {
        if (d.selectedIds.isEmpty()) return d
        val byId = d.snapshot.instances.associateBy { it.instanceId }
        var actions = d.actions
        d.selectedIds.sorted().forEach { id ->
            val old = byId[id] ?: return@forEach
            actions = actions + EidogramAction.MoveZ(actions.size, id, old.zIndex + delta)
        }
        return withMutation(d, actions)
    }

    private fun normalizeRotation(v: Int): Int {
        var x = v % 360_000
        if (x > 180_000) x -= 360_000
        if (x <= -180_000) x += 360_000
        return x
    }
}


object EditorDraftFactory {
    fun fromDocument(document: EidogramDocumentV1): EditorDraft {
        val snapshot = EidogramReplay.replay(document)
        val maxInstance = snapshot.instances
            .mapNotNull { it.instanceId.removePrefix("g").takeIf(String::isNotEmpty)?.toIntOrNull() }
            .maxOrNull() ?: 0
        val maxGroup = buildList {
            snapshot.instances.mapNotNullTo(this) { it.groupId }
            document.actions.forEach { a ->
                when (a) {
                    is EidogramAction.Group -> add(a.groupId)
                    is EidogramAction.Ungroup -> add(a.groupId)
                    else -> Unit
                }
            }
        }.mapNotNull { it.removePrefix("grp").takeIf(String::isNotEmpty)?.toIntOrNull() }
            .maxOrNull() ?: 0

        return EditorDraft(
            actions = document.actions,
            selectedIds = emptySet(),
            nextInstanceNumber = maxInstance + 1,
            nextGroupNumber = maxGroup + 1,
            undo = emptyList(),
            redo = emptyList(),
        )
    }
}
