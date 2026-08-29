package org.eidolang.tools

import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.session.*

fun main(){
    val alice=IdentityParser.parsePublicBundleCanonical(GoldenR20_1.ALICE_BUNDLE)
    val bob=IdentityParser.parsePublicBundleCanonical(GoldenR20_1.BOB_BUNDLE)
    check(IdentityVerifier.verifyBundle(alice))
    check(IdentityVerifier.verifyBundle(bob))
    println("PASS fixed R20.1 identity bundles")

    val conversation=ConversationParser.parseCanonical(GoldenR20_1.CONVERSATION)
    val pre=PreKeyCanonical.parseCanonical(GoldenR20_1.PREKEY_BUNDLE)
    check(PreKeyVerifier.verify(pre,bob))
    println("PASS fixed R20.1 signed prekey bundle")

    val initial=SessionCanonical.parseInitialCanonical(GoldenR20_1.INITIAL_PACKET)
    val bodyBytes=SessionCanonical.initialBodyJson(initial.body).toByteArray(Charsets.UTF_8)
    check(initial.body.sessionId==GoldenR20_1.SESSION_ID)
    check(JcaCrypto.verify(
        PublicKeyCodec.ec(alice.device.body.signingPublicKeyB64),
        bodyBytes,
        B64Url.decode(initial.senderSignatureB64)
    ))
    println("PASS fixed R20.1 initial session signature/session binding")

    var a=DoubleRatchetSession.restore(
        SessionStateCanonical.parseCanonical(GoldenR20_1.ALICE_STATE_BEFORE_RESPONSE)
    )
    val response=SessionCanonical.parseRatchetCanonical(GoldenR20_1.RESPONSE_PACKET)
    check(a.decrypt(response).toString(Charsets.UTF_8)=="golden-response")
    println("PASS fixed R20.1 P-256 DH ratchet response decryption")

    val b=DoubleRatchetSession.restore(
        SessionStateCanonical.parseCanonical(GoldenR20_1.BOB_STATE_BEFORE_OOO)
    )
    val packets=GoldenR20_1.OOO_PACKETS.map(SessionCanonical::parseRatchetCanonical)
    check(b.decrypt(packets[2]).toString(Charsets.UTF_8)=="golden-2")
    check(b.retainedSkippedKeyCount()==2)
    check(b.decrypt(packets[0]).toString(Charsets.UTF_8)=="golden-0")
    check(b.decrypt(packets[1]).toString(Charsets.UTF_8)=="golden-1")
    check(b.retainedSkippedKeyCount()==0)
    check(runCatching{b.decrypt(packets[0])}.isFailure)
    println("PASS fixed R20.1 out-of-order skipped-key consumption and erasure")

    // Canonical secret state stays reproducible, but must only ever be persisted encrypted.
    val state=SessionStateCanonical.parseCanonical(GoldenR20_1.ALICE_STATE_BEFORE_RESPONSE)
    check(SessionStateCanonical.json(state)==GoldenR20_1.ALICE_STATE_BEFORE_RESPONSE)
    println("PASS fixed R20.1 canonical secret-state vector")

    println("ALL R20.1 FIXED GOLDEN CHECKS PASS")
}
