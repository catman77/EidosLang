package org.eidolang.core.crypto

import android.content.Context
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import javax.crypto.Cipher

/**
 * Production Android private-key holder.
 *
 * Runtime handles are non-exportable AndroidKeyStore keys. New profiles also retain sealed
 * recovery material, so an independently encrypted account backup can recreate the same keys.
 * Existing hardware-only identities keep their keys and user id unchanged.
 */
class AndroidKeystoreIdentityStore(
    private val context: Context,
) {
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(): Boolean =
        ks.containsAlias(ROOT_ALIAS) &&
            ks.containsAlias(DEVICE_SIGN_ALIAS) &&
            ks.containsAlias(DEVICE_ENC_ALIAS)

    fun loadIfPresent(): AndroidPrivateIdentity? {
        repairSavedKeys()
        requireCompleteKeySet()
        return if (exists()) ensure() else null
    }

    fun ensure(): AndroidPrivateIdentity {
        repairSavedKeys()
        requireCompleteKeySet()
        check(exists() || !hasSavedProfile()) {
            "Сохранённые данные профиля найдены, но его ключи отсутствуют. " +
                "Новый профиль поверх них не создаётся. Нужна копия для восстановления."
        }
        if (!exists()) AndroidRecoverableKeyStore(context).createPrimary()

        val rootPublic = ks.getCertificate(ROOT_ALIAS).publicKey
        val deviceSignPublic = ks.getCertificate(DEVICE_SIGN_ALIAS).publicKey
        val deviceEncPublic = ks.getCertificate(DEVICE_ENC_ALIAS).publicKey
        val user = IdentityFactory.user(rootPublic)
        val body = IdentityFactory.deviceBody(user, deviceSignPublic, deviceEncPublic)
        val bodyBytes = IdentityCanonical.deviceBodyJson(body).toByteArray(Charsets.UTF_8)
        val bodyHash = HexSha256.of(bodyBytes)
        val deviceId = "dev:" + bodyHash
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedSigB64 = prefs.getString(PREF_ROOT_SIG, null)
        val cachedBodyHash = prefs.getString(PREF_BODY_HASH, null)
        val reusable = cachedBodyHash == bodyHash && cachedSigB64 != null && runCatching {
            JcaCrypto.verify(rootPublic, bodyBytes, B64Url.decode(cachedSigB64))
        }.getOrDefault(false)
        val rootSigB64 = if (reusable) {
            cachedSigB64!!
        } else {
            val created = Signature.getInstance(CryptoSuiteV1.SIGNATURE_JCA).run {
                initSign(ks.getKey(ROOT_ALIAS, null) as PrivateKey)
                update(bodyBytes)
                B64Url.encode(sign())
            }
            prefs.edit().putString(PREF_BODY_HASH, bodyHash).putString(PREF_ROOT_SIG, created).apply()
            created
        }
        val cert = DeviceCertificateV1(
            body = body,
            deviceId = deviceId,
            rootSignatureB64 = rootSigB64,
        )
        check(IdentityVerifier.verifyDevice(user, cert))
        return AndroidPrivateIdentity(ks, user, cert)
    }

    private fun requireCompleteKeySet() {
        val present = listOf(ROOT_ALIAS, DEVICE_SIGN_ALIAS, DEVICE_ENC_ALIAS).count(ks::containsAlias)
        check(present == 0 || present == 3) {
            "Не все ключи прежнего профиля доступны. Автоматическая замена ключей запрещена: " +
                "она изменила бы профиль или сделала переписку недоступной."
        }
    }

    private fun repairSavedKeys() {
        if (!exists()) AndroidRecoverableKeyStore(context).repairIfSaved(AndroidRecoverableKeyStore.primaryAliases())
    }

    fun hasSavedProfile(): Boolean {
        if (context.filesDir.resolve("eidolang-profile-v1.bin").exists() ||
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(PREF_BODY_HASH)) return true
        val file = context.getDatabasePath("eidolang-messenger-r18.db")
        if (!file.exists()) return false
        return runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                listOf("contacts", "conversations", "messages", "own_devices").any { table ->
                    db.rawQuery("SELECT 1 FROM $table LIMIT 1", null).use { it.moveToFirst() }
                }
            }
        }.getOrDefault(true)
    }

    companion object {
        const val ROOT_ALIAS = "eidolang.root-signing.v1"
        const val DEVICE_SIGN_ALIAS = "eidolang.device-signing.v1"
        const val DEVICE_ENC_ALIAS = "eidolang.device-decrypt.v1"
        private const val PREFS_NAME = "eidolang-public-identity-v1"
        private const val PREF_BODY_HASH = "device_certificate_body_hash"
        private const val PREF_ROOT_SIG = "device_certificate_root_signature"
    }
}

class AndroidPrivateIdentity(
    private val ks: KeyStore,
    override val user: UserIdentityV1,
    override val certificate: DeviceCertificateV1,
) : DevicePrivateCrypto {
    override fun signMessage(bytes: ByteArray): ByteArray =
        Signature.getInstance(CryptoSuiteV1.SIGNATURE_JCA).run {
            initSign(ks.getKey(AndroidKeystoreIdentityStore.DEVICE_SIGN_ALIAS, null) as PrivateKey)
            update(bytes)
            sign()
        }

    override fun unwrapMessageKey(wrapped: ByteArray): ByteArray {
        val privateKey = ks.getKey(AndroidKeystoreIdentityStore.DEVICE_ENC_ALIAS, null) as PrivateKey
        val cipher = Cipher.getInstance(CryptoSuiteV1.RSA_JCA)
        // Pre-API 35 AndroidKeyStore uses SHA-1 as the MGF1 default for RSA-OAEP.
        // Try the fully explicit suite first; default initialization is the compatible fallback.
        runCatching { cipher.init(Cipher.DECRYPT_MODE, privateKey, JcaCrypto.oaepSpec) }
            .getOrElse { cipher.init(Cipher.DECRYPT_MODE, privateKey) }
        return cipher.doFinal(wrapped)
    }
}
