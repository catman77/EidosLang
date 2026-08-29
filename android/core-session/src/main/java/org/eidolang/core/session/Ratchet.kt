package org.eidolang.core.session

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import org.eidolang.core.message.ConversationDescriptorV1
import java.security.KeyPair
import java.security.PublicKey
import java.security.SecureRandom

data class RatchetHeaderV1(
    val sessionId: String,
    val dhPublicB64: String,
    val previousChainLength: Int,
    val messageNumber: Int,
)

data class RatchetPacketV1(
    val header: RatchetHeaderV1,
    val nonceB64: String,
    val ciphertextB64: String,
    val packetId: String,
)

data class InitialSessionBodyV1(
    val conversationId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val senderSigningKeyId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val preKeyBundleId: String,
    val signedPreKeyId: String,
    val oneTimePreKeyId: String,
    val senderEphemeralPublicB64: String,
    val sessionId: String,
    val nonceB64: String,
    val ciphertextB64: String,
)

data class InitialSessionPacketV1(
    val body: InitialSessionBodyV1,
    val packetId: String,
    val senderSignatureB64: String,
)

object SessionCanonical {
    fun sessionIdBodyJson(
        conversationId:String,
        senderUserId:String,
        senderDeviceId:String,
        recipientUserId:String,
        recipientDeviceId:String,
        preKeyBundleId:String,
        senderEphemeralPublicB64:String,
    ):String=CanonicalJson.obj(mapOf(
        "conversation_id" to CanonicalJson.string(conversationId),
        "prekey_bundle_id" to CanonicalJson.string(preKeyBundleId),
        "protocol" to CanonicalJson.string(SessionSuiteV1.PROTOCOL),
        "protocol_version" to CanonicalJson.string(SessionSuiteV1.VERSION),
        "recipient_device_id" to CanonicalJson.string(recipientDeviceId),
        "recipient_user_id" to CanonicalJson.string(recipientUserId),
        "sender_device_id" to CanonicalJson.string(senderDeviceId),
        "sender_ephemeral_public_b64" to CanonicalJson.string(senderEphemeralPublicB64),
        "sender_user_id" to CanonicalJson.string(senderUserId),
    ))

    fun initialBodyJson(b:InitialSessionBodyV1):String=CanonicalJson.obj(mapOf(
        "ciphertext_b64" to CanonicalJson.string(b.ciphertextB64),
        "conversation_id" to CanonicalJson.string(b.conversationId),
        "nonce_b64" to CanonicalJson.string(b.nonceB64),
        "one_time_prekey_id" to CanonicalJson.string(b.oneTimePreKeyId),
        "packet_type" to CanonicalJson.string("EidoInitialSessionPacketV1"),
        "packet_version" to CanonicalJson.string("1.0.0"),
        "prekey_bundle_id" to CanonicalJson.string(b.preKeyBundleId),
        "recipient_device_id" to CanonicalJson.string(b.recipientDeviceId),
        "recipient_user_id" to CanonicalJson.string(b.recipientUserId),
        "sender_device_id" to CanonicalJson.string(b.senderDeviceId),
        "sender_ephemeral_public_b64" to CanonicalJson.string(b.senderEphemeralPublicB64),
        "sender_signing_key_id" to CanonicalJson.string(b.senderSigningKeyId),
        "sender_user_id" to CanonicalJson.string(b.senderUserId),
        "session_id" to CanonicalJson.string(b.sessionId),
        "signed_prekey_id" to CanonicalJson.string(b.signedPreKeyId),
    ))

    fun initialPacketJson(p:InitialSessionPacketV1):String=CanonicalJson.obj(mapOf(
        "body" to initialBodyJson(p.body),
        "packet_id" to CanonicalJson.string(p.packetId),
        "sender_signature_b64" to CanonicalJson.string(p.senderSignatureB64),
    ))

    fun ratchetHeaderJson(h:RatchetHeaderV1):String=CanonicalJson.obj(mapOf(
        "dh_public_b64" to CanonicalJson.string(h.dhPublicB64),
        "message_number" to CanonicalJson.int(h.messageNumber),
        "previous_chain_length" to CanonicalJson.int(h.previousChainLength),
        "session_id" to CanonicalJson.string(h.sessionId),
    ))

    fun ratchetBodyJson(p:RatchetPacketV1):String=CanonicalJson.obj(mapOf(
        "ciphertext_b64" to CanonicalJson.string(p.ciphertextB64),
        "header" to ratchetHeaderJson(p.header),
        "nonce_b64" to CanonicalJson.string(p.nonceB64),
        "packet_type" to CanonicalJson.string("EidoRatchetPacketV1"),
        "packet_version" to CanonicalJson.string("1.0.0"),
    ))

    fun ratchetPacketJson(p:RatchetPacketV1):String=CanonicalJson.obj(mapOf(
        "body" to ratchetBodyJson(p),
        "packet_id" to CanonicalJson.string(p.packetId),
    ))

    fun parseRatchetCanonical(text:String):RatchetPacketV1{
        val root=StrictJsonParser(text).parse().obj()
        root.requireKeys("body","packet_id")
        val b=root.obj("body")
        b.requireKeys("ciphertext_b64","header","nonce_b64","packet_type","packet_version")
        require(b.str("packet_type")=="EidoRatchetPacketV1")
        require(b.str("packet_version")=="1.0.0")
        val h=b.obj("header")
        h.requireKeys("dh_public_b64","message_number","previous_chain_length","session_id")
        val out=RatchetPacketV1(
            RatchetHeaderV1(
                h.str("session_id"),h.str("dh_public_b64"),
                h.int("previous_chain_length"),h.int("message_number")
            ),
            b.str("nonce_b64"),b.str("ciphertext_b64"),root.str("packet_id")
        )
        require(out.header.messageNumber>=0 && out.header.previousChainLength>=0)
        require(HexSha256.ofUtf8(ratchetBodyJson(out))==out.packetId)
        require(ratchetPacketJson(out)==text){"Ratchet packet not canonical"}
        return out
    }

    fun parseInitialCanonical(text:String):InitialSessionPacketV1{
        val root=StrictJsonParser(text).parse().obj()
        root.requireKeys("body","packet_id","sender_signature_b64")
        val b=root.obj("body")
        b.requireKeys(
            "ciphertext_b64","conversation_id","nonce_b64","one_time_prekey_id",
            "packet_type","packet_version","prekey_bundle_id","recipient_device_id",
            "recipient_user_id","sender_device_id","sender_ephemeral_public_b64",
            "sender_signing_key_id","sender_user_id","session_id","signed_prekey_id"
        )
        require(b.str("packet_type")=="EidoInitialSessionPacketV1")
        require(b.str("packet_version")=="1.0.0")
        val out=InitialSessionPacketV1(
            InitialSessionBodyV1(
                b.str("conversation_id"),b.str("sender_user_id"),b.str("sender_device_id"),
                b.str("sender_signing_key_id"),b.str("recipient_user_id"),b.str("recipient_device_id"),
                b.str("prekey_bundle_id"),b.str("signed_prekey_id"),b.str("one_time_prekey_id"),
                b.str("sender_ephemeral_public_b64"),b.str("session_id"),b.str("nonce_b64"),b.str("ciphertext_b64")
            ),
            root.str("packet_id"),root.str("sender_signature_b64")
        )
        require(HexSha256.ofUtf8(initialBodyJson(out.body))==out.packetId)
        require(initialPacketJson(out)==text){"Initial session packet not canonical"}
        return out
    }
}

private data class SkippedKeyId(val dhPublicB64:String,val n:Int)

data class SessionSkippedKeyV1(
    val dhPublicB64:String,
    val messageNumber:Int,
    val messageKeyB64:String,
)

data class SessionSecretSnapshotV1(
    val sessionId:String,
    val conversationId:String,
    val localUserId:String,
    val localDeviceId:String,
    val remoteUserId:String,
    val remoteDeviceId:String,
    val rootKeyB64:String,
    val sendingChainKeyB64:String,
    val receivingChainKeyB64:String,
    val selfDhPrivateB64:String,
    val selfDhPublicB64:String,
    val remoteDhPublicB64:String,
    val sendN:Int,
    val recvN:Int,
    val previousSendN:Int,
    val needsSendRatchet:Boolean,
    val skippedKeys:List<SessionSkippedKeyV1>,
)

class DoubleRatchetSession internal constructor(
    val sessionId:String,
    val conversationId:String,
    val localUserId:String,
    val localDeviceId:String,
    val remoteUserId:String,
    val remoteDeviceId:String,
    private var rootKey:ByteArray,
    private var sendingChainKey:ByteArray,
    private var receivingChainKey:ByteArray,
    private var selfDh:KeyPair,
    private var remoteDh:PublicKey,
    private var sendN:Int,
    private var recvN:Int,
    private var previousSendN:Int,
    private var needsSendRatchet:Boolean,
    private val random:SecureRandom=SecureRandom(),
    val maxSkip:Int=256,
) {
    private val skipped=linkedMapOf<SkippedKeyId,ByteArray>()

    fun encrypt(plaintext:ByteArray):RatchetPacketV1{
        if(needsSendRatchet) performSendRatchet()
        val header=RatchetHeaderV1(
            sessionId,
            PublicKeyCodec.encode(selfDh.public),
            previousSendN,
            sendN,
        )
        val (mk,next)=RatchetKdf.messageStep(sendingChainKey)
        sendingChainKey=next
        val nonce=JcaCrypto.randomBytes(CryptoSuiteV1.GCM_NONCE_BYTES,random)
        val aad=SessionCanonical.ratchetHeaderJson(header).toByteArray(Charsets.UTF_8)
        val ct=try{
            JcaCrypto.encryptAesGcm(mk,nonce,aad,plaintext)
        }finally{mk.fill(0)}
        sendN++
        val provisional=RatchetPacketV1(header,B64Url.encode(nonce),B64Url.encode(ct),"")
        return provisional.copy(packetId=HexSha256.ofUtf8(SessionCanonical.ratchetBodyJson(provisional)))
    }

    fun decrypt(packet:RatchetPacketV1):ByteArray{
        val checkpoint=exportSecretSnapshot()
        return try{
            decryptMutating(packet)
        }catch(t:Throwable){
            restoreInPlace(checkpoint)
            throw t
        }
    }

    private fun decryptMutating(packet:RatchetPacketV1):ByteArray{
        require(packet.header.sessionId==sessionId){"Session mismatch"}
        require(HexSha256.ofUtf8(SessionCanonical.ratchetBodyJson(packet))==packet.packetId){"Packet hash mismatch"}

        val skippedId=SkippedKeyId(packet.header.dhPublicB64,packet.header.messageNumber)
        val saved=skipped.remove(skippedId)
        if(saved!=null){
            return decryptWith(saved,packet)
        }

        val currentRemote=PublicKeyCodec.encode(remoteDh)
        if(packet.header.dhPublicB64!=currentRemote){
            skipCurrentChainUntil(packet.header.previousChainLength)
            performReceiveRatchet(PublicKeyCodec.ec(packet.header.dhPublicB64))
        }else{
            require(packet.header.messageNumber>=recvN){"Old message key has already been erased"}
        }

        require(packet.header.messageNumber-recvN<=maxSkip){"Skip limit exceeded"}
        skipCurrentChainUntil(packet.header.messageNumber)
        val (mk,next)=RatchetKdf.messageStep(receivingChainKey)
        receivingChainKey=next
        recvN++
        return decryptWith(mk,packet)
    }

    fun erasedHistoryFloorForCurrentRemote():Int=recvN
    fun retainedSkippedKeyCount():Int=skipped.size
    fun currentDhPublicB64():String=PublicKeyCodec.encode(selfDh.public)

    fun exportSecretSnapshot():SessionSecretSnapshotV1 =
        SessionSecretSnapshotV1(
            sessionId,conversationId,localUserId,localDeviceId,remoteUserId,remoteDeviceId,
            B64Url.encode(rootKey),B64Url.encode(sendingChainKey),B64Url.encode(receivingChainKey),
            B64Url.encode(selfDh.private.encoded),PublicKeyCodec.encode(selfDh.public),
            PublicKeyCodec.encode(remoteDh),sendN,recvN,previousSendN,needsSendRatchet,
            skipped.entries
                .sortedWith(compareBy<Map.Entry<SkippedKeyId,ByteArray>> { it.key.dhPublicB64 }.thenBy { it.key.n })
                .map { SessionSkippedKeyV1(it.key.dhPublicB64,it.key.n,B64Url.encode(it.value)) }
        )

    companion object {
        fun restore(
            snapshot:SessionSecretSnapshotV1,
            random:SecureRandom=SecureRandom(),
            maxSkip:Int=256,
        ):DoubleRatchetSession{
            val session=DoubleRatchetSession(
                snapshot.sessionId,snapshot.conversationId,snapshot.localUserId,snapshot.localDeviceId,
                snapshot.remoteUserId,snapshot.remoteDeviceId,
                B64Url.decode(snapshot.rootKeyB64),B64Url.decode(snapshot.sendingChainKeyB64),
                B64Url.decode(snapshot.receivingChainKeyB64),
                java.security.KeyPair(
                    PublicKeyCodec.ec(snapshot.selfDhPublicB64),
                    PublicKeyCodec.privateEc(snapshot.selfDhPrivateB64),
                ),
                PublicKeyCodec.ec(snapshot.remoteDhPublicB64),
                snapshot.sendN,snapshot.recvN,snapshot.previousSendN,snapshot.needsSendRatchet,
                random,maxSkip
            )
            snapshot.skippedKeys.forEach {
                session.skipped[SkippedKeyId(it.dhPublicB64,it.messageNumber)]=B64Url.decode(it.messageKeyB64)
            }
            require(session.skipped.size<=maxSkip)
            return session
        }
    }

    private fun restoreInPlace(snapshot:SessionSecretSnapshotV1){
        rootKey.fill(0);sendingChainKey.fill(0);receivingChainKey.fill(0)
        skipped.values.forEach{it.fill(0)}
        rootKey=B64Url.decode(snapshot.rootKeyB64)
        sendingChainKey=B64Url.decode(snapshot.sendingChainKeyB64)
        receivingChainKey=B64Url.decode(snapshot.receivingChainKeyB64)
        selfDh=java.security.KeyPair(
            PublicKeyCodec.ec(snapshot.selfDhPublicB64),
            PublicKeyCodec.privateEc(snapshot.selfDhPrivateB64)
        )
        remoteDh=PublicKeyCodec.ec(snapshot.remoteDhPublicB64)
        sendN=snapshot.sendN;recvN=snapshot.recvN;previousSendN=snapshot.previousSendN
        needsSendRatchet=snapshot.needsSendRatchet
        skipped.clear()
        snapshot.skippedKeys.forEach{
            skipped[SkippedKeyId(it.dhPublicB64,it.messageNumber)]=B64Url.decode(it.messageKeyB64)
        }
    }

    private fun decryptWith(mk:ByteArray,packet:RatchetPacketV1):ByteArray=try{
        JcaCrypto.decryptAesGcm(
            mk,B64Url.decode(packet.nonceB64),
            SessionCanonical.ratchetHeaderJson(packet.header).toByteArray(Charsets.UTF_8),
            B64Url.decode(packet.ciphertextB64)
        )
    }finally{mk.fill(0)}

    private fun skipCurrentChainUntil(until:Int){
        require(until>=recvN)
        require(until-recvN<=maxSkip){"Skip limit exceeded"}
        while(recvN<until){
            val (mk,next)=RatchetKdf.messageStep(receivingChainKey)
            receivingChainKey=next
            val id=SkippedKeyId(PublicKeyCodec.encode(remoteDh),recvN)
            skipped[id]=mk
            recvN++
            require(skipped.size<=maxSkip){"Skipped-key store limit exceeded"}
        }
    }

    private fun performSendRatchet(){
        val nextDh=P256Dh.generate(random)
        val step=RatchetKdf.rootStep(rootKey,P256Dh.agree(nextDh.private,remoteDh))
        rootKey.fill(0)
        rootKey=step.rootKey
        sendingChainKey.fill(0)
        sendingChainKey=step.chainKey
        previousSendN=sendN
        sendN=0
        selfDh=nextDh
        needsSendRatchet=false
    }

    private fun performReceiveRatchet(newRemote:PublicKey){
        // Derive the receiving chain under current local DH and the sender's new DH.
        val recvStep=RatchetKdf.rootStep(rootKey,P256Dh.agree(selfDh.private,newRemote))
        rootKey.fill(0)
        rootKey=recvStep.rootKey
        receivingChainKey.fill(0)
        receivingChainKey=recvStep.chainKey
        remoteDh=newRemote
        recvN=0

        // Immediately create our next DH value and derive the response chain.
        val nextSelf=P256Dh.generate(random)
        val sendStep=RatchetKdf.rootStep(rootKey,P256Dh.agree(nextSelf.private,remoteDh))
        rootKey.fill(0)
        rootKey=sendStep.rootKey
        sendingChainKey.fill(0)
        sendingChainKey=sendStep.chainKey
        previousSendN=sendN
        sendN=0
        selfDh=nextSelf
        needsSendRatchet=false
    }
}

data class InitiatedSession(
    val session:DoubleRatchetSession,
    val packet:InitialSessionPacketV1,
)

data class AcceptedSession(
    val session:DoubleRatchetSession,
    val plaintext:ByteArray,
)

object SessionBootstrap {
    fun initiate(
        conversation:ConversationDescriptorV1,
        sender:DevicePrivateCrypto,
        recipientIdentity:PublicIdentityBundleV1,
        recipientPreKeys:DevicePreKeyBundleV1,
        plaintext:ByteArray,
        random:SecureRandom=SecureRandom(),
    ):InitiatedSession{
        require(sender.user.userId in conversation.participantUserIds)
        require(recipientIdentity.user.userId in conversation.participantUserIds)
        require(PreKeyVerifier.verify(recipientPreKeys,recipientIdentity)){"Invalid recipient prekey bundle"}

        val eph=P256Dh.generate(random)
        val ephPub=PublicKeyCodec.encode(eph.public)
        val sidBody=SessionCanonical.sessionIdBodyJson(
            conversation.conversationId,sender.user.userId,sender.certificate.deviceId,
            recipientIdentity.user.userId,recipientIdentity.device.deviceId,
            recipientPreKeys.bundleId,ephPub
        )
        val sessionId=HexSha256.ofUtf8(sidBody)

        val dh1=P256Dh.agree(eph.private,PublicKeyCodec.ec(recipientPreKeys.body.signedPreKeyPublicB64))
        val dh2=P256Dh.agree(eph.private,PublicKeyCodec.ec(recipientPreKeys.body.oneTimePreKeyPublicB64))
        val ikm=dh1+dh2
        dh1.fill(0);dh2.fill(0)
        val (root,i2r,r2i)=RatchetKdf.initialChains(ikm,sessionId)
        ikm.fill(0)

        val (mk,nextI2r)=RatchetKdf.messageStep(i2r)
        i2r.fill(0)
        val nonce=JcaCrypto.randomBytes(CryptoSuiteV1.GCM_NONCE_BYTES,random)

        val aad=CanonicalJson.obj(mapOf(
            "conversation_id" to CanonicalJson.string(conversation.conversationId),
            "recipient_device_id" to CanonicalJson.string(recipientIdentity.device.deviceId),
            "sender_device_id" to CanonicalJson.string(sender.certificate.deviceId),
            "session_id" to CanonicalJson.string(sessionId),
        )).toByteArray(Charsets.UTF_8)
        val ct=try{JcaCrypto.encryptAesGcm(mk,nonce,aad,plaintext)}finally{mk.fill(0)}

        val body=InitialSessionBodyV1(
            conversation.conversationId,sender.user.userId,sender.certificate.deviceId,
            sender.certificate.body.signingKeyId,recipientIdentity.user.userId,recipientIdentity.device.deviceId,
            recipientPreKeys.bundleId,recipientPreKeys.body.signedPreKeyId,recipientPreKeys.body.oneTimePreKeyId,
            ephPub,sessionId,B64Url.encode(nonce),B64Url.encode(ct)
        )
        val bodyBytes=SessionCanonical.initialBodyJson(body).toByteArray(Charsets.UTF_8)
        val packet=InitialSessionPacketV1(
            body,HexSha256.of(bodyBytes),B64Url.encode(sender.signMessage(bodyBytes))
        )
        val session=DoubleRatchetSession(
            sessionId,conversation.conversationId,sender.user.userId,sender.certificate.deviceId,
            recipientIdentity.user.userId,recipientIdentity.device.deviceId,
            root,nextI2r,r2i,eph,PublicKeyCodec.ec(recipientPreKeys.body.signedPreKeyPublicB64),
            sendN=1,recvN=0,previousSendN=0,needsSendRatchet=false,random=random
        )
        return InitiatedSession(session,packet)
    }

    fun accept(
        conversation:ConversationDescriptorV1,
        recipient:DevicePrivateCrypto,
        preKeyStore:JvmPreKeyStore,
        preKeyBundle:DevicePreKeyBundleV1,
        senderIdentity:PublicIdentityBundleV1,
        packet:InitialSessionPacketV1,
        random:SecureRandom=SecureRandom(),
    ):AcceptedSession{
        require(packet.body.conversationId==conversation.conversationId)
        require(packet.body.senderUserId==senderIdentity.user.userId)
        require(packet.body.senderDeviceId==senderIdentity.device.deviceId)
        require(packet.body.senderSigningKeyId==senderIdentity.device.body.signingKeyId)
        require(packet.body.recipientUserId==recipient.user.userId)
        require(packet.body.recipientDeviceId==recipient.certificate.deviceId)
        require(packet.body.preKeyBundleId==preKeyBundle.bundleId)
        require(packet.body.signedPreKeyId==preKeyBundle.body.signedPreKeyId)
        require(packet.body.oneTimePreKeyId==preKeyBundle.body.oneTimePreKeyId)
        require(PreKeyVerifier.verify(preKeyBundle,PublicIdentityBundleV1(recipient.user,recipient.certificate)))

        val expectedSid=HexSha256.ofUtf8(SessionCanonical.sessionIdBodyJson(
            conversation.conversationId,packet.body.senderUserId,packet.body.senderDeviceId,
            packet.body.recipientUserId,packet.body.recipientDeviceId,
            packet.body.preKeyBundleId,packet.body.senderEphemeralPublicB64
        ))
        require(expectedSid==packet.body.sessionId){"Session id mismatch"}

        val bodyBytes=SessionCanonical.initialBodyJson(packet.body).toByteArray(Charsets.UTF_8)
        require(packet.packetId==HexSha256.of(bodyBytes))
        require(JcaCrypto.verify(
            PublicKeyCodec.ec(senderIdentity.device.body.signingPublicKeyB64),
            bodyBytes,B64Url.decode(packet.senderSignatureB64)
        )){"Initial packet sender signature invalid"}

        // One-time prekey is consumed before plaintext release. Failed AEAD consumes the prekey
        // as well, preventing active replay/reuse of the same asynchronous key material.
        val consumed=preKeyStore.consume(preKeyBundle)
        val eph=PublicKeyCodec.ec(packet.body.senderEphemeralPublicB64)
        val dh1=P256Dh.agree(consumed.signedPreKey.private,eph)
        val dh2=P256Dh.agree(consumed.oneTimePreKey.private,eph)
        val ikm=dh1+dh2;dh1.fill(0);dh2.fill(0)
        val (root,i2r,r2i)=RatchetKdf.initialChains(ikm,packet.body.sessionId)
        ikm.fill(0)

        val (mk,nextI2r)=RatchetKdf.messageStep(i2r)
        i2r.fill(0)
        val aad=CanonicalJson.obj(mapOf(
            "conversation_id" to CanonicalJson.string(conversation.conversationId),
            "recipient_device_id" to CanonicalJson.string(recipient.certificate.deviceId),
            "sender_device_id" to CanonicalJson.string(senderIdentity.device.deviceId),
            "session_id" to CanonicalJson.string(packet.body.sessionId),
        )).toByteArray(Charsets.UTF_8)
        val plaintext=try{
            JcaCrypto.decryptAesGcm(
                mk,B64Url.decode(packet.body.nonceB64),aad,B64Url.decode(packet.body.ciphertextB64)
            )
        }finally{mk.fill(0)}

        val session=DoubleRatchetSession(
            packet.body.sessionId,conversation.conversationId,recipient.user.userId,recipient.certificate.deviceId,
            senderIdentity.user.userId,senderIdentity.device.deviceId,
            root,r2i,nextI2r,consumed.signedPreKey,eph,
            sendN=0,recvN=1,previousSendN=0,needsSendRatchet=true,random=random
        )
        return AcceptedSession(session,plaintext)
    }
}

private fun JValue.obj():JValue.Obj=this as? JValue.Obj?:error("Expected object")
private fun JValue.str():String=(this as? JValue.Str)?.value?:error("Expected string")
private fun JValue.Obj.obj(k:String):JValue.Obj=fields[k]?.obj()?:error("Missing $k")
private fun JValue.Obj.str(k:String):String=fields[k]?.str()?:error("Missing $k")
private fun JValue.Obj.int(k:String):Int{
    val v=(fields[k] as? JValue.IntNum)?.value?:error("Missing int $k")
    require(v in 0..Int.MAX_VALUE.toLong());return v.toInt()
}
private fun JValue.Obj.requireKeys(vararg keys:String){require(fields.keys==keys.toSet()){"Schema mismatch"}}
