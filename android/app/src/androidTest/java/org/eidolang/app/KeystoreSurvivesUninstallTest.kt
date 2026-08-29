package org.eidolang.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * Does an AndroidKeyStore entry outlive an uninstall?
 *
 * This decides where the onion service key can live. If Keystore entries survive, the address can be
 * kept there and survives a reinstall; if they do not, no amount of hardware backing helps and the
 * address has to be carried by the recovery backup instead.
 *
 * Deliberately a two-part experiment run by hand rather than an assertion: the answer is a property
 * of the platform, not of this code, and asserting a guess would just encode the guess. Run it, then
 * uninstall the app, reinstall and run it again — the second run's `existed=` is the answer.
 *
 * Run on a disposable emulator only. On a real device an uninstall would take the owner's identity
 * with it.
 */
class KeystoreSurvivesUninstallTest {

    @Test
    fun probeKeystoreAliasAcrossInstalls() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existed = ks.containsAlias(ALIAS)
        if (!existed) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }.generateKey()
        }
        // A file in app storage, for contrast: everybody agrees this one is wiped by an uninstall,
        // so if the two answers differ the Keystore entry really is outliving app data.
        val marker = java.io.File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "keystore-probe.txt"
        )
        val fileExisted = marker.exists()
        marker.writeText("probe")

        println("KSPROBE alias existed=$existed fileExisted=$fileExisted")
    }

    private companion object { const val ALIAS = "eidolang.uninstall-probe.v1" }
}
