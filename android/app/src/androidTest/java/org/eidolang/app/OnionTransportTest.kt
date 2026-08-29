package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.feature.home.EidoOnionClient
import org.eidolang.feature.home.EidoOnionServer
import org.eidolang.feature.home.EidoTor
import org.eidolang.feature.home.OnionStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Does this device's onion address actually carry our bytes?
 *
 * The previous gate proved only that an address exists. An address nothing answers on is worth
 * nothing, and it is exactly the state the app was in.
 *
 * The test fetches from the device's *own* onion address. That is not a loopback shortcut: the
 * request leaves through a Tor circuit, finds the service descriptor in the distributed hash
 * directory, meets it at a rendezvous point and comes back in. Every part of the path a second
 * device would use is exercised — the only thing it does not prove is that a *different* device can
 * reach it, and nothing about the circuit is aware of who is at the other end.
 */
@NativeSessionHeavy
class OnionTransportTest {

    @Test
    fun ownOnionServesHeadsAndSegments() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tor = EidoTor.of(ctx)
        val onion = tor.startAndPublish()
        tor.attempts.forEach { println("ONION route=${it.route} ${it.seconds}s connected=${it.connected}") }
        assertNotNull("Tor не подключился: ${tor.attempts}", onion)

        val store = OnionStore(ctx)
        val target = hex(20)                     // BEP44 target: SHA-1, 40 hex characters
        val infoHash = hex(32)                   // v2 infohash: SHA-256, 64 hex characters
        val headBytes = ByteArray(310).also { SecureRandom().nextBytes(it) }
        val segmentBytes = ByteArray(64 * 1024).also { SecureRandom().nextBytes(it) }
        store.putHead(target, headBytes)
        store.putSegmentFile(infoHash, "segment-manifest.json", segmentBytes)

        val server = EidoOnionServer(store)
        server.start()
        try {
            // Loopback first, deliberately. If the local socket is not answering then nothing about
            // Tor is being measured, and "the onion does not respond" would name the wrong culprit —
            // which is exactly how the first run of this test read.
            val local = java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", EidoTor.LOCAL_PORT), 3_000)
                s.getOutputStream().write("GET /hello HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
                s.getInputStream().readBytes().toString(Charsets.US_ASCII)
            }
            assertTrue("локальный сервер не отвечает: $local", local.contains("eidolang"))
            println("ONION PASS local server answers on 127.0.0.1:${EidoTor.LOCAL_PORT}")

            val hello = EidoOnionClient.getRetrying(onion!!, "/hello") {
                println("ONION descriptor attempt $it")
            }
            assertNotNull("онион не отвечает вообще", hello)
            println("ONION PASS circuit established to own service $onion")

            val head = EidoOnionClient.get(onion, "/head/$target")
            assertArrayEquals("голова вернулась изменённой", headBytes, head)
            println("ONION PASS head round-trips byte-for-byte over the circuit (${headBytes.size} B)")

            // The segment matters separately: it is two orders of magnitude larger, so it spans many
            // Tor cells and is the first thing here that could be truncated rather than corrupted.
            val segment = EidoOnionClient.get(onion, "/segment/$infoHash/segment-manifest.json")
            assertArrayEquals("сегмент вернулся изменённым", segmentBytes, segment)
            println("ONION PASS segment round-trips byte-for-byte (${segmentBytes.size} B)")

            assertNull("несуществующая голова не должна отдаваться", EidoOnionClient.get(onion, "/head/${hex(20)}"))
            assertTrue("промах должен быть 404, а не обрыв: ${EidoOnionClient.lastError}",
                EidoOnionClient.lastError!!.contains("404"))
            println("ONION PASS unknown head is a 404, not a dropped connection")

            // Path traversal: the store owns two directories and must not read outside them, and the
            // request is trivially constructed by anyone holding the address — which is public by
            // design. The decoy is a file this test plants one level up, so an unguarded store would
            // genuinely serve it: without it the assertion would pass on the `.bin` suffix alone and
            // prove nothing about the name check. It holds nothing secret, so the mutation that
            // removes the guard cannot leak anything over a live circuit.
            val decoy = java.io.File(ctx.filesDir, "onion/decoy.bin")
            decoy.writeBytes("decoy".toByteArray())
            assertNull("выход за каталог", EidoOnionClient.get(onion, "/head/../decoy"))
            assertTrue("отказ должен быть 404: ${EidoOnionClient.lastError}",
                EidoOnionClient.lastError!!.contains("404"))
            assertNull("выход за каталог", EidoOnionClient.get(onion, "/segment/../decoy/x"))

            // And the traversal string genuinely resolves: `head/../decoy.bin` *is* the planted file.
            // Without this the 404 above could just as well mean the filesystem found nothing, and
            // the whole check would be vacuous — the name check is the only thing refusing.
            val traversed = java.io.File(java.io.File(ctx.filesDir, "onion/head"), "../decoy.bin")
            assertTrue("обход не резолвится — проверка была бы пустой",
                traversed.isFile && traversed.canonicalPath == decoy.canonicalPath)
            println("ONION PASS path traversal refused with 404 while the target demonstrably exists")

            // The avatar route: served to anyone with the address, and refused unless it really is a
            // PNG. It arrives from a stranger's device and goes straight to an image decoder.
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(64)
            store.putAvatar(png)
            val user = "avatar-probe"
            assertTrue("аватар не отдан", org.eidolang.feature.home.OnionDelivery.fetchAvatar(ctx, user, onion))
            assertArrayEquals(png, org.eidolang.feature.home.ContactAvatarStore(ctx).get(user))

            store.putAvatar("not a png at all".toByteArray())
            assertFalse(
                "принято не-PNG",
                org.eidolang.feature.home.OnionDelivery.fetchAvatar(ctx, "$user-bad", onion),
            )
            println("ONION PASS avatar travels over the circuit and non-PNG is refused")
            java.io.File(ctx.filesDir, "contact-avatars/$user.bin").delete()

            assertTrue("сервер не считает запросы", server.served >= 3)
            assertEquals("CONNECTED", tor.state)
        } finally {
            server.stop()
        }
    }

    private fun hex(bytes: Int): String = ByteArray(bytes)
        .also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
}
