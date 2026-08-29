package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.feature.home.EidoTor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first Tor gate: does this device get a working v3 onion address of its own?
 *
 * Reachability is the only thing the relay was still needed for, and an onion address removes it —
 * but only if Tor actually bootstraps on a real phone, on a real mobile network. That is a runtime
 * claim, so it is made by running it.
 *
 * Every route is printed, connected or not. The first run of this test failed with nothing but
 * `state=CONNECTING`, which says only "it did not work" — it does not distinguish a blocked network
 * from a broken binary from a route that was never tried at all.
 */
@NativeSessionHeavy
class TorOnionTest {

    @Test
    fun deviceGetsAStableOnionAddress() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tor = EidoTor.of(ctx)

        val started = System.currentTimeMillis()
        val onion = tor.startAndPublish()
        val seconds = (System.currentTimeMillis() - started) / 1000
        tor.attempts.forEach {
            println("TOR route=${it.route} ${it.seconds}s connected=${it.connected}")
        }
        println("TOR state=${tor.state} onion=$onion bootstrap=${seconds}s")

        val routes = tor.attempts.joinToString { "${it.route}:${it.seconds}s" }
        assertNotNull("Tor не подключился (state=${tor.state}); маршруты: $routes", onion)
        assertEquals("CONNECTED", tor.state)
        // A v3 address is 56 base32 characters plus the suffix.
        assertTrue("не похоже на v3 onion: $onion", onion!!.matches(Regex("[a-z2-7]{56}\\.onion")))
        println("TOR PASS v3 onion address published: $onion via ${tor.attempts.last().route}")

        // The address must survive a restart, or every contact card goes stale the moment the app is
        // killed. Only a second run — a genuinely new process — can show that, so the first run
        // records the address and later runs compare against it.
        val seen = java.io.File(ctx.filesDir, "tor/first-onion-address.txt")
        if (seen.exists()) {
            assertEquals("onion-адрес не пережил перезапуск", seen.readText().trim(), onion)
            println("TOR PASS address stable across restarts")
        } else {
            seen.parentFile?.mkdirs()
            seen.writeText(onion)
            println("TOR NOTE first run — address recorded; re-run checks it survives a restart")
        }
    }
}
