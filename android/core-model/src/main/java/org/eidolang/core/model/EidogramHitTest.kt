package org.eidolang.core.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Geometry-aware hit testing in fixed-point document coordinates.
 *
 * This deliberately has no Android/Compose dependency so the exact same selection
 * rules are testable on the JVM and reusable by any future client.
 */
object EidogramHitTest {
    const val DEFAULT_TOUCH_SLOP_FP: Int = 18_000

    fun hitTest(
        snapshot: EidogramSnapshot,
        xFp: Int,
        yFp: Int,
        touchSlopFp: Int = DEFAULT_TOUCH_SLOP_FP,
    ): String? =
        snapshot.instances
            .sortedWith(compareByDescending<GlyphInstance> { it.zIndex }.thenByDescending { it.instanceId })
            .firstOrNull { contains(it, xFp, yFp, touchSlopFp) }
            ?.instanceId

    fun contains(instance: GlyphInstance, xFp: Int, yFp: Int, touchSlopFp: Int = DEFAULT_TOUCH_SLOP_FP): Boolean {
        val def = EidoGlyphCatalogV1.requireGlyph(instance.glyphId)
        val t = instance.transform

        // World -> instance-local: undo instance rotation, then non-uniform scale.
        val dx = (xFp - t.cxFp).toDouble()
        val dy = (yFp - t.cyFp).toDouble()
        val a = -Math.toRadians(t.rotationMdeg / 1000.0)
        val cr = cos(a)
        val sr = sin(a)
        val rx = cr * dx - sr * dy
        val ry = sr * dx + cr * dy
        val sx = t.scaleXFp.toDouble() / ProtocolLineage.FIXED_POINT_ONE
        val sy = t.scaleYFp.toDouble() / ProtocolLineage.FIXED_POINT_ONE
        val lx = rx / sx
        val ly = ry / sy
        val localSlop = touchSlopFp / min(sx, sy)

        return when (val r = def.render) {
            is GlyphRenderSpec.FilledCircle -> {
                hypot(lx, ly) <= r.diameterFp / 2.0 + localSlop
            }
            is GlyphRenderSpec.FilledTriangle -> {
                // Solid body, so a point counts as a hit when it is inside the triangle itself
                // rather than near its outline. Barycentric sign test against the same vertices
                // the renderer draws, with the touch slop applied as a small outward bias.
                val hw = r.bboxWFp / 2.0
                val hh = r.bboxHFp / 2.0
                val ax = 0.0; val ay = -hh
                val bx = hw;  val by = hh
                val cx2 = -hw; val cy2 = hh
                fun side(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double) =
                    (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
                val d1 = side(lx, ly, ax, ay, bx, by)
                val d2 = side(lx, ly, bx, by, cx2, cy2)
                val d3 = side(lx, ly, cx2, cy2, ax, ay)
                val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
                val hasPos = d1 > 0 || d2 > 0 || d3 > 0
                val inside = !(hasNeg && hasPos)
                inside || (abs(lx) <= hw + localSlop && abs(ly) <= hh + localSlop &&
                    ly >= -hh - localSlop && ly <= hh + localSlop &&
                    abs(lx) <= hw * (ly + hh) / (2 * hh) + localSlop)
            }
            is GlyphRenderSpec.Capsule -> {
                // Undo the glyph's discrete base pose as well.
                val p = -Math.toRadians(r.baseOrientationMdeg / 1000.0)
                val cp = cos(p)
                val sp = sin(p)
                val x = cp * lx - sp * ly
                val y = sp * lx + cp * ly
                val radius = r.thicknessFp / 2.0
                val halfSegment = max(0.0, (r.lengthFp - r.thicknessFp) / 2.0)
                val outsideX = max(abs(x) - halfSegment, 0.0)
                hypot(outsideX, y) <= radius + localSlop
            }
            is GlyphRenderSpec.BlackOutline -> outlineContains(
                shape = r.shape,
                x = lx,
                y = ly,
                w = r.bboxWFp.toDouble(),
                h = r.bboxHFp.toDouble(),
                stroke = r.strokeWidthFp.toDouble(),
                slop = localSlop,
            )
        }
    }

    /**
     * Selection is based on the actual primitive envelope rather than a radial
     * approximation around the glyph center. Closed outline shapes accept taps
     * inside their geometric interior; open plus/cross shapes use stroked lines.
     */
    private fun outlineContains(
        shape: String,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        stroke: Double,
        slop: Double,
    ): Boolean {
        val hw = w / 2.0
        val hh = h / 2.0
        val margin = stroke / 2.0 + slop
        return when (shape) {
            "square", "rectangle" ->
                abs(x) <= hw + margin && abs(y) <= hh + margin

            "circle", "ellipse" -> {
                val ax = hw + margin
                val ay = hh + margin
                (x * x) / (ax * ax) + (y * y) / (ay * ay) <= 1.0
            }

            "triangle" -> pointInExpandedTriangle(x, y, hw + margin, hh + margin)

            "diamond" ->
                abs(x) / (hw + margin) + abs(y) / (hh + margin) <= 1.0

            "plus" -> {
                pointSegmentDistance(x, y, -hw, 0.0, hw, 0.0) <= margin ||
                    pointSegmentDistance(x, y, 0.0, -hh, 0.0, hh) <= margin
            }

            "cross" -> {
                pointSegmentDistance(x, y, -hw, -hh, hw, hh) <= margin ||
                    pointSegmentDistance(x, y, -hw, hh, hw, -hh) <= margin
            }

            else -> false
        }
    }

    private fun pointInExpandedTriangle(x: Double, y: Double, hw: Double, hh: Double): Boolean {
        // Vertices: (0,-hh), (hw,hh), (-hw,hh)
        val x1 = 0.0; val y1 = -hh
        val x2 = hw; val y2 = hh
        val x3 = -hw; val y3 = hh
        val d1 = sign(x, y, x1, y1, x2, y2)
        val d2 = sign(x, y, x2, y2, x3, y3)
        val d3 = sign(x, y, x3, y3, x1, y1)
        val hasNeg = d1 < 0 || d2 < 0 || d3 < 0
        val hasPos = d1 > 0 || d2 > 0 || d3 > 0
        return !(hasNeg && hasPos)
    }

    private fun sign(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double =
        (px - bx) * (ay - by) - (ax - bx) * (py - by)

    private fun pointSegmentDistance(
        px: Double, py: Double,
        ax: Double, ay: Double,
        bx: Double, by: Double,
    ): Double {
        val vx = bx - ax
        val vy = by - ay
        val wx = px - ax
        val wy = py - ay
        val vv = vx * vx + vy * vy
        if (vv == 0.0) return hypot(px - ax, py - ay)
        val t = ((wx * vx + wy * vy) / vv).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * vx), py - (ay + t * vy))
    }
}

object EditorSelection {
    fun membersForHit(snapshot: EidogramSnapshot, hitId: String): Set<String> {
        val hit = snapshot.instances.firstOrNull { it.instanceId == hitId } ?: return emptySet()
        val gid = hit.groupId ?: return setOf(hitId)
        return snapshot.instances.filter { it.groupId == gid }.mapTo(linkedSetOf()) { it.instanceId }
    }

    fun single(snapshot: EidogramSnapshot, hitId: String?): Set<String> =
        if (hitId == null) emptySet() else membersForHit(snapshot, hitId)

    fun toggle(snapshot: EidogramSnapshot, current: Set<String>, hitId: String?): Set<String> {
        if (hitId == null) return current
        val members = membersForHit(snapshot, hitId)
        return if (members.all { it in current }) current - members else current + members
    }
}
