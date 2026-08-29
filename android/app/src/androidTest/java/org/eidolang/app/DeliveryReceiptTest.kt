package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.feature.home.DeliveryLedger
import org.eidolang.feature.home.EidoOnionServer
import org.eidolang.feature.home.OnionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * When does a sent eidogram earn its second tick?
 *
 * The claim is narrow and worth stating precisely: two ticks mean every file of the segment left
 * this device, observed by the server that handed them out. The dangerous failure is not a missing
 * tick but an early one — a recipient fetches the manifest, then its circuit dies, and the sender is
 * told the message arrived. So the assertion that carries the weight is the negative one in the
 * middle: after the first file, still one tick.
 *
 * No Tor here. The circuit is proven byte-exact in `OnionTransportTest` and across two devices in
 * `TwoDeviceOnionDeliveryTest`; what is under test is the counting, and running it over loopback
 * makes it a few seconds rather than a few minutes. A private port keeps it clear of the app's own
 * node if that happens to be up.
 */
class DeliveryReceiptTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val infoHash = hex32()
    private var server: EidoOnionServer? = null

    @After
    fun cleanUp() {
        server?.stop()
        File(ctx.filesDir, "delivery-ledger/$infoHash.bin").delete()
        File(ctx.filesDir, "onion/segment/$infoHash").deleteRecursively()
    }

    @Test
    fun secondTickAppearsOnlyWhenTheWholeSegmentHasGone() {
        val store = OnionStore(ctx)
        val ledger = DeliveryLedger(ctx)
        val paths = listOf("segment-manifest.json", "conversation.json", "messages/m1.json")
        paths.forEach { store.putSegmentFile(infoHash, it, it.toByteArray(Charsets.UTF_8)) }

        val contact = hex32()
        val sentAt = 1_000L
        ledger.published(infoHash, contact, paths.size, sentAt)
        assertFalse(
            "ничего ещё не забирали, а доставка уже засчитана",
            record(ledger).collected,
        )

        server = EidoOnionServer(store, PORT) { h, p -> ledger.served(h, p, 2_000L) }
        server!!.start()

        // A miss must not count. The path is well-formed and the infohash is one we are serving, so
        // only the check that bytes were actually produced stands between this and a false tick.
        assertEquals("промах должен быть 404", 404, statusOf("/segment/$infoHash/messages/nope.json"))
        // Against `servedPaths`, not against `collected`. Checking only the tick let a mutation that
        // counted 404s survive here and be caught two assertions later, under a name that blamed the
        // wrong thing — and a peer probing invented paths could reach the file count without ever
        // receiving a byte.
        assertTrue("404 записан как выданный файл", record(ledger).servedPaths.isEmpty())

        get("/segment/$infoHash/${paths[0]}")
        assertFalse(
            "манифест забрали — это ещё не доставка",
            record(ledger).collected,
        )
        println("DELIVERY PASS one tick after the manifest alone")

        get("/segment/$infoHash/${paths[1]}")
        assertFalse("сегмент отдан не полностью, а доставка засчитана", record(ledger).collected)

        get("/segment/$infoHash/${paths[2]}")
        val done = record(ledger)
        assertTrue("сегмент отдан целиком, а доставки нет", done.collected)
        assertEquals("время доставки не записано", 2_000L, done.collectedAtMs)
        println("DELIVERY PASS two ticks once every file has left the device")

        // Repeats are the normal case — a recipient that retries, or a second device of the same
        // contact — and must not move the instant the delivery is attributed to.
        get("/segment/$infoHash/${paths[0]}")
        assertEquals("повторный запрос переписал время доставки", 2_000L, record(ledger).collectedAtMs)

        assertEquals(
            "доставка не видна в сводке по контакту",
            sentAt,
            ledger.deliveredThroughByContact()[contact],
        )
        println("DELIVERY PASS the contact's ticks reach back to everything sent before $sentAt")
    }

    private fun record(ledger: DeliveryLedger) =
        ledger.all().single { it.infoHashV2Hex == infoHash }

    /** Plain HTTP to the loopback port the onion service would forward to. */
    private fun raw(path: String): String = Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", PORT), 3_000)
        s.soTimeout = 5_000
        s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    private fun get(path: String) {
        assertEquals("файл сегмента не отдан: $path", 200, statusOf(path))
    }

    private fun statusOf(path: String) =
        raw(path).substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: -1

    private fun hex32() = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private companion object { const val PORT = 6899 }
}
