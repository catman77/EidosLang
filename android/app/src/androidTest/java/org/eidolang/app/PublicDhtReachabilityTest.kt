package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.transport.libtorrent4j.Libtorrent4jTransport
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Can this device actually use the public BitTorrent DHT?
 *
 * Delivery depends on it: the recipient finds a message by resolving a BEP44 head, and without a
 * routing table there is nothing to resolve against. This measures reachability rather than
 * assuming it — a phone behind a carrier NAT may never build one.
 */
@NativeSessionHeavy
class PublicDhtReachabilityTest {

    @Test
    fun deviceJoinsThePublicDht() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val transport = Libtorrent4jTransport(
            storageRoot = File(ctx.filesDir, "dht-probe").apply { mkdirs() },
            listenPort = 0,
            bootstrapNodes = "dht.libtorrent.org:25401,router.bittorrent.com:6881,dht.transmissionbt.com:6881",
            listenInterface = "0.0.0.0",
        )
        try {
            val end = System.currentTimeMillis() + 60_000
            var nodes = 0L
            while (System.currentTimeMillis() < end) {
                nodes = transport.dhtNodes()
                if (nodes >= 10) break
                Thread.sleep(1000)
            }
            println("PUBDHT routing table nodes=$nodes")
            assertTrue("device never joined the public DHT (nodes=$nodes)", nodes > 0)
        } finally {
            transport.close()
        }
    }
}
