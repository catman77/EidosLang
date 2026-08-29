package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import java.security.SecureRandom
import java.util.Base64

private fun doc() = EidogramDocumentV1(listOf(
    EidogramAction.Add(0,"g000001","circle.violet.l",FixedTransform(360_000,480_000),0),
    EidogramAction.Add(1,"g000002","dot.black.s",FixedTransform(500_000,500_000),1),
    EidogramAction.Add(2,"g000003","outline.triangle",FixedTransform(660_000,520_000,1_100_000,1_100_000,15_000),2),
))

private fun b64Text(s:String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))

fun main() {
    val rnd=SecureRandom()
    val alice=JvmPrivateIdentity.generate(rnd)
    val bob=JvmPrivateIdentity.generate(rnd)
    val conversation=ConversationFactory.fromSeed(ByteArray(16){(0xA0+it).toByte()}, listOf(alice.user.userId,bob.user.userId))
    val d=doc()
    val msg=MessageCrypto.seal(d,conversation,alice,listOf(MessageCrypto.recipient(bob.certificate)),7,1_786_400_123_456L,random=rnd)

    println("ALICE_BUNDLE_JSON_B64="+b64Text(IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(alice.user,alice.certificate))))
    println("BOB_BUNDLE_JSON_B64="+b64Text(IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(bob.user,bob.certificate))))
    println("CONVERSATION_JSON_B64="+b64Text(ConversationCanonical.descriptorJson(conversation)))
    println("MESSAGE_JSON_B64="+b64Text(MessageCanonical.envelopeJson(msg)))
    println("ALICE_ROOT_PRIVATE_B64="+B64Url.encode(alice.rootSigning.private.encoded))
    println("ALICE_SIGN_PRIVATE_B64="+B64Url.encode(alice.deviceSigning.private.encoded))
    println("ALICE_ENC_PRIVATE_B64="+B64Url.encode(alice.deviceEncryption.private.encoded))
    println("BOB_ROOT_PRIVATE_B64="+B64Url.encode(bob.rootSigning.private.encoded))
    println("BOB_SIGN_PRIVATE_B64="+B64Url.encode(bob.deviceSigning.private.encoded))
    println("BOB_ENC_PRIVATE_B64="+B64Url.encode(bob.deviceEncryption.private.encoded))
    println("MESSAGE_ID="+msg.messageId)
    println("DOCUMENT_CONTENT_HASH="+EidogramCanonical.contentHash(d))
    println("DOCUMENT_SNAPSHOT_HASH="+EidogramCanonical.snapshotHash(d))
}
