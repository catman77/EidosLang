package org.eidolang.core.vault

import org.eidolang.core.canonical.EidoMessagePayloadCanonical
import org.eidolang.core.canonical.EidogramParser
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.multidevice.DeviceRosterCanonical
import org.eidolang.core.multidevice.DeviceRosterVerifier
import org.eidolang.core.repository.*
import java.security.SecureRandom
import java.util.PriorityQueue

class HistoryVaultDocumentProvider(
    packageCanonical:String,
    private val recoverySecret:HistoryVaultRecoverySecret,
) : HistoricalDocumentProvider {
    private val pkg=HistoryVaultArchivePackageCodec.parseCanonical(packageCanonical)
    private val descriptor=HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
    private val epochKeys:Map<String,ByteArray> = pkg.epochCanonicalJson
        .map(HistoryVaultEpochCodec::parseCanonical)
        .associate{it.epochId to HistoryVaultCrypto.openEpoch(descriptor,recoverySecret,it)}
    private val entries:Map<String,HistoryVaultEntryV1> = pkg.entryCanonicalJson
        .map(HistoryVaultEntryCodec::parseCanonical)
        .associateBy{it.body.messageId}

    override fun documentFor(messageId:String):EidogramDocumentV1?{
        val e=entries[messageId]?:return null
        val key=epochKeys[e.body.epochId]?:error("Vault epoch key missing")
        val payload=HistoryVaultCrypto.openEntry(descriptor,key,e)
        // R22.2 plaintext is a payload wrapper; pre-R22.2 archives hold a bare document.
        return EidoMessagePayloadCanonical.parseCanonical(payload.documentCanonicalJson).document
    }

    fun messageEnvelopeFor(messageId:String):String?{
        val e=entries[messageId]?:return null
        val key=epochKeys[e.body.epochId]?:error("Vault epoch key missing")
        return HistoryVaultCrypto.openEntry(descriptor,key,e).messageEnvelopeCanonicalJson
    }

    fun close(){
        epochKeys.values.forEach{it.fill(0)}
    }
}

class HistoryVaultRecoveryCoordinator(
    private val targetRepository:MessengerRepository,
    private val targetIdentity:DevicePrivateCrypto,
    private val random:SecureRandom=SecureRandom(),
) {
    fun restore(
        packageCanonical:String,
        recoverySecret:HistoryVaultRecoverySecret,
        storedAtMs:Long,
    ):HistoryVaultRecoveredState{
        val pkg=HistoryVaultArchivePackageCodec.parseCanonical(packageCanonical)
        require(pkg.ownerUserId==targetIdentity.user.userId){"Vault archive belongs to another user"}
        HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)

        // The provider opens every epoch using only the independent recovery domain.
        val provider=HistoryVaultDocumentProvider(packageCanonical,recoverySecret)

        return try {
            // Full cryptographic/canonical preflight into a mirror of the CURRENT target state.
            // This catches identity/sequence/roster collisions before any actual mutation.
            val mirror=InMemoryMessengerRepository()
            targetRepository.contactDevices().forEach(mirror::putContact)
            targetRepository.ownDevices().forEach(mirror::putOwnDevice)
            targetRepository.deviceRosters().forEach(mirror::putDeviceRoster)
            targetRepository.keyGrants().forEach(mirror::putKeyGrant)
            targetRepository.conversations().forEach { c ->
                mirror.putConversation(c)
                targetRepository.messages(c.conversationId).forEach { mirror.insertMessage(it) }
            }
            val report=apply(pkg,provider,mirror,storedAtMs)

            // No actual mutation before the mirror passes.
            val actualReport=apply(pkg,provider,targetRepository,storedAtMs)
            require(actualReport==report)
            HistoryVaultRecoveredState(actualReport,provider)
        } catch (t:Throwable) {
            provider.close()
            throw t
        }
    }

    private fun apply(
        pkg:HistoryVaultArchivePackageV1,
        provider:HistoryVaultDocumentProvider,
        repo:MessengerRepository,
        storedAtMs:Long,
    ):HistoryVaultRestoreReport{
        val identities=linkedMapOf<Pair<String,String>,String>()

        fun addIdentity(raw:String){
            val b=IdentityParser.parsePublicBundleCanonical(raw)
            val key=b.user.userId to b.device.deviceId
            val old=identities[key]
            if(old==null) identities[key]=raw
            else{
                val a=IdentityParser.parsePublicBundleCanonical(old)
                require(a.user==b.user && a.device.body==b.device.body && a.device.deviceId==b.device.deviceId)
                identities[key]=minOf(old,raw)
            }
        }

        addIdentity(pkg.exporterIdentityBundleCanonicalJson)
        pkg.ownDeviceBundleCanonicalJson.forEach(::addIdentity)
        pkg.contacts.flatMap{it.deviceBundleCanonicalJson}.forEach(::addIdentity)

        val users=identities.values.map(IdentityParser::parsePublicBundleCanonical)
            .groupBy{it.user.userId}
            .mapValues{it.value.first().user}

        // Roster snapshots are current authorization metadata, not historical proof gates.
        pkg.deviceRosterCanonicalJson.map(DeviceRosterCanonical::parseCanonical).forEach{r->
            val user=users[r.body.userId] ?: if(r.body.userId==targetIdentity.user.userId) targetIdentity.user
                else error("Roster user identity missing")
            require(DeviceRosterVerifier.verifySignature(r,user))
            val known=identities.keys.filter{it.first==r.body.userId}.map{it.second}.toSet()
            require((r.body.activeDeviceIds+r.body.revokedDeviceIds).all{it in known})
        }

        val conversations=pkg.conversations.associateBy{it.conversationId}
        val entries=pkg.entryCanonicalJson.map(HistoryVaultEntryCodec::parseCanonical)
        val messages=linkedMapOf<String,EidogramMessageV1>()

        // Decrypt all history records before mutation. The package signature authenticates the
        // envelope<->document backup pair; the original envelope signature is independently verified.
        entries.forEach{entry->
            val raw=provider.messageEnvelopeFor(entry.body.messageId)?:error("Vault entry missing")
            val m=MessageParser.parseCanonical(raw)
            require(m.messageId==entry.body.messageId)
            require(m.body.aad.conversationId==entry.body.conversationId)
            require(m.messageId !in messages)
            val convMeta=conversations[m.body.aad.conversationId] ?: error("Conversation metadata missing")
            val conv=ConversationParser.parseCanonical(convMeta.descriptorCanonicalJson)
            val senderRaw=identities[m.body.aad.senderUserId to m.body.aad.senderDeviceId]
                ?: error("Sender identity missing from vault package")
            val sender=IdentityParser.parsePublicBundleCanonical(senderRaw)
            require(MessageCrypto.verify(m,sender.user,sender.device,conv)){"Historical envelope signature invalid"}
            // Forces authenticated vault document parse/hash check as well.
            require(provider.documentFor(m.messageId)!=null)
            messages[m.messageId]=m
        }

        val ordered=topological(messages.values.toList())

        // Presentation/public identity state.
        val contactAlias=pkg.contacts.associate{it.userId to it.alias}
        identities.toSortedMap(compareBy<Pair<String,String>>{it.first}.thenBy{it.second}).forEach{(_,raw)->
            val b=IdentityParser.parsePublicBundleCanonical(raw)
            if(b.user.userId==targetIdentity.user.userId){
                repo.putOwnDevice(OwnDeviceRecord(b.user.userId,b.device.deviceId,raw,storedAtMs))
            }else{
                repo.putContact(ContactDeviceRecord(
                    b.user.userId,b.device.deviceId,contactAlias[b.user.userId]?:b.user.userId.take(12),raw,storedAtMs
                ))
            }
        }

        pkg.conversations.sortedBy{it.conversationId}.forEach{c->
            val d=ConversationParser.parseCanonical(c.descriptorCanonicalJson)
            require(targetIdentity.user.userId in d.participantUserIds)
            repo.putConversation(ConversationRecord(c.conversationId,c.descriptorCanonicalJson,c.title,c.createdAtMs))
        }

        pkg.deviceRosterCanonicalJson.map(DeviceRosterCanonical::parseCanonical).sortedBy{it.body.userId}.forEach{r->
            repo.putDeviceRoster(DeviceRosterRecord(
                r.body.userId,r.body.epoch,r.rosterId,DeviceRosterCanonical.json(r),storedAtMs
            ))
        }

        ordered.forEach{m->
            repo.insertMessage(StoredMessageRecord(
                m.messageId,m.body.aad.conversationId,m.body.aad.senderUserId,m.body.aad.senderDeviceId,
                m.body.aad.senderSeq,m.body.aad.createdAtMs,m.body.aad.parentMessageIds,
                MessageCanonical.envelopeJson(m),
                if(m.body.aad.senderUserId==targetIdentity.user.userId) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                MessageLocalState.ARCHIVED,storedAtMs
            ))
        }

        return HistoryVaultRestoreReport(
            contactDeviceCount=identities.keys.count{it.first!=targetIdentity.user.userId},
            ownDeviceCount=identities.keys.count{it.first==targetIdentity.user.userId},
            conversationCount=pkg.conversations.size,
            messageCount=messages.size,
            epochCount=pkg.epochCanonicalJson.size,
        )
    }

    private fun topological(messages:List<EidogramMessageV1>):List<EidogramMessageV1>{
        val byId=messages.associateBy{it.messageId}
        require(byId.size==messages.size)
        // A full history-vault package is not a partial transport segment: every local parent
        // must be present, otherwise destructive recovery would silently truncate causal history.
        messages.forEach{m->
            m.body.aad.parentMessageIds.forEach{p->require(p in byId){"Vault package missing parent $p"}}
        }
        val children=byId.keys.associateWith{mutableListOf<String>()}
        val indegree=byId.keys.associateWith{0}.toMutableMap()
        messages.forEach{m->
            m.body.aad.parentMessageIds.forEach{p->
                children.getValue(p).add(m.messageId)
                indegree[m.messageId]=indegree.getValue(m.messageId)+1
            }
        }
        val cmp=compareBy<String>(
            {byId.getValue(it).body.aad.createdAtMs},
            {byId.getValue(it).body.aad.senderUserId},
            {byId.getValue(it).body.aad.senderSeq},
            {it}
        )
        val q=PriorityQueue(cmp)
        indegree.filterValues{it==0}.keys.forEach(q::add)
        val out=mutableListOf<EidogramMessageV1>()
        while(q.isNotEmpty()){
            val id=q.remove();out+=byId.getValue(id)
            children.getValue(id).sorted().forEach{c->
                val n=indegree.getValue(c)-1;indegree[c]=n;if(n==0)q.add(c)
            }
        }
        require(out.size==messages.size){"Vault history DAG contains a cycle"}
        return out
    }
}
