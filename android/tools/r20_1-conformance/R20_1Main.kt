package org.eidolang.tools

import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.repository.*
import org.eidolang.core.session.*
import java.security.SecureRandom

private fun doc(glyph:String,x:Int=500_000)=EidogramDocumentV1(
    listOf(EidogramAction.Add(0,"g000001",glyph,FixedTransform(x,500_000),0))
)
private fun bundle(d:DevicePrivateCrypto)=PublicIdentityBundleV1(d.user,d.certificate)
private fun rawBundle(d:DevicePrivateCrypto)=IdentityCanonical.publicBundleJson(bundle(d))

private fun hex(s:String):ByteArray {
    require(s.length%2==0)
    return ByteArray(s.length/2){i->s.substring(i*2,i*2+2).toInt(16).toByte()}
}
private fun ByteArray.hex():String=joinToString(""){"%02x".format(it)}

fun main(){
    // RFC 5869 A.1, SHA-256.
    val ikm=ByteArray(22){0x0b}
    val salt=hex("000102030405060708090a0b0c")
    val info=hex("f0f1f2f3f4f5f6f7f8f9")
    val prk=HkdfSha256.extract(salt,ikm)
    val okm=HkdfSha256.expand(prk,info,42)
    check(prk.hex()=="077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
    check(okm.hex()=="3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    println("PASS RFC5869 HKDF-SHA256 vector A.1")

    val random=SecureRandom()
    val aliceId=JvmPrivateIdentity.generate(random)
    val bobId=JvmPrivateIdentity.generate(random)
    val ar=InMemoryMessengerRepository()
    val br=InMemoryMessengerRepository()
    val alice=LocalMessengerService(ar,aliceId,random)
    val bob=LocalMessengerService(br,bobId,random)
    alice.importContact(rawBundle(bobId),"Bob",100)
    bob.importContact(rawBundle(aliceId),"Alice",100)
    val conversation=alice.createConversation(
        listOf(bobId.user.userId),ByteArray(16){(0x11+it).toByte()},"FS",1_000
    )
    bob.importConversation(alice.conversationDescriptorCanonical(conversation.conversationId),"FS",1_001)

    var bobPreKeys=JvmPreKeyStore(bobId,random)
    val preBundle=bobPreKeys.createBundle()
    check(PreKeyVerifier.verify(preBundle,bundle(bobId)))
    val brokenBundle=preBundle.copy(deviceSignatureB64=
        preBundle.deviceSignatureB64.dropLast(1)+(if(preBundle.deviceSignatureB64.last()=='A')"B" else "A"))
    check(!PreKeyVerifier.verify(brokenBundle,bundle(bobId)))
    check(bobPreKeys.pendingOneTimePreKeyCount()==1)
    println("PASS signed P-256 prekey bundle authentication")

    val preStateRaw=PreKeyStateCanonical.json(bobPreKeys.exportSecretSnapshot())
    bobPreKeys=JvmPreKeyStore.restore(
        bobId,
        PreKeyStateCanonical.parseCanonical(preStateRaw),
        random,
    )
    check(PreKeyStateCanonical.json(bobPreKeys.exportSecretSnapshot())==preStateRaw)
    check(bobPreKeys.pendingOneTimePreKeyCount()==1)
    println("PASS persistent prekey secret state round-trip before asynchronous use")

    val aBridge=ForwardSecureMessengerBridge(alice,random)
    val bBridge=ForwardSecureMessengerBridge(bob,random)

    val first=alice.send(conversation.conversationId,doc("circle.red.m",400_000),2_000)
    val init=aBridge.initiate(MessageCanonical.envelopeJson(first),bundle(bobId),preBundle)
    val initRaw=SessionCanonical.initialPacketJson(init.packet)
    val accepted=bBridge.acceptInitial(initRaw,bundle(aliceId),preBundle,bobPreKeys,2_010)
    var aSession=init.session
    val bSession=accepted.session
    check(accepted.repositoryResult==InsertMessageResult.Inserted)
    check(bobPreKeys.pendingOneTimePreKeyCount()==0)
    check(bob.timeline(conversation.conversationId).single().messageId==first.messageId)
    check(runCatching{
        bBridge.acceptInitial(initRaw,bundle(aliceId),preBundle,bobPreKeys,2_020)
    }.isFailure)
    println("PASS asynchronous session bootstrap consumes one-time prekey exactly once")

    // Bob's first response performs the first DH ratchet.
    val reply=bob.send(conversation.conversationId,doc("outline.circle",600_000),3_000)
    val responseRaw=SessionCanonical.ratchetPacketJson(
        bSession.encrypt(MessageCanonical.envelopeJson(reply).toByteArray(Charsets.UTF_8))
    )
    check(aBridge.openAndAdmit(aSession,responseRaw,3_010)==InsertMessageResult.Inserted)
    check(alice.timeline(conversation.conversationId).last().messageId==reply.messageId)
    println("PASS responder first send advances the DH/root ratchet")

    // Send three messages on Alice's new chain, receive them out of order.
    val follow=mutableListOf<Pair<EidogramMessageV1,String>>()
    for((i,g) in listOf("outline.triangle","circle.violet.s","stick.black.pose090.l.thick").withIndex()){
        val m=alice.send(conversation.conversationId,doc(g,350_000+i*120_000),4_000L+i)
        val raw=aBridge.wrap(aSession,MessageCanonical.envelopeJson(m))
        follow += m to raw
    }
    check(bBridge.openAndAdmit(bSession,follow[2].second,4_100)==InsertMessageResult.Inserted)
    check(bSession.retainedSkippedKeyCount()==2)
    check(bBridge.openAndAdmit(bSession,follow[0].second,4_101)==InsertMessageResult.Inserted)
    check(bBridge.openAndAdmit(bSession,follow[1].second,4_102)==InsertMessageResult.Inserted)
    check(bSession.retainedSkippedKeyCount()==0)
    val timelineIds=bob.timeline(conversation.conversationId).map{it.messageId}
    check(timelineIds.takeLast(3)==follow.map{it.first.messageId})
    println("PASS bounded skipped-message keys recover out-of-order ratchet packets")

    // Consumed historical message key is no longer retained.
    val oldPacket=SessionCanonical.parseRatchetCanonical(follow[0].second)
    val compromisedCurrentState=DoubleRatchetSession.restore(bSession.exportSecretSnapshot(),random)
    check(runCatching{compromisedCurrentState.decrypt(oldPacket)}.isFailure)
    println("PASS compromise of current ratchet state does not recover an erased historical message key")

    // Secret-state persistence is exact when encrypted by the Android storage adapter later.
    val stateRaw=SessionStateCanonical.json(aSession.exportSecretSnapshot())
    val parsedState=SessionStateCanonical.parseCanonical(stateRaw)
    aSession=DoubleRatchetSession.restore(parsedState,random)
    check(SessionStateCanonical.json(aSession.exportSecretSnapshot())==stateRaw)
    println("PASS canonical secret-session state round-trip")

    val persistedMessage=alice.send(conversation.conversationId,doc("dot.black.m"),5_000)
    val persistedPacket=aBridge.wrap(aSession,MessageCanonical.envelopeJson(persistedMessage))
    check(bBridge.openAndAdmit(bSession,persistedPacket,5_010)==InsertMessageResult.Inserted)
    println("PASS restored ratchet state continues the same live session")

    // AEAD failure must not advance/poison receive state.
    val next=alice.send(conversation.conversationId,doc("circle.cyan.l"),5_100)
    val goodRaw=aBridge.wrap(aSession,MessageCanonical.envelopeJson(next))
    val good=SessionCanonical.parseRatchetCanonical(goodRaw)
    val ct=B64Url.decode(good.ciphertextB64).also{it[0]=(it[0].toInt() xor 1).toByte()}
    val tampered0=good.copy(ciphertextB64=B64Url.encode(ct),packetId="")
    val tampered=tampered0.copy(packetId=HexSha256.ofUtf8(SessionCanonical.ratchetBodyJson(tampered0)))
    check(runCatching{bSession.decrypt(tampered)}.isFailure)
    check(bBridge.openAndAdmit(bSession,goodRaw,5_110)==InsertMessageResult.Inserted)
    println("PASS failed AEAD decrypt rolls ratchet state back transactionally")

    // Initial packet signature protects prekey consumption from network bit flipping.
    val initPacket=SessionCanonical.parseInitialCanonical(initRaw)
    val initCt=B64Url.decode(initPacket.body.ciphertextB64).also{it[0]=(it[0].toInt() xor 1).toByte()}
    val modifiedBody=initPacket.body.copy(ciphertextB64=B64Url.encode(initCt))
    val modified=initPacket.copy(
        body=modifiedBody,
        packetId=HexSha256.ofUtf8(SessionCanonical.initialBodyJson(modifiedBody))
        // signature deliberately remains the old one
    )
    check(runCatching{
        SessionBootstrap.accept(
            conversation,bobId,JvmPreKeyStore(bobId,random),preBundle,bundle(aliceId),modified,random
        )
    }.isFailure)
    println("PASS initial ciphertext tamper fails sender signature before plaintext release")

    println("SESSION_ID=${aSession.sessionId}")
    println("FINAL_BOB_HEAD=${bob.currentHeads(conversation.conversationId).single()}")
    println("ALL R20.1 FORWARD-SESSION CHECKS PASS")
}
