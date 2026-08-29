package org.eidolang.core.session

import org.eidolang.core.canonical.*

object SessionStateCanonical {
    fun skippedJson(k:SessionSkippedKeyV1):String=CanonicalJson.obj(mapOf(
        "dh_public_b64" to CanonicalJson.string(k.dhPublicB64),
        "message_key_b64" to CanonicalJson.string(k.messageKeyB64),
        "message_number" to CanonicalJson.int(k.messageNumber),
    ))

    fun json(s:SessionSecretSnapshotV1):String=CanonicalJson.obj(mapOf(
        "conversation_id" to CanonicalJson.string(s.conversationId),
        "local_device_id" to CanonicalJson.string(s.localDeviceId),
        "local_user_id" to CanonicalJson.string(s.localUserId),
        "needs_send_ratchet" to CanonicalJson.int(if(s.needsSendRatchet)1 else 0),
        "previous_send_n" to CanonicalJson.int(s.previousSendN),
        "receiving_chain_key_b64" to CanonicalJson.string(s.receivingChainKeyB64),
        "recv_n" to CanonicalJson.int(s.recvN),
        "remote_device_id" to CanonicalJson.string(s.remoteDeviceId),
        "remote_dh_public_b64" to CanonicalJson.string(s.remoteDhPublicB64),
        "remote_user_id" to CanonicalJson.string(s.remoteUserId),
        "root_key_b64" to CanonicalJson.string(s.rootKeyB64),
        "self_dh_private_b64" to CanonicalJson.string(s.selfDhPrivateB64),
        "self_dh_public_b64" to CanonicalJson.string(s.selfDhPublicB64),
        "send_n" to CanonicalJson.int(s.sendN),
        "sending_chain_key_b64" to CanonicalJson.string(s.sendingChainKeyB64),
        "session_id" to CanonicalJson.string(s.sessionId),
        "skipped_keys" to CanonicalJson.arr(s.skippedKeys.map(::skippedJson)),
        "state_type" to CanonicalJson.string("EidoForwardSessionSecretStateV1"),
        "state_version" to CanonicalJson.string("1.0.0"),
    ))

    fun parseCanonical(text:String):SessionSecretSnapshotV1{
        val o=StrictJsonParser(text).parse().obj()
        o.requireKeys(
            "conversation_id","local_device_id","local_user_id","needs_send_ratchet",
            "previous_send_n","receiving_chain_key_b64","recv_n","remote_device_id",
            "remote_dh_public_b64","remote_user_id","root_key_b64","self_dh_private_b64",
            "self_dh_public_b64","send_n","sending_chain_key_b64","session_id",
            "skipped_keys","state_type","state_version"
        )
        require(o.str("state_type")=="EidoForwardSessionSecretStateV1")
        require(o.str("state_version")=="1.0.0")
        val skipped=o.arr("skipped_keys").items.map{raw->
            val k=raw.obj()
            k.requireKeys("dh_public_b64","message_key_b64","message_number")
            SessionSkippedKeyV1(k.str("dh_public_b64"),k.int("message_number"),k.str("message_key_b64"))
        }
        val flag=o.int("needs_send_ratchet")
        require(flag==0||flag==1)
        val out=SessionSecretSnapshotV1(
            o.str("session_id"),o.str("conversation_id"),o.str("local_user_id"),o.str("local_device_id"),
            o.str("remote_user_id"),o.str("remote_device_id"),o.str("root_key_b64"),
            o.str("sending_chain_key_b64"),o.str("receiving_chain_key_b64"),
            o.str("self_dh_private_b64"),o.str("self_dh_public_b64"),o.str("remote_dh_public_b64"),
            o.int("send_n"),o.int("recv_n"),o.int("previous_send_n"),flag==1,skipped
        )
        require(json(out)==text){"Session secret state is not canonical"}
        return out
    }
}

private fun JValue.obj():JValue.Obj=this as? JValue.Obj?:error("Expected object")
private fun JValue.str():String=(this as? JValue.Str)?.value?:error("Expected string")
private fun JValue.Obj.str(k:String):String=fields[k]?.str()?:error("Missing $k")
private fun JValue.Obj.int(k:String):Int{
    val v=(fields[k] as? JValue.IntNum)?.value?:error("Missing int $k")
    require(v in 0..Int.MAX_VALUE.toLong());return v.toInt()
}
private fun JValue.Obj.arr(k:String):JValue.Arr=fields[k] as? JValue.Arr?:error("Missing $k")
private fun JValue.Obj.requireKeys(vararg keys:String){require(fields.keys==keys.toSet()){"Schema mismatch"}}

object PreKeyStateCanonical {
    fun entryJson(e:PreKeySecretEntryV1):String=CanonicalJson.obj(mapOf(
        "prekey_id" to CanonicalJson.string(e.preKeyId),
        "private_key_b64" to CanonicalJson.string(e.privateKeyB64),
        "public_key_b64" to CanonicalJson.string(e.publicKeyB64),
    ))

    fun json(s:PreKeySecretSnapshotV1):String=CanonicalJson.obj(mapOf(
        "one_time_prekeys" to CanonicalJson.arr(s.oneTimePreKeys.map(::entryJson)),
        "signed_prekey" to entryJson(s.signedPreKey),
        "state_type" to CanonicalJson.string("EidoPreKeySecretStateV1"),
        "state_version" to CanonicalJson.string("1.0.0"),
    ))

    fun parseCanonical(text:String):PreKeySecretSnapshotV1{
        val o=StrictJsonParser(text).parse().obj()
        o.requireKeys("one_time_prekeys","signed_prekey","state_type","state_version")
        require(o.str("state_type")=="EidoPreKeySecretStateV1")
        require(o.str("state_version")=="1.0.0")
        fun parseEntry(v:JValue):PreKeySecretEntryV1{
            val e=v.obj();e.requireKeys("prekey_id","private_key_b64","public_key_b64")
            return PreKeySecretEntryV1(e.str("prekey_id"),e.str("private_key_b64"),e.str("public_key_b64"))
        }
        val out=PreKeySecretSnapshotV1(
            parseEntry(o.fields.getValue("signed_prekey")),
            o.arr("one_time_prekeys").items.map(::parseEntry),
        )
        require(out.oneTimePreKeys.map{it.preKeyId}==out.oneTimePreKeys.map{it.preKeyId}.sorted().distinct())
        require(json(out)==text){"Prekey secret state is not canonical"}
        return out
    }
}

