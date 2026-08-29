package org.eidolang.core.hardening

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.HexSha256
import org.eidolang.core.crypto.IdentityParser
import org.eidolang.core.multidevice.DeviceRosterCanonical
import org.eidolang.core.vault.*

data class RosterAnchorV1(
    val userId:String,
    val epoch:Int,
    val rosterId:String,
)

data class VaultRollbackAnchorV1(
    val vaultId:String,
    val epochIds:List<String>,
    val messageEntryIds:Map<String,String>,
    val ownDeviceIds:List<String>,
    val contactDeviceIds:List<String>,
    val rosters:List<RosterAnchorV1>,
    val lastPackageId:String,
    val anchorId:String,
)

enum class VaultFreshnessStatus {
    /**
     * Cryptographically valid package, but a fresh offline device has no monotonic local
     * evidence proving that this is the latest valid package.
     */
    BOOTSTRAP_FRESHNESS_UNPROVEN,

    /**
     * Candidate provably extends all locally anchored immutable history/authorization facts.
     */
    MONOTONIC_EXTENSION,
}

data class VaultRollbackAdmission(
    val status:VaultFreshnessStatus,
    val nextAnchor:VaultRollbackAnchorV1,
)

object VaultRollbackAnchorCodec {
    private fun rosterJson(r:RosterAnchorV1)=CanonicalJson.obj(mapOf(
        "epoch" to CanonicalJson.int(r.epoch),
        "roster_id" to CanonicalJson.string(r.rosterId),
        "user_id" to CanonicalJson.string(r.userId),
    ))

    private fun entryJson(messageId:String,entryId:String)=CanonicalJson.obj(mapOf(
        "entry_id" to CanonicalJson.string(entryId),
        "message_id" to CanonicalJson.string(messageId),
    ))

    fun bodyJson(a:VaultRollbackAnchorV1):String=CanonicalJson.obj(mapOf(
        "anchor_type" to CanonicalJson.string("EidoVaultRollbackAnchorV1"),
        "anchor_version" to CanonicalJson.string("1.0.0"),
        "contact_device_ids" to CanonicalJson.arr(a.contactDeviceIds.map(CanonicalJson::string)),
        "epoch_ids" to CanonicalJson.arr(a.epochIds.map(CanonicalJson::string)),
        "last_package_id" to CanonicalJson.string(a.lastPackageId),
        "message_entries" to CanonicalJson.arr(
            a.messageEntryIds.toSortedMap().map{(m,e)->entryJson(m,e)}
        ),
        "own_device_ids" to CanonicalJson.arr(a.ownDeviceIds.map(CanonicalJson::string)),
        "rosters" to CanonicalJson.arr(a.rosters.map(::rosterJson)),
        "vault_id" to CanonicalJson.string(a.vaultId),
    ))

    fun json(a:VaultRollbackAnchorV1):String=CanonicalJson.obj(mapOf(
        "anchor_id" to CanonicalJson.string(a.anchorId),
        "body" to bodyJson(a),
    ))

    fun parseCanonical(text:String):VaultRollbackAnchorV1{
        val root=StrictJsonParser(text).parse().obj()
        root.requireKeys("anchor_id","body")
        val b=root.obj("body")
        b.requireKeys(
            "anchor_type","anchor_version","contact_device_ids","epoch_ids","last_package_id",
            "message_entries","own_device_ids","rosters","vault_id"
        )
        require(b.str("anchor_type")=="EidoVaultRollbackAnchorV1")
        require(b.str("anchor_version")=="1.0.0")
        val entries=b.arr("message_entries").items.associate{v->
            val e=v.obj();e.requireKeys("entry_id","message_id")
            e.str("message_id") to e.str("entry_id")
        }
        val rosters=b.arr("rosters").items.map{v->
            val r=v.obj();r.requireKeys("epoch","roster_id","user_id")
            RosterAnchorV1(r.str("user_id"),r.int("epoch"),r.str("roster_id"))
        }
        val a=VaultRollbackAnchorV1(
            b.str("vault_id"),
            b.arr("epoch_ids").items.map{it.str()},
            entries,
            b.arr("own_device_ids").items.map{it.str()},
            b.arr("contact_device_ids").items.map{it.str()},
            rosters,
            b.str("last_package_id"),
            root.str("anchor_id")
        )
        require(a.epochIds==a.epochIds.distinct())
        require(a.ownDeviceIds==a.ownDeviceIds.sorted().distinct())
        require(a.contactDeviceIds==a.contactDeviceIds.sorted().distinct())
        require(a.rosters==a.rosters.sortedBy{it.userId})
        require(a.rosters.map{it.userId}.distinct().size==a.rosters.size)
        require(a.anchorId==HexSha256.ofUtf8(bodyJson(a)))
        require(json(a)==text)
        return a
    }
}

object VaultRollbackGuard {
    fun inspect(
        packageCanonical:String,
        previous:VaultRollbackAnchorV1?,
    ):VaultRollbackAdmission{
        val pkg=HistoryVaultArchivePackageCodec.parseCanonical(packageCanonical)
        val descriptor=HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
        val candidate=anchorFrom(pkg,descriptor.vaultId)

        if(previous==null){
            return VaultRollbackAdmission(
                VaultFreshnessStatus.BOOTSTRAP_FRESHNESS_UNPROVEN,
                candidate
            )
        }

        require(previous.vaultId==candidate.vaultId){"Cross-vault rollback anchor substitution"}

        // Epoch chain must retain the entire previously accepted prefix.
        require(candidate.epochIds.size>=previous.epochIds.size){"Vault epoch rollback"}
        require(candidate.epochIds.take(previous.epochIds.size)==previous.epochIds){"Vault epoch fork/rewrite"}

        // Already accepted immutable message->entry bindings cannot disappear or change.
        previous.messageEntryIds.forEach{(messageId,entryId)->
            require(candidate.messageEntryIds[messageId]==entryId){"Vault history rollback/re-encryption under same VaultId"}
        }

        // Public identities already anchored locally cannot silently disappear.
        require(candidate.ownDeviceIds.containsAll(previous.ownDeviceIds)){"Own-device identity rollback"}
        require(candidate.contactDeviceIds.containsAll(previous.contactDeviceIds)){"Contact-device identity rollback"}

        val nextRosters=candidate.rosters.associateBy{it.userId}
        previous.rosters.forEach{old->
            val n=nextRosters[old.userId] ?: error("Device-roster rollback: ${old.userId} disappeared")
            require(n.epoch>=old.epoch){"Device-roster epoch rollback"}
            if(n.epoch==old.epoch){
                require(n.rosterId==old.rosterId){"Device-roster fork at anchored epoch"}
            }
        }

        return VaultRollbackAdmission(VaultFreshnessStatus.MONOTONIC_EXTENSION,candidate)
    }

    private fun anchorFrom(
        pkg:HistoryVaultArchivePackageV1,
        vaultId:String,
    ):VaultRollbackAnchorV1{
        val epochs=pkg.epochCanonicalJson.map(HistoryVaultEpochCodec::parseCanonical)
        val entries=pkg.entryCanonicalJson.map(HistoryVaultEntryCodec::parseCanonical)
        val own=pkg.ownDeviceBundleCanonicalJson.map(IdentityParser::parsePublicBundleCanonical)
            .map{it.device.deviceId}.sorted().distinct()
        val contacts=pkg.contacts.flatMap{it.deviceBundleCanonicalJson}
            .map(IdentityParser::parsePublicBundleCanonical).map{it.device.deviceId}.sorted().distinct()
        val rosters=pkg.deviceRosterCanonicalJson.map(DeviceRosterCanonical::parseCanonical)
            .map{RosterAnchorV1(it.body.userId,it.body.epoch,it.rosterId)}
            .sortedBy{it.userId}
        val provisional=VaultRollbackAnchorV1(
            vaultId,
            epochs.sortedBy{it.body.epoch}.map{it.epochId},
            entries.associate{it.body.messageId to it.entryId},
            own,contacts,rosters,pkg.packageId,""
        )
        return provisional.copy(anchorId=HexSha256.ofUtf8(VaultRollbackAnchorCodec.bodyJson(provisional)))
    }
}

private fun JValue.obj():JValue.Obj=this as? JValue.Obj?:error("Expected object")
private fun JValue.str():String=(this as? JValue.Str)?.value?:error("Expected string")
private fun JValue.Obj.str(k:String):String=fields[k]?.str()?:error("Missing $k")
private fun JValue.Obj.obj(k:String):JValue.Obj=fields[k]?.obj()?:error("Missing $k")
private fun JValue.Obj.arr(k:String):JValue.Arr=fields[k] as? JValue.Arr?:error("Missing $k")
private fun JValue.Obj.int(k:String):Int{
    val v=(fields[k] as? JValue.IntNum)?.value?:error("Missing int $k")
    require(v in 0..Int.MAX_VALUE.toLong());return v.toInt()
}
private fun JValue.Obj.requireKeys(vararg keys:String){require(fields.keys==keys.toSet()){"Schema mismatch"}}
