package org.eidolang.core.session

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import java.security.KeyPair
import java.security.SecureRandom

data class DevicePreKeyBundleBodyV1(
    val userId: String,
    val deviceId: String,
    val signingKeyId: String,
    val signedPreKeyId: String,
    val signedPreKeyPublicB64: String,
    val oneTimePreKeyId: String,
    val oneTimePreKeyPublicB64: String,
)

data class DevicePreKeyBundleV1(
    val body: DevicePreKeyBundleBodyV1,
    val bundleId: String,
    val deviceSignatureB64: String,
)

object PreKeyCanonical {
    fun bodyJson(b: DevicePreKeyBundleBodyV1): String = CanonicalJson.obj(mapOf(
        "bundle_type" to CanonicalJson.string("EidoDevicePreKeyBundleV1"),
        "bundle_version" to CanonicalJson.string("1.0.0"),
        "device_id" to CanonicalJson.string(b.deviceId),
        "one_time_prekey_id" to CanonicalJson.string(b.oneTimePreKeyId),
        "one_time_prekey_public_b64" to CanonicalJson.string(b.oneTimePreKeyPublicB64),
        "signed_prekey_id" to CanonicalJson.string(b.signedPreKeyId),
        "signed_prekey_public_b64" to CanonicalJson.string(b.signedPreKeyPublicB64),
        "signing_key_id" to CanonicalJson.string(b.signingKeyId),
        "user_id" to CanonicalJson.string(b.userId),
    ))

    fun json(b: DevicePreKeyBundleV1): String = CanonicalJson.obj(mapOf(
        "body" to bodyJson(b.body),
        "bundle_id" to CanonicalJson.string(b.bundleId),
        "device_signature_b64" to CanonicalJson.string(b.deviceSignatureB64),
    ))

    fun parseCanonical(text: String): DevicePreKeyBundleV1 {
        val root = StrictJsonParser(text).parse().obj()
        root.requireKeys("body","bundle_id","device_signature_b64")
        val b=root.obj("body")
        b.requireKeys(
            "bundle_type","bundle_version","device_id","one_time_prekey_id",
            "one_time_prekey_public_b64","signed_prekey_id","signed_prekey_public_b64",
            "signing_key_id","user_id"
        )
        require(b.str("bundle_type")=="EidoDevicePreKeyBundleV1")
        require(b.str("bundle_version")=="1.0.0")
        val out=DevicePreKeyBundleV1(
            DevicePreKeyBundleBodyV1(
                userId=b.str("user_id"),
                deviceId=b.str("device_id"),
                signingKeyId=b.str("signing_key_id"),
                signedPreKeyId=b.str("signed_prekey_id"),
                signedPreKeyPublicB64=b.str("signed_prekey_public_b64"),
                oneTimePreKeyId=b.str("one_time_prekey_id"),
                oneTimePreKeyPublicB64=b.str("one_time_prekey_public_b64"),
            ),
            root.str("bundle_id"),
            root.str("device_signature_b64"),
        )
        require(json(out)==text){"Prekey bundle is not canonical"}
        return out
    }
}

object PreKeyVerifier {
    fun verify(bundle: DevicePreKeyBundleV1, identity: PublicIdentityBundleV1): Boolean = runCatching {
        if(!IdentityVerifier.verifyBundle(identity)) return false
        if(bundle.body.userId!=identity.user.userId) return false
        if(bundle.body.deviceId!=identity.device.deviceId) return false
        if(bundle.body.signingKeyId!=identity.device.body.signingKeyId) return false

        val spk=PublicKeyCodec.ec(bundle.body.signedPreKeyPublicB64)
        val opk=PublicKeyCodec.ec(bundle.body.oneTimePreKeyPublicB64)
        if(bundle.body.signedPreKeyId!="spk:"+HexSha256.of(spk.encoded)) return false
        if(bundle.body.oneTimePreKeyId!="opk:"+HexSha256.of(opk.encoded)) return false

        val bytes=PreKeyCanonical.bodyJson(bundle.body).toByteArray(Charsets.UTF_8)
        if(bundle.bundleId!=HexSha256.of(bytes)) return false
        JcaCrypto.verify(
            PublicKeyCodec.ec(identity.device.body.signingPublicKeyB64),
            bytes,
            B64Url.decode(bundle.deviceSignatureB64),
        )
    }.getOrDefault(false)
}

data class ConsumedRecipientPreKeys(
    val signedPreKey: KeyPair,
    val oneTimePreKey: KeyPair,
)

data class PreKeySecretEntryV1(
    val preKeyId:String,
    val privateKeyB64:String,
    val publicKeyB64:String,
)

data class PreKeySecretSnapshotV1(
    val signedPreKey:PreKeySecretEntryV1,
    val oneTimePreKeys:List<PreKeySecretEntryV1>,
)

class JvmPreKeyStore private constructor(
    private val identity: DevicePrivateCrypto,
    private val random: SecureRandom,
    private var signedPreKey: KeyPair,
    private val oneTime: LinkedHashMap<String,KeyPair>,
    val maxPendingOneTimePreKeys:Int,
) {
    init {
        require(maxPendingOneTimePreKeys > 0)
        require(oneTime.size <= maxPendingOneTimePreKeys)
    }

    constructor(
        identity:DevicePrivateCrypto,
        random:SecureRandom=SecureRandom(),
        maxPendingOneTimePreKeys:Int=128,
    ):this(identity,random,P256Dh.generate(random),linkedMapOf(),maxPendingOneTimePreKeys)

    fun rotateSignedPreKey() {
        signedPreKey = P256Dh.generate(random)
    }

    fun createBundle(): DevicePreKeyBundleV1 {
        require(oneTime.size < maxPendingOneTimePreKeys) {
            "Pending one-time prekey resource limit exceeded"
        }
        val opk=P256Dh.generate(random)
        val opkId="opk:"+HexSha256.of(opk.public.encoded)
        oneTime[opkId]=opk

        val spkId="spk:"+HexSha256.of(signedPreKey.public.encoded)
        val body=DevicePreKeyBundleBodyV1(
            userId=identity.user.userId,
            deviceId=identity.certificate.deviceId,
            signingKeyId=identity.certificate.body.signingKeyId,
            signedPreKeyId=spkId,
            signedPreKeyPublicB64=PublicKeyCodec.encode(signedPreKey.public),
            oneTimePreKeyId=opkId,
            oneTimePreKeyPublicB64=PublicKeyCodec.encode(opk.public),
        )
        val bytes=PreKeyCanonical.bodyJson(body).toByteArray(Charsets.UTF_8)
        return DevicePreKeyBundleV1(
            body,
            HexSha256.of(bytes),
            B64Url.encode(identity.signMessage(bytes)),
        )
    }

    fun consume(bundle: DevicePreKeyBundleV1): ConsumedRecipientPreKeys {
        val currentSpkId="spk:"+HexSha256.of(signedPreKey.public.encoded)
        require(bundle.body.signedPreKeyId==currentSpkId){"Signed prekey has rotated"}
        val opk=oneTime.remove(bundle.body.oneTimePreKeyId)
            ?: error("One-time prekey already consumed or unknown")
        return ConsumedRecipientPreKeys(signedPreKey,opk)
    }

    fun pendingOneTimePreKeyCount(): Int = oneTime.size

    fun exportSecretSnapshot():PreKeySecretSnapshotV1 =
        PreKeySecretSnapshotV1(
            PreKeySecretEntryV1(
                "spk:"+HexSha256.of(signedPreKey.public.encoded),
                B64Url.encode(signedPreKey.private.encoded),
                PublicKeyCodec.encode(signedPreKey.public),
            ),
            oneTime.entries.sortedBy{it.key}.map{(id,kp)->
                PreKeySecretEntryV1(id,B64Url.encode(kp.private.encoded),PublicKeyCodec.encode(kp.public))
            }
        )

    companion object{
        fun restore(
            identity:DevicePrivateCrypto,
            snapshot:PreKeySecretSnapshotV1,
            random:SecureRandom=SecureRandom(),
            maxPendingOneTimePreKeys:Int=128,
        ):JvmPreKeyStore{
            fun kp(e:PreKeySecretEntryV1)=KeyPair(
                PublicKeyCodec.ec(e.publicKeyB64),
                PublicKeyCodec.privateEc(e.privateKeyB64)
            )
            val spk=kp(snapshot.signedPreKey)
            require(snapshot.signedPreKey.preKeyId=="spk:"+HexSha256.of(spk.public.encoded))
            val opks=linkedMapOf<String,KeyPair>()
            snapshot.oneTimePreKeys.forEach{e->
                val key=kp(e)
                require(e.preKeyId=="opk:"+HexSha256.of(key.public.encoded))
                require(opks.put(e.preKeyId,key)==null)
            }
            require(opks.size <= maxPendingOneTimePreKeys) {
                "Persisted one-time prekey state exceeds resource limit"
            }
            return JvmPreKeyStore(identity,random,spk,opks,maxPendingOneTimePreKeys)
        }
    }
}

private fun JValue.obj():JValue.Obj=this as? JValue.Obj?:error("Expected object")
private fun JValue.str():String=(this as? JValue.Str)?.value?:error("Expected string")
private fun JValue.Obj.obj(k:String):JValue.Obj=fields[k]?.obj()?:error("Missing $k")
private fun JValue.Obj.str(k:String):String=fields[k]?.str()?:error("Missing $k")
private fun JValue.Obj.requireKeys(vararg keys:String){require(fields.keys==keys.toSet()){"Schema mismatch"}}
