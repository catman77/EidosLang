package org.eidolang.core.vault

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import org.eidolang.core.message.MessageParser
import org.eidolang.core.message.MessageCanonical
import org.eidolang.core.canonical.EidogramParser
import org.eidolang.core.canonical.EidogramCanonical

object HistoryVaultDescriptorCodec {
    fun bodyJson(ownerUserId:String, seedB64:String):String = CanonicalJson.obj(mapOf(
        "owner_user_id" to CanonicalJson.string(ownerUserId),
        "seed_b64" to CanonicalJson.string(seedB64),
        "vault_type" to CanonicalJson.string("EidoHistoryVaultV1"),
        "vault_version" to CanonicalJson.string("1.0.0"),
    ))

    fun json(d:HistoryVaultDescriptorV1):String = CanonicalJson.obj(mapOf(
        "body" to bodyJson(d.ownerUserId,d.seedB64),
        "vault_id" to CanonicalJson.string(d.vaultId),
    ))

    fun fromSeed(ownerUserId:String,seed:ByteArray):HistoryVaultDescriptorV1{
        require(ownerUserId.matches(Regex("[0-9a-f]{64}")))
        require(seed.size==16)
        val seedB64=B64Url.encode(seed)
        val id=HexSha256.ofUtf8(bodyJson(ownerUserId,seedB64))
        return HistoryVaultDescriptorV1(ownerUserId,seedB64,id)
    }

    fun parseCanonical(text:String):HistoryVaultDescriptorV1{
        val root=StrictJsonParser(text).parse().obj()
        root.requireKeys("body","vault_id")
        val b=root.obj("body")
        b.requireKeys("owner_user_id","seed_b64","vault_type","vault_version")
        require(b.str("vault_type")=="EidoHistoryVaultV1")
        require(b.str("vault_version")=="1.0.0")
        val d=fromSeed(b.str("owner_user_id"),B64Url.decode(b.str("seed_b64")))
        require(d.vaultId==root.str("vault_id"))
        require(json(d)==text)
        return d
    }
}

object RecoverySecretExportCodec {
    fun bodyJson(vaultId:String,recoveryKeyId:String,secretB64:String):String=CanonicalJson.obj(mapOf(
        "recovery_key_id" to CanonicalJson.string(recoveryKeyId),
        "secret_b64" to CanonicalJson.string(secretB64),
        "secret_type" to CanonicalJson.string("EidoHistoryVaultRecoverySecretV1"),
        "secret_version" to CanonicalJson.string("1.0.0"),
        "vault_id" to CanonicalJson.string(vaultId),
    ))

    fun json(e:RecoverySecretExportV1):String=CanonicalJson.obj(mapOf(
        "body" to bodyJson(e.vaultId,e.recoveryKeyId,e.secretB64),
        "export_id" to CanonicalJson.string(e.exportId),
    ))

    fun create(vaultId:String,secret:HistoryVaultRecoverySecret):RecoverySecretExportV1{
        val raw=secret.copyBytes()
        try{
            val b64=B64Url.encode(raw)
            val body=bodyJson(vaultId,secret.recoveryKeyId,b64)
            return RecoverySecretExportV1(vaultId,secret.recoveryKeyId,b64,HexSha256.ofUtf8(body))
        }finally{raw.fill(0)}
    }

    fun parseCanonical(text:String):Pair<RecoverySecretExportV1,HistoryVaultRecoverySecret>{
        val root=StrictJsonParser(text).parse().obj()
        root.requireKeys("body","export_id")
        val b=root.obj("body")
        b.requireKeys("recovery_key_id","secret_b64","secret_type","secret_version","vault_id")
        require(b.str("secret_type")=="EidoHistoryVaultRecoverySecretV1")
        require(b.str("secret_version")=="1.0.0")
        val secret=HistoryVaultRecoverySecret.fromBytes(B64Url.decode(b.str("secret_b64")))
        require(secret.recoveryKeyId==b.str("recovery_key_id"))
        val out=create(b.str("vault_id"),secret)
        require(out.exportId==root.str("export_id"))
        require(json(out)==text)
        return out to secret
    }
}

object HistoryVaultEpochCodec {
    fun aadJson(
        vaultId:String,epoch:Int,previousEpochId:String?,
        activeDeviceIds:List<String>,recoveryKeyId:String
    ):String=CanonicalJson.obj(mapOf(
        "active_device_ids" to CanonicalJson.arr(activeDeviceIds.map(CanonicalJson::string)),
        "epoch" to CanonicalJson.int(epoch),
        "previous_epoch_id" to CanonicalJson.nullableString(previousEpochId),
        "recovery_key_id" to CanonicalJson.string(recoveryKeyId),
        "vault_id" to CanonicalJson.string(vaultId),
        "wrap_type" to CanonicalJson.string("EidoHistoryVaultEpochRecoveryWrapV1"),
    ))

    fun bodyJson(b:HistoryVaultEpochBodyV1):String=CanonicalJson.obj(mapOf(
        "active_device_ids" to CanonicalJson.arr(b.activeDeviceIds.map(CanonicalJson::string)),
        "epoch" to CanonicalJson.int(b.epoch),
        "epoch_type" to CanonicalJson.string("EidoHistoryVaultEpochV1"),
        "epoch_version" to CanonicalJson.string("1.0.0"),
        "previous_epoch_id" to CanonicalJson.nullableString(b.previousEpochId),
        "recovery_ciphertext_b64" to CanonicalJson.string(b.recoveryCiphertextB64),
        "recovery_key_id" to CanonicalJson.string(b.recoveryKeyId),
        "recovery_nonce_b64" to CanonicalJson.string(b.recoveryNonceB64),
        "vault_id" to CanonicalJson.string(b.vaultId),
    ))

    fun json(e:HistoryVaultEpochV1):String=CanonicalJson.obj(mapOf(
        "body" to bodyJson(e.body),
        "epoch_id" to CanonicalJson.string(e.epochId),
    ))

    fun parseCanonical(text:String):HistoryVaultEpochV1{
        val root=StrictJsonParser(text).parse().obj()
        root.requireKeys("body","epoch_id")
        val b=root.obj("body")
        b.requireKeys(
            "active_device_ids","epoch","epoch_type","epoch_version","previous_epoch_id",
            "recovery_ciphertext_b64","recovery_key_id","recovery_nonce_b64","vault_id"
        )
        require(b.str("epoch_type")=="EidoHistoryVaultEpochV1")
        require(b.str("epoch_version")=="1.0.0")
        val active=b.arr("active_device_ids").items.map{it.str()}
        require(active==active.sorted().distinct())
        val epoch=b.int("epoch"); require(epoch>=0)
        val out=HistoryVaultEpochV1(
            HistoryVaultEpochBodyV1(
                b.str("vault_id"),epoch,b.nullableStr("previous_epoch_id"),active,
                b.str("recovery_key_id"),b.str("recovery_nonce_b64"),b.str("recovery_ciphertext_b64")
            ),
            root.str("epoch_id")
        )
        require(out.epochId==HexSha256.ofUtf8(bodyJson(out.body)))
        require(json(out)==text)
        return out
    }
}

object HistoryVaultEntryCodec {
    fun aadJson(vaultId:String,epoch:Int,epochId:String,messageId:String,conversationId:String):String=
        CanonicalJson.obj(mapOf(
            "conversation_id" to CanonicalJson.string(conversationId),
            "entry_type" to CanonicalJson.string("EidoHistoryVaultEntryV1"),
            "entry_version" to CanonicalJson.string("1.0.0"),
            "epoch" to CanonicalJson.int(epoch),
            "epoch_id" to CanonicalJson.string(epochId),
            "message_id" to CanonicalJson.string(messageId),
            "vault_id" to CanonicalJson.string(vaultId),
        ))

    fun payloadJson(p:HistoryVaultEntryPayloadV1):String=CanonicalJson.obj(mapOf(
        "document_content_hash" to CanonicalJson.string(p.documentContentHash),
        "document_json" to CanonicalJson.string(p.documentCanonicalJson),
        "message_envelope_json" to CanonicalJson.string(p.messageEnvelopeCanonicalJson),
        "payload_type" to CanonicalJson.string("EidoHistoryVaultEntryPayloadV1"),
        "payload_version" to CanonicalJson.string("1.0.0"),
    ))

    fun parsePayloadCanonical(text:String):HistoryVaultEntryPayloadV1{
        val o=StrictJsonParser(text).parse().obj()
        o.requireKeys("document_content_hash","document_json","message_envelope_json","payload_type","payload_version")
        require(o.str("payload_type")=="EidoHistoryVaultEntryPayloadV1")
        require(o.str("payload_version")=="1.0.0")
        val messageRaw=o.str("message_envelope_json")
        val docRaw=o.str("document_json")
        MessageParser.parseCanonical(messageRaw)
        val doc=EidogramParser.parseCanonical(docRaw)
        val out=HistoryVaultEntryPayloadV1(messageRaw,docRaw,o.str("document_content_hash"))
        require(EidogramCanonical.contentHash(doc)==out.documentContentHash)
        require(payloadJson(out)==text)
        return out
    }

    fun bodyJson(b:HistoryVaultEntryBodyV1):String=CanonicalJson.obj(mapOf(
        "ciphertext_b64" to CanonicalJson.string(b.ciphertextB64),
        "conversation_id" to CanonicalJson.string(b.conversationId),
        "entry_type" to CanonicalJson.string("EidoHistoryVaultEntryV1"),
        "entry_version" to CanonicalJson.string("1.0.0"),
        "epoch" to CanonicalJson.int(b.epoch),
        "epoch_id" to CanonicalJson.string(b.epochId),
        "message_id" to CanonicalJson.string(b.messageId),
        "nonce_b64" to CanonicalJson.string(b.nonceB64),
        "vault_id" to CanonicalJson.string(b.vaultId),
    ))

    fun json(e:HistoryVaultEntryV1):String=CanonicalJson.obj(mapOf(
        "body" to bodyJson(e.body),
        "entry_id" to CanonicalJson.string(e.entryId),
    ))

    fun parseCanonical(text:String):HistoryVaultEntryV1{
        val root=StrictJsonParser(text).parse().obj()
        root.requireKeys("body","entry_id")
        val b=root.obj("body")
        b.requireKeys(
            "ciphertext_b64","conversation_id","entry_type","entry_version","epoch",
            "epoch_id","message_id","nonce_b64","vault_id"
        )
        require(b.str("entry_type")=="EidoHistoryVaultEntryV1")
        require(b.str("entry_version")=="1.0.0")
        val out=HistoryVaultEntryV1(
            HistoryVaultEntryBodyV1(
                b.str("vault_id"),b.int("epoch"),b.str("epoch_id"),b.str("message_id"),
                b.str("conversation_id"),b.str("nonce_b64"),b.str("ciphertext_b64")
            ),
            root.str("entry_id")
        )
        require(out.body.epoch>=0)
        require(out.body.messageId.matches(Regex("[0-9a-f]{64}")))
        require(out.body.conversationId.matches(Regex("[0-9a-f]{64}")))
        require(out.entryId==HexSha256.ofUtf8(bodyJson(out.body)))
        require(json(out)==text)
        return out
    }
}

private fun JValue.obj():JValue.Obj=this as? JValue.Obj?:error("Expected object")
private fun JValue.str():String=(this as? JValue.Str)?.value?:error("Expected string")
private fun JValue.Obj.str(k:String):String=fields[k]?.str()?:error("Missing $k")
private fun JValue.Obj.obj(k:String):JValue.Obj=fields[k]?.obj()?:error("Missing $k")
private fun JValue.Obj.arr(k:String):JValue.Arr=fields[k] as? JValue.Arr?:error("Missing $k")
private fun JValue.Obj.int(k:String):Int{
    val v=(fields[k] as? JValue.IntNum)?.value?:error("Missing int $k")
    require(v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong());return v.toInt()
}
private fun JValue.Obj.nullableStr(k:String):String?=when(val v=fields[k]?:error("Missing $k")){
    JValue.Null->null
    is JValue.Str->v.value
    else->error("Expected string/null $k")
}
private fun JValue.Obj.requireKeys(vararg keys:String){require(fields.keys==keys.toSet()){"Schema mismatch"}}
