package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.vault.AndroidBootstrapRecoverySecret
import org.eidolang.core.vault.AndroidHistoryVaultSecretStore
import org.eidolang.feature.home.EidoOnionNode
import org.eidolang.feature.home.OnionServiceKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Is the address derivable from the very first run, and does it stay put when a vault appears?
 *
 * The derivation itself is proven elsewhere. What matters here is the link to the running app, which
 * is where the requirement is actually won or lost — a correct derivation nothing reaches leaves the
 * address as fragile as before, and nothing in the UI would say so.
 *
 * The second claim is the one worth the trouble. Creating a history vault used to generate a fresh
 * recovery secret, which would have moved the address at exactly the moment the owner started caring
 * about backups, invalidating every card already handed out.
 *
 * Tor-free, and it puts the device's vault state back as it found it.
 */
class OnionVaultDiscoveryTest {

    @Test
    fun addressIsDerivableFromFirstRunAndSurvivesVaultCreation() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val activeFile = File(ctx.filesDir, "history-vault-active-v1.txt")
        // Refuse to touch a real vault rather than risk restoring it wrongly.
        org.junit.Assume.assumeFalse("на устройстве есть хранилище истории", activeFile.exists())

        val first = EidoOnionNode.derivedKey(ctx)
        assertNotNull("без хранилища адрес всё равно должен выводиться", first)
        assertEquals("вывод неустойчив между вызовами", first, EidoOnionNode.derivedKey(ctx))
        println("ONIONVAULT PASS address derivable before any vault exists")

        // Now the app creates a vault, the way ArchiveScreen does it.
        val vaultId = "b".repeat(64)
        val store = AndroidHistoryVaultSecretStore(ctx)
        try {
            val adopted = AndroidBootstrapRecoverySecret.adopt(ctx, vaultId)
            activeFile.writeText(vaultId, Charsets.US_ASCII)

            assertArrayEquals(
                "хранилище взяло не тот секрет",
                AndroidBootstrapRecoverySecret.ensure(ctx).copyBytes(),
                adopted.copyBytes(),
            )
            assertEquals(
                "адрес сменился при создании хранилища",
                first,
                EidoOnionNode.derivedKey(ctx),
            )
            assertEquals(
                "выведенный ключ не совпал с секретом хранилища",
                OnionServiceKey.fromRecoverySecret(adopted.copyBytes()),
                EidoOnionNode.derivedKey(ctx),
            )
            println("ONIONVAULT PASS creating a vault leaves the address unchanged")
        } finally {
            activeFile.delete()
            store.delete(vaultId)
        }
        assertEquals("состояние устройства не восстановлено", first, EidoOnionNode.derivedKey(ctx))
    }
}
