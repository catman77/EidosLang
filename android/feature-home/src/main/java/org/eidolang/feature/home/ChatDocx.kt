package org.eidolang.feature.home

import android.content.Context
import android.graphics.Bitmap
import org.eidolang.core.repository.TimelineItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A conversation written out as a Word document.
 *
 * The format is assembled by hand rather than with a library: a `.docx` is a zip of a few XML parts,
 * and the subset needed here — paragraphs, bold runs and inline PNGs — is small and stable. Adding
 * Apache POI for it would pull tens of megabytes and a large surface into an app whose whole
 * argument is that it depends on very little.
 *
 * Each eidogram is rendered to a picture, because that is what an eidogram *is* to a reader; the
 * author's own words go underneath it. The export is a reading copy and nothing more — it carries
 * no keys, no message ids and no signatures, and nothing can be imported back from it. An eidogram
 * outside the app has lost the thing that made it verifiable, and the document says so on its face.
 */
object ChatDocx {

    fun write(
        context: Context,
        title: String,
        items: List<TimelineItem>,
        renderer: (TimelineItem) -> Bitmap?,
        fileName: String,
    ): File {
        val images = mutableListOf<Pair<String, ByteArray>>()
        val body = StringBuilder()
        body.append(heading(title))
        body.append(
            paragraph(
                "Выгружено ${stamp(System.currentTimeMillis())}. " +
                    "Это читаемая копия: подписи, ключи и идентификаторы сообщений в неё не входят, " +
                    "и обратно в приложение она не загружается.",
                italic = true,
            )
        )
        items.forEach { item ->
            val who = if (item.outgoing) "Вы" else title
            body.append(paragraph("$who · ${stamp(item.createdAtMs)}", bold = true))
            renderer(item)?.let { bitmap ->
                val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    .toByteArray()
                val id = "img${images.size + 1}"
                images += "$id.png" to png
                body.append(image(id, images.size, bitmap.width, bitmap.height))
            }
            if (item.caption.isNotBlank()) body.append(paragraph(item.caption))
            body.append(paragraph(""))
        }

        val file = Sharing.exportFile(context, fileName)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", RELS)
            zip.put("word/document.xml", DOC_OPEN + body + DOC_CLOSE)
            zip.put("word/_rels/document.xml.rels", documentRels(images.map { it.first }))
            images.forEach { (name, bytes) -> zip.putBytes("word/media/$name", bytes) }
        }
        return file
    }

    private fun ZipOutputStream.put(name: String, text: CharSequence) =
        putBytes(name, text.toString().toByteArray(Charsets.UTF_8))

    private fun ZipOutputStream.putBytes(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    /**
     * Escaped, always.
     *
     * Captions are the author's own free text and go straight into XML; an unescaped `&` or `<`
     * produces a file Word refuses to open, and the person would have no idea why.
     */
    private fun esc(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
        // Word rejects control characters outright rather than ignoring them.
        .filter { it >= ' ' || it == '\n' }
        .replace("\n", "</w:t><w:br/><w:t xml:space=\"preserve\">")

    private fun paragraph(text: String, bold: Boolean = false, italic: Boolean = false): String {
        val props = buildString {
            if (bold || italic) {
                append("<w:rPr>")
                if (bold) append("<w:b/>")
                if (italic) append("<w:i/><w:color w:val=\"666666\"/>")
                append("</w:rPr>")
            }
        }
        return "<w:p><w:r>$props<w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r></w:p>"
    }

    private fun heading(text: String) =
        "<w:p><w:r><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr>" +
            "<w:t xml:space=\"preserve\">${esc(text)}</w:t></w:r></w:p>"

    /** EMUs: Word measures in 914400 per inch, and a pixel here is taken as 1/96 inch. */
    private fun image(id: String, index: Int, wPx: Int, hPx: Int): String {
        val cx = (wPx.toLong() * 914400 / 96).coerceAtMost(5486400)
        val cy = (hPx.toLong() * 914400 / 96).coerceAtMost(5486400)
        return """<w:p><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0">
<wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$index" name="$id"/>
<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
<pic:nvPicPr><pic:cNvPr id="$index" name="$id"/><pic:cNvPicPr/></pic:nvPicPr>
<pic:blipFill><a:blip r:embed="rId$index"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>
<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr>
</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
    }

    private fun documentRels(images: List<String>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        images.forEachIndexed { i, name ->
            append(
                """<Relationship Id="rId${i + 1}" """ +
                    """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" """ +
                    """Target="media/$name"/>"""
            )
        }
        append("</Relationships>")
    }

    private fun stamp(ms: Long) = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale("ru"))
        .format(java.util.Date(ms))

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="png" ContentType="image/png"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rIdDoc" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private const val DOC_OPEN = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
 xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>"""

    private const val DOC_CLOSE = """</w:body></w:document>"""

    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
}
