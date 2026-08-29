package org.eidolang.core.session

import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.repository.InsertMessageResult
import org.eidolang.core.repository.LocalMessengerService
import java.security.SecureRandom

data class AcceptedInitialDelivery(
    val session:DoubleRatchetSession,
    val repositoryResult:InsertMessageResult,
)

class ForwardSecureMessengerBridge(
    private val messenger:LocalMessengerService,
    private val random:SecureRandom=SecureRandom(),
) {
    fun initiate(
        innerMessageCanonical:String,
        recipientIdentity:PublicIdentityBundleV1,
        recipientPreKeys:DevicePreKeyBundleV1,
    ):InitiatedSession{
        val message=MessageParser.parseCanonical(innerMessageCanonical)
        val conversation=messenger.conversationDescriptor(message.body.aad.conversationId)
        require(message.body.aad.senderUserId==messenger.localIdentity.user.userId)
        require(message.body.aad.senderDeviceId==messenger.localIdentity.certificate.deviceId)
        return SessionBootstrap.initiate(
            conversation,messenger.localIdentity,recipientIdentity,recipientPreKeys,
            innerMessageCanonical.toByteArray(Charsets.UTF_8),random
        )
    }

    fun acceptInitial(
        initialCanonical:String,
        senderIdentity:PublicIdentityBundleV1,
        recipientPreKeyBundle:DevicePreKeyBundleV1,
        recipientPreKeyStore:JvmPreKeyStore,
        storedAtMs:Long,
    ):AcceptedInitialDelivery{
        val packet=SessionCanonical.parseInitialCanonical(initialCanonical)
        val conversation=messenger.conversationDescriptor(packet.body.conversationId)
        val accepted=SessionBootstrap.accept(
            conversation,messenger.localIdentity,recipientPreKeyStore,recipientPreKeyBundle,
            senderIdentity,packet,random
        )
        val inner=accepted.plaintext.toString(Charsets.UTF_8)
        val result=messenger.admitIncoming(inner,storedAtMs)
        return AcceptedInitialDelivery(accepted.session,result)
    }

    fun wrap(session:DoubleRatchetSession,innerMessageCanonical:String):String{
        MessageParser.parseCanonical(innerMessageCanonical)
        return SessionCanonical.ratchetPacketJson(
            session.encrypt(innerMessageCanonical.toByteArray(Charsets.UTF_8))
        )
    }

    fun openAndAdmit(
        session:DoubleRatchetSession,
        ratchetPacketCanonical:String,
        storedAtMs:Long,
    ):InsertMessageResult{
        val packet=SessionCanonical.parseRatchetCanonical(ratchetPacketCanonical)
        val inner=session.decrypt(packet).toString(Charsets.UTF_8)
        return messenger.admitIncoming(inner,storedAtMs)
    }
}
