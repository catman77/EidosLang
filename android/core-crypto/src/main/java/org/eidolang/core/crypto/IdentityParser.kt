package org.eidolang.core.crypto

import org.eidolang.core.canonical.JValue
import org.eidolang.core.canonical.StrictJsonParser

object IdentityParser {
    fun parsePublicBundleCanonical(text: String): PublicIdentityBundleV1 {
        val root = StrictJsonParser(text).parse().obj()
        root.requireKeys("bundle_type", "bundle_version", "device", "user")
        require(root.str("bundle_type") == "EidoPublicIdentityBundleV1")
        require(root.str("bundle_version") == "1.0.0")

        val u = root.obj("user")
        u.requireKeys("root_signing_public_key_b64", "signature_algorithm", "user_id")
        val user = UserIdentityV1(
            userId = u.str("user_id"),
            rootSigningPublicKeyB64 = u.str("root_signing_public_key_b64"),
            signatureAlgorithm = u.str("signature_algorithm"),
        )

        val d = root.obj("device")
        d.requireKeys("body", "device_id", "root_signature_b64")
        val b = d.obj("body")
        b.requireKeys(
            "device_certificate_type", "device_certificate_version", "encryption_key_algorithm",
            "encryption_key_id", "encryption_public_key_b64", "signature_algorithm",
            "signing_key_id", "signing_public_key_b64", "user_id"
        )
        require(b.str("device_certificate_type") == "EidoDeviceCertificateV1")
        require(b.str("device_certificate_version") == "1.0.0")
        val body = DeviceCertificateBodyV1(
            userId = b.str("user_id"),
            signingKeyId = b.str("signing_key_id"),
            signingPublicKeyB64 = b.str("signing_public_key_b64"),
            encryptionKeyId = b.str("encryption_key_id"),
            encryptionPublicKeyB64 = b.str("encryption_public_key_b64"),
            signatureAlgorithm = b.str("signature_algorithm"),
            encryptionKeyAlgorithm = b.str("encryption_key_algorithm"),
        )
        val bundle = PublicIdentityBundleV1(
            user = user,
            device = DeviceCertificateV1(
                body = body,
                deviceId = d.str("device_id"),
                rootSignatureB64 = d.str("root_signature_b64"),
            ),
        )
        require(IdentityVerifier.verifyBundle(bundle)) { "Invalid public identity bundle" }
        require(IdentityCanonical.publicBundleJson(bundle) == text) { "Identity bundle is not canonical" }
        return bundle
    }
}

private fun JValue.obj(): JValue.Obj = this as? JValue.Obj ?: error("Expected object")
private fun JValue.str(): String = (this as? JValue.Str)?.value ?: error("Expected string")
private fun JValue.Obj.str(k: String): String = fields[k]?.str() ?: error("Missing '$k'")
private fun JValue.Obj.obj(k: String): JValue.Obj = fields[k]?.obj() ?: error("Missing '$k'")
private fun JValue.Obj.requireKeys(vararg keys: String) {
    require(fields.keys == keys.toSet()) { "Object schema mismatch" }
}
