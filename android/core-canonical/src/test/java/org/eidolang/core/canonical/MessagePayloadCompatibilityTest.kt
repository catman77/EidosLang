package org.eidolang.core.canonical

import org.eidolang.core.model.EidogramDocumentV1
import org.junit.Assert.*
import org.junit.Test

class MessagePayloadCompatibilityTest {
    @Test fun ordinaryMessagesKeepReleasedWireShapeAndLegacyDocumentsStillOpen() {
        val document = EidogramDocumentV1(emptyList())
        val oldShape = "{\"caption\":\"hello\",\"document\":" + EidogramCanonical.documentJson(document) +
            ",\"payload_version\":\"eido-message-payload-v1\"}"
        assertEquals(oldShape, EidoMessagePayloadCanonical.json(EidoMessagePayloadV1(document, "hello")))
        assertEquals(EidoMessagePayloadV1(document, ""), EidoMessagePayloadCanonical.parseCanonical(EidogramCanonical.documentJson(document)))
    }

    @Test fun encryptedServicePayloadRoundTripsAlongsideWordsWithoutBecomingOrdinaryText() {
        val payload = EidoMessagePayloadV1(EidogramDocumentV1(emptyList()), "Служебное сообщение", "{\"type\":\"fixture\"}")
        val bytes = EidoMessagePayloadCanonical.json(payload)
        assertEquals(payload, EidoMessagePayloadCanonical.parseCanonical(bytes))
        assertTrue(runCatching { EidoMessagePayloadCanonical.parseCanonical(bytes.replace("\"control\":\"", "\"unexpected\":\"")) }.isFailure)
    }
}
