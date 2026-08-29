package org.eidolang.core.archive

import org.eidolang.core.canonical.JValue
import org.eidolang.core.canonical.StrictJsonParser

object ArchivePublisherParser {
    fun parseCanonical(text: String): ArchivePublisherCertificateV1 {
        val root = StrictJsonParser(text).parse().obj()
        root.requireKeys("body", "device_signature_b64", "publisher_key_id")
        val b = root.obj("body")
        b.requireKeys("algorithm", "device_id", "dht_public_key_raw_b64", "publisher_certificate_type", "publisher_certificate_version", "user_id")
        require(b.str("algorithm") == "ED25519_BEP44")
        require(b.str("publisher_certificate_type") == "ArchivePublisherCertificateV1")
        require(b.str("publisher_certificate_version") == "1.0.0")
        val c = ArchivePublisherCertificateV1(
            userId = b.str("user_id"),
            deviceId = b.str("device_id"),
            dhtPublicKeyRawB64 = b.str("dht_public_key_raw_b64"),
            publisherKeyId = root.str("publisher_key_id"),
            deviceSignatureB64 = root.str("device_signature_b64"),
        )
        require(ArchivePublisherCanonical.certificateJson(c) == text) { "publisher certificate is not canonical" }
        return c
    }
}

private fun JValue.obj(): JValue.Obj = this as? JValue.Obj ?: error("Expected object")
private fun JValue.str(): String = (this as? JValue.Str)?.value ?: error("Expected string")
private fun JValue.Obj.str(k: String): String = fields[k]?.str() ?: error("Missing '$k'")
private fun JValue.Obj.obj(k: String): JValue.Obj = fields[k]?.obj() ?: error("Missing '$k'")
private fun JValue.Obj.requireKeys(vararg keys: String) { require(fields.keys == keys.toSet()) }
