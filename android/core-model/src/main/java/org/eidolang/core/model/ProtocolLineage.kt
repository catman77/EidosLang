package org.eidolang.core.model

object ProtocolLineage {
    const val MASTER_MANIFEST_HASH = "4667dcd42dec6e14aed1311525488370d141758ca8795aeb2ddfe8aecde57fd3"
    const val PRODUCTION_GRAMMAR_HASH = "d5afb09e6ecdcff8a4b2c9758af83f215059d1b5bc4ad32da3fb8dd191b69b19"
    const val PRODUCTION_BINDING_HASH = "130eed81541292dfc8ccfd48ba01a28c374cd8fa96285c51fb3ea978d530944a"
    /**
     * The catalogue *family*, checked strictly on parse — so it must not move when the catalogue
     * merely gains glyphs, or every document ever written stops loading. Which revision a document
     * was authored against is [GLYPH_CATALOG_HASH], carried per document and per message.
     */
    const val GLYPH_CATALOG_ID = "EidoGlyphCatalogV1"
    /** Derived from the catalogue's contents; `CatalogTest` fails if the two drift apart. */
    const val GLYPH_CATALOG_HASH = "363f96f2573791fecb584e2b10909599d38a9e0d4437893b66eae802f90931bb"
    const val DOCUMENT_TYPE = "EidogramDocumentV1"
    const val DOCUMENT_VERSION = "1.1.0"
    const val FIXED_POINT_ONE = 1_000_000
    const val CANVAS_W_FP = 1_000_000
    const val CANVAS_H_FP = 1_000_000
}
