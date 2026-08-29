package org.eidolang.tools

import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.session.*
import java.security.SecureRandom
import java.util.Base64

private fun b64(s:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
private fun bundle(d:DevicePrivateCrypto)=PublicIdentityBundleV1(d.user,d.certificate)
private fun raw(d:DevicePrivateCrypto)=IdentityCanonical.publicBundleJson(bundle(d))

fun main(){
    val rnd=SecureRandom()
    val alice=JvmPrivateIdentity.generate(rnd)
    val bob=JvmPrivateIdentity.generate(rnd)
    val conversation=ConversationFactory.fromSeed(
        ByteArray(16){(0x41+it).toByte()},listOf(alice.user.userId,bob.user.userId)
    )
    val store=JvmPreKeyStore(bob,rnd)
    val pre=store.createBundle()

    val init=SessionBootstrap.initiate(
        conversation,alice,bundle(bob),pre,"golden-initial".toByteArray(),rnd
    )
    val accepted=SessionBootstrap.accept(
        conversation,bob,store,pre,bundle(alice),init.packet,rnd
    )
    check(accepted.plaintext.toString(Charsets.UTF_8)=="golden-initial")

    // Snapshot Alice before receiving Bob's fresh-DH response.
    val aliceBeforeResponse=SessionStateCanonical.json(init.session.exportSecretSnapshot())
    val response=accepted.session.encrypt("golden-response".toByteArray())
    val responseRaw=SessionCanonical.ratchetPacketJson(response)
    check(init.session.decrypt(response).toString(Charsets.UTF_8)=="golden-response")

    // Snapshot Bob before an out-of-order receive on Alice's new sending chain.
    val bobBeforeOoO=SessionStateCanonical.json(accepted.session.exportSecretSnapshot())
    val p0=init.session.encrypt("golden-0".toByteArray())
    val p1=init.session.encrypt("golden-1".toByteArray())
    val p2=init.session.encrypt("golden-2".toByteArray())

    println("ALICE_BUNDLE="+b64(raw(alice)))
    println("BOB_BUNDLE="+b64(raw(bob)))
    println("CONVERSATION="+b64(ConversationCanonical.descriptorJson(conversation)))
    println("PREKEY_BUNDLE="+b64(PreKeyCanonical.json(pre)))
    println("INITIAL_PACKET="+b64(SessionCanonical.initialPacketJson(init.packet)))
    println("ALICE_BEFORE_RESPONSE="+b64(aliceBeforeResponse))
    println("RESPONSE_PACKET="+b64(responseRaw))
    println("BOB_BEFORE_OOO="+b64(bobBeforeOoO))
    println("PACKET0="+b64(SessionCanonical.ratchetPacketJson(p0)))
    println("PACKET1="+b64(SessionCanonical.ratchetPacketJson(p1)))
    println("PACKET2="+b64(SessionCanonical.ratchetPacketJson(p2)))
    println("SESSION_ID="+init.session.sessionId)
}
