package org.eidolang.app

import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.crypto.JcaCrypto
import org.eidolang.core.hardening.AndroidLocalSecretBox
import org.eidolang.core.vault.AndroidHistoryVaultSecretStore
import org.eidolang.core.vault.HistoryVaultRecoverySecret
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Substantiates the one thing a physical device adds over the emulator: that R22's private key
 * material actually lives in secure hardware rather than in a software-emulated keystore.
 *
 * Nothing in the codebase requests StrongBox or user authentication, so the expectation is
 * TRUSTED_ENVIRONMENT (TEE), not STRONGBOX.
 *
 * `PackageManager.FEATURE_HARDWARE_KEYSTORE` must NOT be used to decide whether to assert: an
 * `android-34` emulator advertises that feature (`=300`) and still produces SECURITY_LEVEL_SOFTWARE
 * for every alias. Only `KeyInfo.getSecurityLevel()` is trustworthy. The gate is therefore skipped
 * by emulator detection and enforced everywhere else — an emulator admission run genuinely does
 * not establish hardware backing.
 */
class KeystoreSecurityLevelTest {

    private val aliases = listOf(
        AndroidKeystoreIdentityStore.ROOT_ALIAS,
        AndroidKeystoreIdentityStore.DEVICE_SIGN_ALIAS,
        AndroidKeystoreIdentityStore.DEVICE_ENC_ALIAS,
        AndroidLocalSecretBox.ALIAS,
        AndroidHistoryVaultSecretStore.ALIAS,
    )

    private fun levelName(level: Int): String = when (level) {
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE"
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TRUSTED_ENVIRONMENT"
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX"
        KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "UNKNOWN_SECURE"
        else -> "UNKNOWN($level)"
    }

    private fun keyInfo(ks: KeyStore, alias: String): KeyInfo {
        val key = ks.getKey(alias, null) ?: error("alias missing after setup: $alias")
        return when (key) {
            is PrivateKey ->
                KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                    .getKeySpec(key, KeyInfo::class.java)
            is SecretKey ->
                SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                    .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            else -> error("unexpected key type for $alias: ${key::class.java}")
        }
    }

    @Test
    fun r22PrivateKeyMaterialIsHardwareBacked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val random = SecureRandom()

        // Force every alias into existence before inspecting it.
        AndroidKeystoreIdentityStore(context).ensure()
        AndroidLocalSecretBox(context).seal("admission", "keylevel-probe", JcaCrypto.randomBytes(32, random))
        AndroidHistoryVaultSecretStore(context).let { store ->
            val vaultId = "0".repeat(64)
            store.save(vaultId, HistoryVaultRecoverySecret.generate(random))
            store.delete(vaultId)
        }

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val hardwareAdvertised = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_HARDWARE_KEYSTORE)
        val isEmulator = Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.startsWith("generic") ||
            Build.MODEL.contains("sdk_gphone")

        println("KEYLEVEL device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} hardware_keystore=$hardwareAdvertised emulator=$isEmulator")

        val software = mutableListOf<String>()
        aliases.forEach { alias ->
            val info = keyInfo(ks, alias)
            val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                info.securityLevel
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
                else KeyProperties.SECURITY_LEVEL_SOFTWARE
            }
            println("KEYLEVEL $alias -> ${levelName(level)}")
            if (level == KeyProperties.SECURITY_LEVEL_SOFTWARE) software += alias
        }

        if (isEmulator) {
            println("KEYLEVEL emulator runtime: hardware backing NOT established (software=$software)")
        } else {
            assertTrue(
                "physical device keys must not be software-backed: $software",
                software.isEmpty(),
            )
        }
    }
}
