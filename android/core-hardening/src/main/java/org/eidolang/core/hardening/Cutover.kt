package org.eidolang.core.hardening

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import org.eidolang.core.message.MessageParser
import org.eidolang.core.repository.*
import org.eidolang.core.vault.HistoryVaultArchivePackageCodec

data class HistoryDigestRowV1(
    val conversationId:String,
    val messageId:String,
    val parentMessageIds:List<String>,
    val documentContentHash:String,
)

data class HistoryEquivalenceDigestV1(
    val rows:List<HistoryDigestRowV1>,
    val digest:String,
)

object HistoryEquivalence {
    private fun rowJson(r:HistoryDigestRowV1):String=CanonicalJson.obj(mapOf(
        "conversation_id" to CanonicalJson.string(r.conversationId),
        "document_content_hash" to CanonicalJson.string(r.documentContentHash),
        "message_id" to CanonicalJson.string(r.messageId),
        "parent_message_ids" to CanonicalJson.arr(r.parentMessageIds.map(CanonicalJson::string)),
    ))

    fun compute(repository:MessengerRepository,messenger:LocalMessengerService):HistoryEquivalenceDigestV1{
        val rows=repository.conversations().sortedBy{it.conversationId}.flatMap{c->
            val docs=messenger.timeline(c.conversationId).associateBy{it.messageId}
            repository.messages(c.conversationId).map{r->
                val d=docs[r.messageId]?:error("Timeline document missing")
                HistoryDigestRowV1(
                    c.conversationId,r.messageId,r.parentMessageIds.sorted(),
                    EidogramCanonical.contentHash(d.document)
                )
            }
        }.sortedWith(compareBy<HistoryDigestRowV1>{it.conversationId}.thenBy{it.messageId})
        val body=CanonicalJson.arr(rows.map(::rowJson))
        return HistoryEquivalenceDigestV1(rows,HexSha256.ofUtf8(body))
    }

    fun requireEqual(a:HistoryEquivalenceDigestV1,b:HistoryEquivalenceDigestV1){
        require(a.digest==b.digest && a.rows==b.rows){"Migration history equivalence failed"}
    }
}

data class LegacyArtifactRefV1(
    val kind:String,
    val sha256:String,
)

data class LegacyCutoverBodyV1(
    val ownerUserId:String,
    val signerDeviceId:String,
    val signerSigningKeyId:String,
    val vaultId:String,
    val vaultPackageId:String,
    val historyDigest:String,
    val legacyArtifacts:List<LegacyArtifactRefV1>,
)

data class LegacyCutoverReceiptV1(
    val body:LegacyCutoverBodyV1,
    val cutoverId:String,
    val signatureB64:String,
)

object LegacyCutoverCodec {
    private fun legacyJson(a:LegacyArtifactRefV1)=CanonicalJson.obj(mapOf(
        "kind" to CanonicalJson.string(a.kind),
        "sha256" to CanonicalJson.string(a.sha256),
    ))

    fun bodyJson(b:LegacyCutoverBodyV1):String=CanonicalJson.obj(mapOf(
        "cutover_type" to CanonicalJson.string("EidoLegacyArchiveCutoverV1"),
        "cutover_version" to CanonicalJson.string("1.0.0"),
        "history_digest" to CanonicalJson.string(b.historyDigest),
        "legacy_artifacts" to CanonicalJson.arr(b.legacyArtifacts.map(::legacyJson)),
        "owner_user_id" to CanonicalJson.string(b.ownerUserId),
        "signer_device_id" to CanonicalJson.string(b.signerDeviceId),
        "signer_signing_key_id" to CanonicalJson.string(b.signerSigningKeyId),
        "vault_id" to CanonicalJson.string(b.vaultId),
        "vault_package_id" to CanonicalJson.string(b.vaultPackageId),
    ))

    fun json(r:LegacyCutoverReceiptV1):String=CanonicalJson.obj(mapOf(
        "body" to bodyJson(r.body),
        "cutover_id" to CanonicalJson.string(r.cutoverId),
        "signature_b64" to CanonicalJson.string(r.signatureB64),
    ))
}

object LegacyCutover {
    fun issue(
        legacyArtifacts:Collection<Pair<String,ByteArray>>,
        vaultPackageCanonical:String,
        sourceDigest:HistoryEquivalenceDigestV1,
        recoveredDigest:HistoryEquivalenceDigestV1,
        signer:DevicePrivateCrypto,
    ):LegacyCutoverReceiptV1{
        HistoryEquivalence.requireEqual(sourceDigest,recoveredDigest)
        val pkg=HistoryVaultArchivePackageCodec.parseCanonical(vaultPackageCanonical)
        require(pkg.ownerUserId==signer.user.userId)
        val descriptor=org.eidolang.core.vault.HistoryVaultDescriptorCodec.parseCanonical(
            pkg.vaultDescriptorCanonicalJson
        )
        val refs=legacyArtifacts.map{(kind,bytes)->
            require(kind.matches(Regex("[A-Z0-9_]{1,40}")))
            LegacyArtifactRefV1(kind,HexSha256.of(bytes))
        }.sortedWith(compareBy<LegacyArtifactRefV1>{it.kind}.thenBy{it.sha256})
        require(refs.isNotEmpty())
        val body=LegacyCutoverBodyV1(
            signer.user.userId,signer.certificate.deviceId,signer.certificate.body.signingKeyId,
            descriptor.vaultId,pkg.packageId,sourceDigest.digest,refs
        )
        val bytes=LegacyCutoverCodec.bodyJson(body).toByteArray(Charsets.UTF_8)
        return LegacyCutoverReceiptV1(
            body,HexSha256.of(bytes),B64Url.encode(signer.signMessage(bytes))
        )
    }

    fun verify(receipt:LegacyCutoverReceiptV1,signer:PublicIdentityBundleV1):Boolean=runCatching{
        if(!IdentityVerifier.verifyBundle(signer))return false
        if(receipt.body.ownerUserId!=signer.user.userId)return false
        if(receipt.body.signerDeviceId!=signer.device.deviceId)return false
        if(receipt.body.signerSigningKeyId!=signer.device.body.signingKeyId)return false
        if(receipt.body.legacyArtifacts!=receipt.body.legacyArtifacts.sortedWith(
                compareBy<LegacyArtifactRefV1>{it.kind}.thenBy{it.sha256})) return false
        if(receipt.body.legacyArtifacts.distinct().size!=receipt.body.legacyArtifacts.size)return false
        val bytes=LegacyCutoverCodec.bodyJson(receipt.body).toByteArray(Charsets.UTF_8)
        if(receipt.cutoverId!=HexSha256.of(bytes))return false
        JcaCrypto.verify(
            PublicKeyCodec.ec(signer.device.body.signingPublicKeyB64),
            bytes,B64Url.decode(receipt.signatureB64)
        )
    }.getOrDefault(false)

    fun rejectCommittedLegacy(
        artifactBytes:ByteArray,
        receipt:LegacyCutoverReceiptV1,
    ){
        val h=HexSha256.of(artifactBytes)
        require(receipt.body.legacyArtifacts.none{it.sha256==h}){
            "Legacy archive was committed as deleted/retired by cutover ${receipt.cutoverId}"
        }
    }
}

object LegacyCutoverParser {
    fun parseCanonical(text:String):LegacyCutoverReceiptV1{
        val root=StrictJsonParser(text).parse().obj()
        root.requireKeys("body","cutover_id","signature_b64")
        val b=root.obj("body")
        b.requireKeys(
            "cutover_type","cutover_version","history_digest","legacy_artifacts",
            "owner_user_id","signer_device_id","signer_signing_key_id",
            "vault_id","vault_package_id"
        )
        require(b.str("cutover_type")=="EidoLegacyArchiveCutoverV1")
        require(b.str("cutover_version")=="1.0.0")
        val refs=b.arr("legacy_artifacts").items.map{raw->
            val a=raw.obj();a.requireKeys("kind","sha256")
            LegacyArtifactRefV1(a.str("kind"),a.str("sha256"))
        }
        val out=LegacyCutoverReceiptV1(
            LegacyCutoverBodyV1(
                b.str("owner_user_id"),b.str("signer_device_id"),b.str("signer_signing_key_id"),
                b.str("vault_id"),b.str("vault_package_id"),b.str("history_digest"),refs
            ),
            root.str("cutover_id"),root.str("signature_b64")
        )
        require(out.body.legacyArtifacts==out.body.legacyArtifacts.sortedWith(
            compareBy<LegacyArtifactRefV1>{it.kind}.thenBy{it.sha256}))
        require(out.body.legacyArtifacts.distinct().size==out.body.legacyArtifacts.size)
        require(out.cutoverId==HexSha256.ofUtf8(LegacyCutoverCodec.bodyJson(out.body)))
        require(LegacyCutoverCodec.json(out)==text)
        return out
    }
}

private fun JValue.obj():JValue.Obj=this as? JValue.Obj?:error("Expected object")
private fun JValue.str():String=(this as? JValue.Str)?.value?:error("Expected string")
private fun JValue.Obj.str(k:String):String=fields[k]?.str()?:error("Missing $k")
private fun JValue.Obj.obj(k:String):JValue.Obj=fields[k]?.obj()?:error("Missing $k")
private fun JValue.Obj.arr(k:String):JValue.Arr=fields[k] as? JValue.Arr?:error("Missing $k")
private fun JValue.Obj.requireKeys(vararg keys:String){require(fields.keys==keys.toSet()){"Schema mismatch"}}

