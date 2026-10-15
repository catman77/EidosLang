package org.eidolang.core.crypto

import org.eidolang.core.canonical.*
import java.security.KeyPair

/** Secret material. Only include its encoding inside an authenticated encrypted backup. */
class PortableIdentity(
    val bundleCanonical: String,
    val signingPrivateB64: String,
    val encryptionPrivateB64: String,
    val rootPrivateB64: String? = null,
) : DevicePrivateCrypto {
    private val bundle = IdentityParser.parsePublicBundleCanonical(bundleCanonical)
    override val user get() = bundle.user
    override val certificate get() = bundle.device
    private val signing get() = PublicKeyCodec.privateEc(signingPrivateB64)
    private val encryption get() = PublicKeyCodec.privateRsa(encryptionPrivateB64)
    override fun signMessage(bytes: ByteArray) = JcaCrypto.sign(signing, bytes)
    override fun unwrapMessageKey(wrapped: ByteArray) = JcaCrypto.unwrapKey(encryption, wrapped)

    fun validate() {
        val challenge = JcaCrypto.randomBytes(32)
        check(JcaCrypto.verify(PublicKeyCodec.ec(certificate.body.signingPublicKeyB64), challenge, signMessage(challenge)))
        val wrapped = JcaCrypto.wrapKey(PublicKeyCodec.rsa(certificate.body.encryptionPublicKeyB64), challenge)
        val opened = unwrapMessageKey(wrapped)
        try { check(challenge.contentEquals(opened)) } finally { opened.fill(0) }
        rootPrivateB64?.let {
            check(JcaCrypto.verify(PublicKeyCodec.ec(user.rootSigningPublicKeyB64), challenge,
                JcaCrypto.sign(PublicKeyCodec.privateEc(it), challenge)))
        }
        challenge.fill(0)
    }

    fun json(): String = CanonicalJson.obj(mapOf(
        "bundle" to CanonicalJson.string(bundleCanonical),
        "encryption_private" to CanonicalJson.string(encryptionPrivateB64),
        "root_private" to (rootPrivateB64?.let(CanonicalJson::string) ?: "null"),
        "signing_private" to CanonicalJson.string(signingPrivateB64),
    ))

    fun signingPair() = KeyPair(PublicKeyCodec.ec(certificate.body.signingPublicKeyB64), signing)
    fun encryptionPair() = KeyPair(PublicKeyCodec.rsa(certificate.body.encryptionPublicKeyB64), encryption)
    fun rootPair(): KeyPair? = rootPrivateB64?.let {
        KeyPair(PublicKeyCodec.ec(user.rootSigningPublicKeyB64), PublicKeyCodec.privateEc(it))
    }

    companion object {
        fun parse(text: String): PortableIdentity {
            val o = StrictJsonParser(text).parse() as JValue.Obj
            require(o.fields.keys == setOf("bundle", "encryption_private", "root_private", "signing_private"))
            fun str(key: String) = (o.fields[key] as JValue.Str).value
            return PortableIdentity(str("bundle"), str("signing_private"), str("encryption_private"),
                (o.fields["root_private"] as? JValue.Str)?.value).also {
                require(it.json() == text)
                it.validate()
            }
        }
    }
}
