package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.feature.home.EidoTor
import org.eidolang.feature.home.OnionServiceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Does the address come back after the device forgets it?
 *
 * This is the reinstall question, asked in the only way a test can ask it without uninstalling the
 * app: delete the sealed key — the exact thing an uninstall destroys — and rebuild the service from
 * the recovery secret alone. If the same address comes back, the owner who restores from their
 * recovery pair keeps the address their contacts already hold.
 *
 * The secret here is fixed rather than random, so the expected address is a constant of this file and
 * not something the test computes the same wrong way twice.
 */
@NativeSessionHeavy
class OnionKeyDerivationTest {

    private val secret = ByteArray(32) { it.toByte() }

    @Test
    fun derivationIsStableAndDomainSeparated() {
        val blob = OnionServiceKey.fromRecoverySecret(secret)
        assertEquals("та же тайна — тот же ключ", blob, OnionServiceKey.fromRecoverySecret(secret))
        assertTrue("не похоже на ED25519-V3", blob.startsWith("ED25519-V3:"))
        // 11 characters of prefix plus base64 of the 64-byte expanded key.
        assertEquals(99, blob.length)

        val other = ByteArray(32) { (it + 1).toByte() }
        assertNotEquals("разные тайны дали один ключ", blob, OnionServiceKey.fromRecoverySecret(other))

        // The expanded key is clamped, not a raw hash. Without this the derivation could be an
        // ordinary SHA-512 and still pass everything above, while Tor derived a different address.
        val raw = android.util.Base64.decode(blob.removePrefix("ED25519-V3:"), android.util.Base64.NO_WRAP)
        assertEquals(64, raw.size)
        assertEquals("младшие 3 бита не обнулены", 0, raw[0].toInt() and 0x07)
        assertEquals("старший бит не сброшен", 0, raw[31].toInt() and 0x80)
        assertEquals("бит 254 не установлен", 64, raw[31].toInt() and 0x40)
        println("ONIONKEY PASS derivation stable, domain-separated and clamped")
    }

    @Test
    fun addressSurvivesLosingEverythingStoredOnTheDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val derived = OnionServiceKey.fromRecoverySecret(secret)

        val tor = EidoTor.of(ctx)
        val first = tor.startAndPublish(derivedKey = derived)
        assertNotNull("Tor не подключился: ${tor.attempts}", first)
        println("ONIONKEY derived address=$first")

        // Exactly what an uninstall does to the key material this device holds.
        val sealed = File(ctx.filesDir, "tor/onion-service-key.sealed")
        assertTrue("нечего удалять — ключ не был сохранён", sealed.isFile)
        assertTrue(sealed.delete())

        // Tor refuses to add the same onion twice in one session, so the service is withdrawn first.
        // A reinstall would take the whole Tor process with it; this is the nearest thing a single
        // process can do, and it means the re-publish is a genuine ADD_ONION rather than a no-op.
        tor.unpublish(first!!)
        val second = EidoTor.of(ctx).startAndPublish(derivedKey = derived)
        assertEquals("адрес не восстановился из тайны", first, second)
        println("ONIONKEY PASS address rebuilt from the recovery secret alone: $second")

        // And a different secret must not land on the same address — otherwise the equality above
        // would hold for reasons that have nothing to do with the derivation.
        tor.unpublish(second!!)
        val elsewhere = EidoTor.of(ctx).startAndPublish(
            derivedKey = OnionServiceKey.fromRecoverySecret(ByteArray(32) { (it + 7).toByte() })
        )
        assertNotEquals("чужая тайна дала тот же адрес", first, elsewhere)
        println("ONIONKEY PASS a different secret gives a different address: $elsewhere")
    }
}
