package org.eidolang.core.vault

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import org.eidolang.core.message.MessageParser
import org.eidolang.core.repository.*
import org.eidolang.core.recovery.ContactPresentationV1
import org.eidolang.core.recovery.ConversationPresentationV1
import org.eidolang.core.multidevice.DeviceRosterCanonical
import java.security.SecureRandom

object HistoryVaultArchivePackageCodec {
    private fun contactJson(c:ContactPresentationV1):String=CanonicalJson.obj(mapOf(
        "alias" to CanonicalJson.string(c.alias),
        "device_bundle_json" to CanonicalJson.arr(c.deviceBundleCanonicalJson.map(CanonicalJson::string)),
        "user_id" to CanonicalJson.string(c.userId),
    ))

    private fun conversationJson(c:ConversationPresentationV1):String=CanonicalJson.obj(mapOf(
        "conversation_id" to CanonicalJson.string(c.conversationId),
        "created_at_ms" to CanonicalJson.long(c.createdAtMs),
        "descriptor_json" to CanonicalJson.string(c.descriptorCanonicalJson),
        "title" to CanonicalJson.string(c.title),
    ))

    fun bodyJson(p:HistoryVaultArchivePackageV1):String=CanonicalJson.obj(mapOf(
        "contacts" to CanonicalJson.arr(p.contacts.map(::contactJson)),
        "conversations" to CanonicalJson.arr(p.conversations.map(::conversationJson)),
        "device_roster_json" to CanonicalJson.arr(p.deviceRosterCanonicalJson.map(CanonicalJson::string)),
        "entry_json" to CanonicalJson.arr(p.entryCanonicalJson.map(CanonicalJson::string)),
        "epoch_json" to CanonicalJson.arr(p.epochCanonicalJson.map(CanonicalJson::string)),
        "exporter_identity_bundle_json" to CanonicalJson.string(p.exporterIdentityBundleCanonicalJson),
        "own_device_bundle_json" to CanonicalJson.arr(p.ownDeviceBundleCanonicalJson.map(CanonicalJson::string)),
        "owner_user_id" to CanonicalJson.string(p.ownerUserId),
        "package_type" to CanonicalJson.string("EidoHistoryVaultArchivePackageV1"),
        "package_version" to CanonicalJson.string("1.0.0"),
        "vault_descriptor_json" to CanonicalJson.string(p.vaultDescriptorCanonicalJson),
    ))

    fun json(p:HistoryVaultArchivePackageV1):String=CanonicalJson.obj(mapOf(
        "body" to bodyJson(p),
        "exporter_signature_b64" to CanonicalJson.string(p.exporterSignatureB64),
        "package_id" to CanonicalJson.string(p.packageId),
    ))

    fun build(
        ownerUserId:String,
        vaultDescriptorCanonicalJson:String,
        exporterIdentityBundleCanonicalJson:String,
        ownDeviceBundleCanonicalJson:Collection<String>,
        deviceRosterCanonicalJson:Collection<String>,
        contacts:Collection<ContactPresentationV1>,
        conversations:Collection<ConversationPresentationV1>,
        epochCanonicalJson:Collection<String>,
        entryCanonicalJson:Collection<String>,
        signer:DevicePrivateCrypto,
    ):HistoryVaultArchivePackageV1{
        require(signer.user.userId==ownerUserId)
        val descriptor=HistoryVaultDescriptorCodec.parseCanonical(vaultDescriptorCanonicalJson)
        require(descriptor.ownerUserId==ownerUserId)
        val exporter=IdentityParser.parsePublicBundleCanonical(exporterIdentityBundleCanonicalJson)
        require(exporter.user==signer.user && exporter.device.deviceId==signer.certificate.deviceId)

        val own=ownDeviceBundleCanonicalJson
            .map(IdentityParser::parsePublicBundleCanonical)
            .onEach{require(it.user.userId==ownerUserId)}
            .sortedBy{it.device.deviceId}
            .map(IdentityCanonical::publicBundleJson)

        val rosters=deviceRosterCanonicalJson
            .map(org.eidolang.core.multidevice.DeviceRosterCanonical::parseCanonical)
            .sortedBy{it.body.userId}
            .map(DeviceRosterCanonical::json)

        val epochs=epochCanonicalJson
            .map(HistoryVaultEpochCodec::parseCanonical)
            .sortedBy{it.body.epoch}
        HistoryVaultCrypto.verifyEpochChain(descriptor,epochs)

        val epochIds=epochs.associateBy{it.epochId}
        val entries=entryCanonicalJson
            .map(HistoryVaultEntryCodec::parseCanonical)
            .sortedBy{it.body.messageId}
        require(entries.map{it.body.messageId}.distinct().size==entries.size)
        entries.forEach{
            require(it.body.vaultId==descriptor.vaultId)
            require(epochIds[it.body.epochId]?.body?.epoch==it.body.epoch)
        }

        val cs=contacts.map{c->
            val bs=c.deviceBundleCanonicalJson.map(IdentityParser::parsePublicBundleCanonical)
            require(bs.all{it.user.userId==c.userId})
            c.copy(deviceBundleCanonicalJson=bs.sortedBy{it.device.deviceId}.map(IdentityCanonical::publicBundleJson))
        }.sortedBy{it.userId}
        require(cs.map{it.userId}.distinct().size==cs.size)

        val convs=conversations.sortedBy{it.conversationId}
        require(convs.map{it.conversationId}.distinct().size==convs.size)

        val provisional=HistoryVaultArchivePackageV1(
            ownerUserId,
            vaultDescriptorCanonicalJson,
            exporterIdentityBundleCanonicalJson,
            own,
            rosters,
            cs,
            convs,
            epochs.map(HistoryVaultEpochCodec::json),
            entries.map(HistoryVaultEntryCodec::json),
            "",
            "",
        )
        val body=bodyJson(provisional)
        return provisional.copy(
            packageId=HexSha256.ofUtf8(body),
            exporterSignatureB64=B64Url.encode(signer.signMessage(body.toByteArray(Charsets.UTF_8)))
        )
    }

    fun parseCanonical(text:String):HistoryVaultArchivePackageV1{
        val root=StrictJsonParser(text).parse().obj()
        root.requireKeys("body","exporter_signature_b64","package_id")
        val b=root.obj("body")
        b.requireKeys(
            "contacts","conversations","device_roster_json","entry_json","epoch_json",
            "exporter_identity_bundle_json","own_device_bundle_json","owner_user_id",
            "package_type","package_version","vault_descriptor_json"
        )
        require(b.str("package_type")=="EidoHistoryVaultArchivePackageV1")
        require(b.str("package_version")=="1.0.0")

        val contacts=b.arr("contacts").items.map{raw->
            val c=raw.obj();c.requireKeys("alias","device_bundle_json","user_id")
            ContactPresentationV1(c.str("user_id"),c.str("alias"),c.arr("device_bundle_json").items.map{it.str()})
        }
        val conversations=b.arr("conversations").items.map{raw->
            val c=raw.obj();c.requireKeys("conversation_id","created_at_ms","descriptor_json","title")
            ConversationPresentationV1(c.str("conversation_id"),c.str("title"),c.long("created_at_ms"),c.str("descriptor_json"))
        }
        val out=HistoryVaultArchivePackageV1(
            ownerUserId=b.str("owner_user_id"),
            vaultDescriptorCanonicalJson=b.str("vault_descriptor_json"),
            exporterIdentityBundleCanonicalJson=b.str("exporter_identity_bundle_json"),
            ownDeviceBundleCanonicalJson=b.arr("own_device_bundle_json").items.map{it.str()},
            deviceRosterCanonicalJson=b.arr("device_roster_json").items.map{it.str()},
            contacts=contacts,
            conversations=conversations,
            epochCanonicalJson=b.arr("epoch_json").items.map{it.str()},
            entryCanonicalJson=b.arr("entry_json").items.map{it.str()},
            packageId=root.str("package_id"),
            exporterSignatureB64=root.str("exporter_signature_b64"),
        )

        val descriptor=HistoryVaultDescriptorCodec.parseCanonical(out.vaultDescriptorCanonicalJson)
        require(descriptor.ownerUserId==out.ownerUserId)
        val exporter=IdentityParser.parsePublicBundleCanonical(out.exporterIdentityBundleCanonicalJson)
        require(exporter.user.userId==out.ownerUserId)

        val own=out.ownDeviceBundleCanonicalJson.map(IdentityParser::parsePublicBundleCanonical)
        require(own.all{it.user.userId==out.ownerUserId})
        require(own.map{it.device.deviceId}==own.map{it.device.deviceId}.sorted().distinct())

        val rosters=out.deviceRosterCanonicalJson.map(DeviceRosterCanonical::parseCanonical)
        require(rosters.map{it.body.userId}==rosters.map{it.body.userId}.sorted().distinct())

        require(out.contacts==out.contacts.sortedBy{it.userId})
        require(out.contacts.map{it.userId}.distinct().size==out.contacts.size)
        out.contacts.forEach{c->
            val bs=c.deviceBundleCanonicalJson.map(IdentityParser::parsePublicBundleCanonical)
            require(bs.all{it.user.userId==c.userId})
            require(bs.map{it.device.deviceId}==bs.map{it.device.deviceId}.sorted().distinct())
            require(c.deviceBundleCanonicalJson==bs.map(IdentityCanonical::publicBundleJson))
        }
        require(out.conversations==out.conversations.sortedBy{it.conversationId})
        require(out.conversations.map{it.conversationId}.distinct().size==out.conversations.size)
        out.conversations.forEach{
            require(org.eidolang.core.message.ConversationParser.parseCanonical(it.descriptorCanonicalJson).conversationId==it.conversationId)
        }

        val epochs=out.epochCanonicalJson.map(HistoryVaultEpochCodec::parseCanonical)
        HistoryVaultCrypto.verifyEpochChain(descriptor,epochs)
        require(out.epochCanonicalJson==epochs.sortedBy{it.body.epoch}.map(HistoryVaultEpochCodec::json))
        val epochIds=epochs.associateBy{it.epochId}

        val entries=out.entryCanonicalJson.map(HistoryVaultEntryCodec::parseCanonical)
        require(out.entryCanonicalJson==entries.sortedBy{it.body.messageId}.map(HistoryVaultEntryCodec::json))
        require(entries.map{it.body.messageId}.distinct().size==entries.size)
        entries.forEach{
            require(it.body.vaultId==descriptor.vaultId)
            require(epochIds[it.body.epochId]?.body?.epoch==it.body.epoch)
        }

        val body=bodyJson(out)
        require(out.packageId==HexSha256.ofUtf8(body)){"History-vault package id mismatch"}
        require(JcaCrypto.verify(
            PublicKeyCodec.ec(exporter.device.body.signingPublicKeyB64),
            body.toByteArray(Charsets.UTF_8),
            B64Url.decode(out.exporterSignatureB64)
        )){"History-vault package exporter signature invalid"}
        require(json(out)==text){"History-vault package is not canonical"}
        return out
    }
}

class HistoryVaultController private constructor(
    val descriptor:HistoryVaultDescriptorV1,
    private val recoverySecret:HistoryVaultRecoverySecret,
    val localIdentity:DevicePrivateCrypto,
    private val random:SecureRandom,
) {
    private val epochs=mutableListOf<OpenedVaultEpoch>()
    private val entries=linkedMapOf<String,HistoryVaultEntryV1>()

    fun createEpoch(activeDeviceIds:Collection<String>):HistoryVaultEpochV1{
        val opened=HistoryVaultCrypto.createEpoch(
            descriptor,recoverySecret,epochs.lastOrNull()?.epoch,activeDeviceIds,random
        )
        epochs += opened
        return opened.epoch
    }

    fun createEpochFromRoster(rosterCanonicalJson:String):HistoryVaultEpochV1{
        val roster=DeviceRosterCanonical.parseCanonical(rosterCanonicalJson)
        require(roster.body.userId==descriptor.ownerUserId)
        require(org.eidolang.core.multidevice.DeviceRosterVerifier.verifySignature(roster,localIdentity.user)) {
            "Invalid owner root-signed roster"
        }
        return createEpoch(roster.body.activeDeviceIds)
    }

    fun currentEpoch():HistoryVaultEpochV1 = epochs.lastOrNull()?.epoch ?: error("No vault epoch")

    fun archiveAll(repository:MessengerRepository,messenger:LocalMessengerService):Int{
        require(epochs.isNotEmpty()){"Create a vault epoch before archiving"}
        var added=0
        repository.conversations().sortedBy{it.conversationId}.forEach{c->
            val docs=messenger.timeline(c.conversationId).associateBy{it.messageId}
            repository.messages(c.conversationId).sortedBy{it.messageId}.forEach{row->
                if(row.messageId !in entries){
                    val timeline=docs[row.messageId]?:error("Verified timeline document missing")
                    val entry=HistoryVaultCrypto.sealEntry(
                        descriptor,epochs.last(),row.envelopeCanonicalJson,timeline.document,random
                    )
                    entries[row.messageId]=entry
                    added++
                }
            }
        }
        return added
    }

    fun entry(messageId:String):HistoryVaultEntryV1?=entries[messageId]

    fun exportPackage(repository:MessengerRepository):String{
        val contacts=repository.contactDevices().groupBy{it.userId}.values.map{rows->
            val chosen=rows.minWith(compareBy<ContactDeviceRecord>{it.importedAtMs}.thenBy{it.deviceId})
            ContactPresentationV1(
                chosen.userId,chosen.alias,
                rows.sortedBy{it.deviceId}.map{it.bundleCanonicalJson}
            )
        }
        val conversations=repository.conversations().map{
            ConversationPresentationV1(it.conversationId,it.title,it.createdAtMs,it.descriptorCanonicalJson)
        }
        val own=(repository.ownDevices().map{it.bundleCanonicalJson}+
            IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(localIdentity.user,localIdentity.certificate)))
            .distinct()
        val exporter=IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(localIdentity.user,localIdentity.certificate))
        val pkg=HistoryVaultArchivePackageCodec.build(
            descriptor.ownerUserId,
            HistoryVaultDescriptorCodec.json(descriptor),
            exporter,
            own,
            repository.deviceRosters().map{it.rosterCanonicalJson},
            contacts,
            conversations,
            epochs.map{HistoryVaultEpochCodec.json(it.epoch)},
            entries.values.map(HistoryVaultEntryCodec::json),
            localIdentity
        )
        return HistoryVaultArchivePackageCodec.json(pkg)
    }

    fun recoverySecretExportCanonical():String =
        RecoverySecretExportCodec.json(RecoverySecretExportCodec.create(descriptor.vaultId,recoverySecret))

    fun openEpochForDeviceGrant(epochId:String):OpenedVaultEpoch =
        epochs.firstOrNull{it.epoch.epochId==epochId} ?: error("Unknown local vault epoch")

    companion object{
        fun create(
            owner:DevicePrivateCrypto,
            recoverySecret:HistoryVaultRecoverySecret=HistoryVaultRecoverySecret.generate(),
            seed:ByteArray=JcaCrypto.randomBytes(16),
            random:SecureRandom=SecureRandom(),
        ):HistoryVaultController{
            val d=HistoryVaultDescriptorCodec.fromSeed(owner.user.userId,seed)
            return HistoryVaultController(d,recoverySecret,owner,random)
        }

        fun fromPackage(
            canonical:String,
            recoverySecret:HistoryVaultRecoverySecret,
            owner:DevicePrivateCrypto,
            random:SecureRandom=SecureRandom(),
        ):HistoryVaultController{
            val pkg=HistoryVaultArchivePackageCodec.parseCanonical(canonical)
            require(pkg.ownerUserId==owner.user.userId)
            val descriptor=HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
            val controller=HistoryVaultController(descriptor,recoverySecret,owner,random)
            pkg.epochCanonicalJson.map(HistoryVaultEpochCodec::parseCanonical).forEach{e->
                controller.epochs += OpenedVaultEpoch(
                    e,HistoryVaultCrypto.openEpoch(descriptor,recoverySecret,e)
                )
            }
            pkg.entryCanonicalJson.map(HistoryVaultEntryCodec::parseCanonical).forEach{e->
                controller.entries[e.body.messageId]=e
            }
            return controller
        }
    }
}

private fun JValue.obj():JValue.Obj=this as? JValue.Obj?:error("Expected object")
private fun JValue.str():String=(this as? JValue.Str)?.value?:error("Expected string")
private fun JValue.Obj.str(k:String):String=fields[k]?.str()?:error("Missing $k")
private fun JValue.Obj.obj(k:String):JValue.Obj=fields[k]?.obj()?:error("Missing $k")
private fun JValue.Obj.arr(k:String):JValue.Arr=fields[k] as? JValue.Arr?:error("Missing $k")
private fun JValue.Obj.long(k:String):Long=(fields[k] as? JValue.IntNum)?.value?:error("Missing long $k")
private fun JValue.Obj.requireKeys(vararg keys:String){require(fields.keys==keys.toSet()){"Schema mismatch"}}
