package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.transport.libtorrent4j.Libtorrent4jTransport
import org.junit.Test
import java.io.File

/**
 * Can a peer on the internet open a connection *to* this device?
 *
 * Resolving a head only tells the recipient where the data is. Pulling it needs an inbound path,
 * and on a home Wi-Fi the phone still sits behind the router's NAT. libtorrent reports what it
 * observes about its own reachability; this records it instead of guessing.
 */
@NativeSessionHeavy
class InboundReachabilityTest {

    private fun probe(label: String, portMapping: Boolean) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val t = Libtorrent4jTransport(
            storageRoot = File(ctx.filesDir, "reach-$label").apply { mkdirs() },
            listenPort = 0,
            bootstrapNodes = "dht.libtorrent.org:25401,router.bittorrent.com:6881,dht.transmissionbt.com:6881",
            listenInterface = "0.0.0.0",
            enablePortMapping = portMapping,
        )
        try {
            val end = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < end && t.dhtNodes() < 10) Thread.sleep(1000)
            Thread.sleep(15_000)   // give UPnP/NAT-PMP a chance to answer
            println("REACH[$label] portMapping=$portMapping nodes=${t.dhtNodes()}")
            println("REACH[$label] listen=${t.listenEndpoints()}")
            println("REACH[$label] externalAddress=${t.externalAddress()}")
            println("REACH[$label] firewalled=${t.isFirewalled()}")
        } finally {
            t.close()
        }
    }

    @Test fun withoutPortMapping() = probe("nomap", false)
    @Test fun withPortMapping() = probe("map", true)
}
