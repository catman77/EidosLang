package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.archive.Bencode
import org.eidolang.core.archive.ConversationHeadCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Entry
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.DhtPutAlert
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.string_int_pair
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * R17.1 item 6 on the Android device: the R16 conversation head carried through a real BEP44
 * mutable put/get.
 *
 * Scope, stated up front: the golden artifacts hold the publisher's Ed25519 *public* key and
 * signature but not the private key, so the project's own signed item cannot be republished. The
 * value published here is `ConversationHeadCodec`'s own canonical encoding of the golden head,
 * under a freshly generated key. What this establishes is that a real BEP44 implementation on
 * Android accepts that structure, stores it, and returns it byte-identical, and that
 * `target = SHA1(pk || salt)` addresses it. `tools/python/verify_r16_bep44.py` covers the
 * project's signature offline.
 *
 * A loopback DHT does not form on its own: libtorrent's anti-Sybil defaults keep every 127.0.0.1
 * node out of the routing table, and two nodes never finish bootstrapping. Hence a small local
 * swarm with those restrictions lifted. Public bootstrap nodes stay empty; nothing leaves the
 * device.
 */
@NativeSessionHeavy
class DhtOnDeviceTest {

    private val nodes = 8
    private val basePort = 6900
    private val deadlineMs = 120_000L

    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun asset(path: String): ByteArray = assets.open("golden-r16/$path").use { it.readBytes() }

    private fun ids(): Map<String, String> =
        asset("ids.txt").toString(Charsets.UTF_8).lineSequence()
            .filter { "=" in it }
            .associate { val p = it.split("=", limit = 2); p[0].trim() to p[1].trim() }

    private fun session(port: Int): SessionManager {
        val sp = SettingsPack()
            .setString(settings_pack.string_types.listen_interfaces.swigValue(), "127.0.0.1:$port")
            .setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), "")
            .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false)
            .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
            // Local-only swarm: without these the routing table stays empty on loopback, and
            // BEP42 node-id enforcement makes every store fail with "invalid signature".
            .setBoolean(settings_pack.bool_types.dht_restrict_routing_ips.swigValue(), false)
            .setBoolean(settings_pack.bool_types.dht_restrict_search_ips.swigValue(), false)
            .setBoolean(settings_pack.bool_types.dht_ignore_dark_internet.swigValue(), false)
            .setBoolean(settings_pack.bool_types.dht_enforce_node_id.swigValue(), false)
        val s = SessionManager()
        s.start(SessionParams(sp))
        return s
    }

    private fun waitFor(what: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (predicate()) return
            Thread.sleep(500)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** libtorrent's bundled orlp/ed25519 wants the clamped SHA-512 expansion, not `seed || pub`. */
    private fun expandedSecret(seed: ByteArray): ByteArray {
        val h = MessageDigest.getInstance("SHA-512").digest(seed)
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = (h[31].toInt() and 63).toByte()
        h[31] = (h[31].toInt() or 64).toByte()
        return h
    }

    @Test
    fun r17Bep44MutableHeadRoundTripOnDevice() {
        val expected = ids()
        val head = ConversationHeadCodec.parseMutablePutFields(asset("head-put.bencode"))
        val valueBytes = Bencode.encode(head.value)

        // The project addresses heads as SHA1(dht_public_key || salt).
        val target = MessageDigest.getInstance("SHA-1")
            .digest(head.publicKeyRaw + head.salt)
            .joinToString("") { "%02x".format(it) }
        assertEquals("target formula disagreement", expected.getValue("bep44_target"), target)
        println("DHT PASS target formula SHA1(k||salt) reproduces the golden bep44_target")

        val swarm = (0 until nodes).map { session(basePort + it) }
        try {
            swarm.forEachIndexed { i, s ->
                for (j in 0 until nodes) {
                    if (i != j) s.swig().add_dht_node(string_int_pair("127.0.0.1", basePort + j))
                }
            }
            waitFor("the private DHT to form a routing table") { swarm[0].dhtNodes() >= 2 }
            println("DHT PASS private $nodes-node DHT formed a routing table (${swarm[0].dhtNodes()} nodes, no public bootstrap)")

            // Not KeyPairGenerator("Ed25519"): Android has no Ed25519 KeyPairGenerator below
            // API 35, so this ran on the phone and would have failed on an API 34 emulator.
            val seed = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val pub = org.eidolang.core.archive.Ed25519Raw.publicFromSeed(seed)

            val publisher = swarm[0]
            val successes = AtomicInteger(-1)
            publisher.addListener(object : AlertListener {
                override fun types(): IntArray = intArrayOf(AlertType.DHT_PUT.swig())
                override fun alert(a: Alert<*>) {
                    if (a is DhtPutAlert) successes.set(a.swig().num_success)
                }
            })

            publisher.dhtPutItem(pub, expandedSecret(seed), Entry.bdecode(valueBytes), head.salt)
            waitFor("the put to be acknowledged") { successes.get() >= 0 }
            assertTrue(
                "put reached 0 nodes; nothing was actually stored",
                successes.get() > 0,
            )
            println("DHT PASS head value published as a real BEP44 mutable item to ${successes.get()} node(s)")

            val retriever = swarm[nodes - 1]
            val got = retriever.dhtGetItem(pub, head.salt, 30)
            assertNotNull("no mutable item returned; a miss looks the same as a hit here", got)
            assertNotNull("DHT miss: the item came back empty", got.item)
            assertTrue("sequence regressed: ${got.seq}", got.seq >= head.seq)
            println("DHT PASS a different node retrieved the item from the DHT")

            assertTrue(
                "head value changed in transit",
                got.item.bencode().contentEquals(valueBytes),
            )
            println("DHT PASS retrieved head value is byte-identical (seq=${got.seq}, ${valueBytes.size} bytes)")

            assertTrue("value exceeds the BEP44 limit", valueBytes.size <= 1000)
            println("DHT PASS stored value stays within the BEP44 1000-byte limit")
            println("DHT TARGET_GOLDEN=${expected.getValue("bep44_target")}")
        } finally {
            swarm.forEach { runCatching { it.stop() } }
        }
    }
}
