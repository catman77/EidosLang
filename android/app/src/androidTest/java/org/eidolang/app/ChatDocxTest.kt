package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.model.EidogramAction
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.model.FixedTransform
import org.eidolang.core.repository.MessageLocalState
import org.eidolang.core.repository.TimelineItem
import org.eidolang.feature.home.ChatDocx
import org.eidolang.feature.home.renderEidogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

/**
 * The exported conversation has to be a document Word will actually open.
 *
 * A `.docx` is a zip, so "it produced a file" proves nothing — a broken one is still a file, and the
 * person only finds out when their reader refuses it. The parts a reader requires are checked by
 * name, the XML is checked for well-formedness, and a caption containing the characters that break
 * XML is put through on purpose: `&` and `<` in somebody's own words would otherwise produce a
 * document that fails to open with no clue as to why.
 */
class ChatDocxTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun item(caption: String, outgoing: Boolean) = TimelineItem(
        messageId = "m".repeat(64), senderUserId = "u".repeat(64), senderDeviceId = "dev:x",
        senderSeq = 1, createdAtMs = 1_700_000_000_000, parentMessageIds = emptyList(),
        outgoing = outgoing, localState = MessageLocalState.ACCEPTED,
        document = EidogramDocumentV1(
            listOf(EidogramAction.Add(0, "g000001", "circle.red.m", FixedTransform(500_000, 500_000), 0))
        ),
        caption = caption,
    )

    @Test
    fun theExportIsAWellFormedDocumentEvenWithHostileText() {
        val hostile = "тест & <разметка> \"кавычки\"\nвторая строка"
        val file = ChatDocx.write(
            ctx, "Собеседник & Ко",
            listOf(item(hostile, true), item("обычная подпись", false)),
            { renderEidogram(it.document, 256) },
            "docx-test.docx",
        )
        assertTrue("файл не создан", file.isFile && file.length() > 0)

        ZipFile(file).use { zip ->
            val names = zip.entries().toList().map { it.name }.toSet()
            listOf(
                "[Content_Types].xml", "_rels/.rels",
                "word/document.xml", "word/_rels/document.xml.rels",
            ).forEach { assertTrue("в документе нет части $it (Word такой не откроет)", it in names) }
            assertTrue("картинки эйдограмм не вложены", names.any { it.startsWith("word/media/") })

            // Well-formed, not merely present. An unescaped ampersand parses here and nowhere else.
            names.filter { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { part ->
                val text = zip.getInputStream(zip.getEntry(part)).readBytes().toString(Charsets.UTF_8)
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(text.byteInputStream())
            }
            val doc = zip.getInputStream(zip.getEntry("word/document.xml")).readBytes()
                .toString(Charsets.UTF_8)
            assertTrue("подпись не попала в документ", doc.contains("тест &amp; &lt;разметка&gt;"))
            assertTrue("перевод строки потерялся", doc.contains("<w:br/>"))
            // One relationship per image, or the picture shows as a red cross.
            val rels = zip.getInputStream(zip.getEntry("word/_rels/document.xml.rels")).readBytes()
                .toString(Charsets.UTF_8)
            assertEquals(
                "число картинок и ссылок на них разошлось",
                names.count { it.startsWith("word/media/") },
                Regex("relationships/image").findAll(rels).count(),
            )
        }
        println("DOCX PASS выгрузка открывается как документ: ${file.length()} байт")
    }
}
