package org.eidolang.core.canonical

import org.eidolang.core.model.EidogramDocumentV1

/**
 * What a message actually carries: the eidogram plus the sender's own words for it.
 *
 * Up to R22 the encrypted plaintext was the canonical eidogram document and nothing else, on the
 * principle that meaning is discovered by the reader rather than declared by the author. R22.2
 * carries an author-written caption alongside the form, by an explicit product decision recorded in
 * `R22_2_METHODOLOGY.md`. That is a deliberate departure from `EidoLang_v1.md`, not an oversight.
 *
 * Wire compatibility: a payload is recognised by the `payload_version` key. Anything without it —
 * every message written before R22.2, and every golden vector — is read as a bare document with no
 * caption, so old archives and the R15/R16 fixtures keep verifying untouched.
 */
data class EidoMessagePayloadV1(
    val document: EidogramDocumentV1,
    val caption: String,
) {
    init {
        require(caption.length <= MAX_CAPTION) { "caption exceeds $MAX_CAPTION characters" }
    }

    companion object {
        const val VERSION = "eido-message-payload-v1"
        const val MAX_CAPTION = 2000
    }
}

object EidoMessagePayloadCanonical {

    fun json(p: EidoMessagePayloadV1): String = CanonicalJson.obj(
        mapOf(
            "caption" to CanonicalJson.string(p.caption),
            "document" to EidogramCanonical.documentJson(p.document),
            "payload_version" to CanonicalJson.string(EidoMessagePayloadV1.VERSION),
        )
    )

    /**
     * Parse either shape. Pre-R22.2 plaintext is the document itself; the wrapper is only assumed
     * when `payload_version` is present and correct.
     */
    fun parseCanonical(text: String): EidoMessagePayloadV1 {
        val root = StrictJsonParser(text).parse() as? JValue.Obj
            ?: error("Message payload must be an object")
        val version = (root.fields["payload_version"] as? JValue.Str)?.value
        if (version == null) {
            return EidoMessagePayloadV1(EidogramParser.parseCanonical(text), "")
        }
        require(version == EidoMessagePayloadV1.VERSION) { "Unsupported payload version $version" }
        require(root.fields.keys == setOf("caption", "document", "payload_version")) {
            "Unexpected payload fields: ${root.fields.keys}"
        }
        val caption = (root.fields["caption"] as? JValue.Str)?.value
            ?: error("Missing caption")
        val document = root.fields["document"] as? JValue.Obj ?: error("Missing document")
        // Re-serialise the nested object so the document parser sees exactly canonical bytes.
        return EidoMessagePayloadV1(
            EidogramParser.parseCanonical(CanonicalReserialize.of(document)),
            caption,
        )
    }
}

/**
 * Canonical re-serialisation of an already-parsed object.
 *
 * The document parser is strict about byte form, so a nested object has to be written back out in
 * canonical order rather than sliced out of the original text.
 */
object CanonicalReserialize {
    fun of(v: JValue): String = when (v) {
        is JValue.Obj -> CanonicalJson.obj(v.fields.mapValues { of(it.value) })
        is JValue.Arr -> CanonicalJson.arr(v.items.map { of(it) })
        is JValue.Str -> CanonicalJson.string(v.value)
        is JValue.IntNum -> v.value.toString()
        JValue.Null -> "null"
    }
}
