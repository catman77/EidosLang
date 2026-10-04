package org.eidolang.core.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import org.eidolang.core.model.*
import kotlin.math.min

private fun color(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

fun DrawScope.drawGlyph(instance: GlyphInstance, canvasWFp:Int=1_000_000, canvasHFp:Int=1_000_000, selected:Boolean=false) {
    val def=EidoGlyphCatalogV1.requireGlyph(instance.glyphId)
    val t=instance.transform
    val sx=size.width/canvasWFp.toFloat(); val sy=size.height/canvasHFp.toFloat(); val unit=min(sx,sy)
    val cx=t.cxFp*sx; val cy=t.cyFp*sy
    val scaleX=t.scaleXFp/1_000_000f; val scaleY=t.scaleYFp/1_000_000f
    translate(cx,cy) {
        // Pivot must be explicit. DrawScope.rotate and DrawScope.scale default their pivot to the
        // centre of the whole canvas, not to the origin translate() just established, so a rotated
        // glyph swung around the middle of the screen and a scaled one drifted away from its own
        // position. Same default that put filled circles half a canvas off before.
        rotate(t.rotationMdeg/1000f, pivot = Offset.Zero) {
            scale(scaleX,scaleY, pivot = Offset.Zero) {
                when(val r=def.render) {
                    is GlyphRenderSpec.FilledCircle -> {
                        val d=r.diameterFp*unit
                        // center must be explicit: DrawScope.drawCircle defaults to the centre of
                        // the whole canvas, which lands every filled circle half a canvas away
                        // from where translate() just put the origin.
                        drawCircle(color(EidoGlyphCatalogV1.palette[r.paletteIndex].hexSrgb), radius=d/2f, center=Offset.Zero)
                        if (selected) drawCircle(Color(0xFF007AFF),radius=d/2f+5f,center=Offset.Zero,style=Stroke(3f))
                    }
                    is GlyphRenderSpec.FilledTriangle -> {
                        val w=r.bboxWFp*unit; val h=r.bboxHFp*unit
                        val path=Path().apply{moveTo(0f,-h/2);lineTo(w/2,h/2);lineTo(-w/2,h/2);close()}
                        drawPath(path,color(EidoGlyphCatalogV1.palette[r.paletteIndex].hexSrgb))
                        if(selected) drawRect(Color(0xFF007AFF),topLeft=Offset(-w/2-5,-h/2-5),size=Size(w+10,h+10),style=Stroke(3f))
                    }
                    is GlyphRenderSpec.FilledSquare -> {
                        val edge=r.edgeFp*unit
                        drawRect(color(EidoGlyphCatalogV1.palette[r.paletteIndex].hexSrgb),topLeft=Offset(-edge/2,-edge/2),size=Size(edge,edge))
                        if(selected) drawRect(Color(0xFF007AFF),topLeft=Offset(-edge/2-5,-edge/2-5),size=Size(edge+10,edge+10),style=Stroke(3f))
                    }
                    is GlyphRenderSpec.Capsule -> {
                        rotate(r.baseOrientationMdeg/1000f, pivot = Offset.Zero) {
                            val w=r.lengthFp*unit; val h=r.thicknessFp*unit
                            drawRoundRect(color(EidoGlyphCatalogV1.palette[r.paletteIndex].hexSrgb),topLeft=Offset(-w/2,-h/2),size=Size(w,h),cornerRadius=androidx.compose.ui.geometry.CornerRadius(h/2,h/2))
                            if(selected) drawRoundRect(Color(0xFF007AFF),topLeft=Offset(-w/2-4,-h/2-4),size=Size(w+8,h+8),cornerRadius=androidx.compose.ui.geometry.CornerRadius(h/2+4,h/2+4),style=Stroke(3f))
                        }
                    }
                    is GlyphRenderSpec.BlackOutline -> {
                        val w=r.bboxWFp*unit; val h=r.bboxHFp*unit; val sw=r.strokeWidthFp*unit
                        val c=color(EidoGlyphCatalogV1.palette[r.paletteIndex].hexSrgb)
                        drawOutline(r.shape,w,h,sw,c)
                        if(selected) drawRect(Color(0xFF007AFF),topLeft=Offset(-w/2-5,-h/2-5),size=Size(w+10,h+10),style=Stroke(3f))
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawOutline(shape:String,w:Float,h:Float,sw:Float,c:Color) {
    val stroke=Stroke(width=sw.coerceAtLeast(1f))
    when(shape) {
        "square","rectangle" -> drawRect(c,Offset(-w/2,-h/2),Size(w,h),style=stroke)
        "circle","ellipse" -> drawOval(c,Offset(-w/2,-h/2),Size(w,h),style=stroke)
        "triangle" -> { val p=Path().apply{moveTo(0f,-h/2);lineTo(w/2,h/2);lineTo(-w/2,h/2);close()}; drawPath(p,c,style=stroke) }
        "diamond" -> { val p=Path().apply{moveTo(0f,-h/2);lineTo(w/2,0f);lineTo(0f,h/2);lineTo(-w/2,0f);close()}; drawPath(p,c,style=stroke) }
        "plus" -> { drawLine(c,Offset(-w/2,0f),Offset(w/2,0f),sw); drawLine(c,Offset(0f,-h/2),Offset(0f,h/2),sw) }
        "cross" -> { drawLine(c,Offset(-w/2,-h/2),Offset(w/2,h/2),sw); drawLine(c,Offset(-w/2,h/2),Offset(w/2,-h/2),sw) }
    }
}

@Composable
fun GlyphPreview(glyphId:String, modifier:Modifier=Modifier) {
    Canvas(modifier) {
        drawGlyph(GlyphInstance("preview",glyphId,FixedTransform(500_000,500_000),0))
    }
}
