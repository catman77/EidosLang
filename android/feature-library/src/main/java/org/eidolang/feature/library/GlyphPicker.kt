package org.eidolang.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.eidolang.core.model.*
import org.eidolang.core.render.GlyphPreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphPicker(onGlyph:(String)->Unit, modifier:Modifier=Modifier) {
    var family by remember { mutableStateOf(GlyphFamily.COLORED_CIRCLE) }
    Column(modifier.padding(8.dp)) {
        SingleChoiceSegmentedButtonRow(modifier=Modifier.fillMaxWidth()) {
            GlyphFamily.entries.forEachIndexed { index,f ->
                SegmentedButton(
                    selected=family==f,
                    onClick={family=f},
                    shape=SegmentedButtonDefaults.itemShape(index,GlyphFamily.entries.size),
                    label={ Text(when(f){
                        GlyphFamily.COLORED_CIRCLE->"Круги"
                        GlyphFamily.COLORED_TRIANGLE->"Треуг."
                        GlyphFamily.BLACK_DOT->"Точки"
                        GlyphFamily.BLACK_STICK->"Палочки"
                        GlyphFamily.BLACK_OUTLINE->"Контуры"
                    }) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            items(EidoGlyphCatalogV1.family(family),key={it.glyphId}) { g ->
                OutlinedCard(onClick={onGlyph(g.glyphId)},border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(6.dp)) {
                        GlyphPreview(g.glyphId,Modifier.size(58.dp))
                        Text(g.glyphId.substringAfterLast('.'),style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
