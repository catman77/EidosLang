package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.feature.home.EidoOnionClient
import org.eidolang.feature.home.EidoOnionNode
import org.eidolang.feature.home.EidoOnionServer
import org.eidolang.feature.home.EidoTor
import org.eidolang.feature.home.OnionStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Is what this device published actually reachable from outside?
 *
 * Splits the two halves of "the sender publishes and the recipient never receives". The bytes are
 * on disk either way, so the only question that matters is whether a stranger's circuit can pull
 * them — and asking this device's own address answers it: the request leaves through Tor, finds the
 * descriptor and comes back in, exactly as the other phone's would.
 *
 * A pass here moves the fault to the recipient. A failure means the sender was never serving, which
 * no amount of pressing "Обновить" on the other device would have fixed.
 */
@NativeSessionHeavy
class SelfServeHeadTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theHeadThisDevicePublishedCanBeFetchedOverItsOwnCircuit() {
        val heads = File(ctx.filesDir, "onion/head").listFiles()?.filter { it.name.endsWith(".bin") }.orEmpty()
        Assume.assumeTrue("это устройство ничего не публиковало", heads.isNotEmpty())
        val head = heads.maxByOrNull { it.lastModified() }!!
        val target = head.name.removeSuffix(".bin")
        println("SELF голова=$target (${head.length()} байт)")

        val store = OnionStore(ctx)
        EidoOnionServer(store).start()
        val onion = EidoTor.of(ctx).startAndPublish(derivedKey = EidoOnionNode.derivedKey(ctx))
        assertNotNull("Tor не поднялся: ${EidoTor.of(ctx).attempts}", onion)
        println("SELF адрес=$onion")

        val got = EidoOnionClient.getRetrying(onion!!, "/head/$target", attempts = 30) {
            println("SELF попытка $it")
        }
        assertNotNull("своя же голова не отдаётся наружу: ${EidoOnionClient.lastError}", got)
        assertArrayEquals("голова вернулась изменённой", head.readBytes(), got)
        println("SELF PASS устройство отдаёт опубликованную голову через свою цепочку")
    }
}
