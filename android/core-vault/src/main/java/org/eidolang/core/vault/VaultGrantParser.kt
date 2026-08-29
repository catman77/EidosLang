package org.eidolang.core.vault

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.HexSha256

object HistoryVaultDeviceGrantParser {
    fun parseCanonical(text:String):HistoryVaultDeviceGrantV1{
        val root=StrictJsonParser(text).parse().obj()
        root.requireKeys("body","grant_id","signature_b64")
        val b=root.obj("body")
        b.requireKeys(
            "epoch","epoch_id","grant_type","grant_version","grantor_device_id",
            "grantor_signing_key_id","owner_user_id","target_device_id",
            "target_encryption_key_id","vault_id","wrapped_epoch_key_b64"
        )
        require(b.str("grant_type")=="EidoHistoryVaultDeviceGrantV1")
        require(b.str("grant_version")=="1.0.0")
        val out=HistoryVaultDeviceGrantV1(
            HistoryVaultDeviceGrantBodyV1(
                b.str("vault_id"),b.int("epoch"),b.str("epoch_id"),b.str("owner_user_id"),
                b.str("grantor_device_id"),b.str("grantor_signing_key_id"),
                b.str("target_device_id"),b.str("target_encryption_key_id"),
                b.str("wrapped_epoch_key_b64"),
            ),
            root.str("grant_id"),root.str("signature_b64")
        )
        require(out.body.epoch>=0)
        require(out.grantId==HexSha256.ofUtf8(HistoryVaultDeviceGrantCodec.bodyJson(out.body)))
        require(HistoryVaultDeviceGrantCodec.json(out)==text)
        return out
    }
}

private fun JValue.obj():JValue.Obj=this as? JValue.Obj?:error("Expected object")
private fun JValue.str():String=(this as? JValue.Str)?.value?:error("Expected string")
private fun JValue.Obj.str(k:String):String=fields[k]?.str()?:error("Missing $k")
private fun JValue.Obj.obj(k:String):JValue.Obj=fields[k]?.obj()?:error("Missing $k")
private fun JValue.Obj.int(k:String):Int{
    val v=(fields[k] as? JValue.IntNum)?.value?:error("Missing int $k")
    require(v in 0..Int.MAX_VALUE.toLong());return v.toInt()
}
private fun JValue.Obj.requireKeys(vararg keys:String){require(fields.keys==keys.toSet()){"Schema mismatch"}}
