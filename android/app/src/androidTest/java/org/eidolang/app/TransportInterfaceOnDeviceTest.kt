package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.archive.ArchivePublisherCanonical
import org.eidolang.core.archive.ArchivePublisherParser
import org.eidolang.core.archive.ConversationHeadCodec
import org.eidolang.core.archive.ConversationHeadValueV1
import org.eidolang.core.archive.Ed25519Raw
import org.eidolang.core.archive.JcaArchivePublisher
import org.eidolang.core.archive.SegmentParser
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.transport.NetworkAdmissionGate
import org.eidolang.core.transport.SwarmObject
import org.eidolang.core.transport.TransportFile
import org.eidolang.transport.libtorrent4j.AndroidArchivePublisherStore
import org.eidolang.transport.libtorrent4j.Libtorrent4jTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R22.1: the product transport interface over a real BitTorrent stack, on the device.
 *
 * Everything earlier drove libtorrent directly and handed bytes to `NetworkAdmissionGate` by
 * hand. This goes through `TorrentTransport` — the interface the app is wired to — so a pass here
 * means the product path works, not just the library.
 */
@NativeSessionHeavy
class TransportInterfaceOnDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun asset(p: String) = assets.open("golden-r16/$p").use { it.readBytes() }

    private fun ids(): Map<String, String> =
        asset("ids.txt").toString(Charsets.UTF_8).lineSequence().filter { "=" in it }
            .associate { val x = it.split("=", limit = 2); x[0].trim() to x[1].trim() }

    private fun assetFiles(): List<TransportFile> {
        val out = mutableListOf<TransportFile>()
        fun walk(p: String) {
            val kids = assets.list("golden-r16/$p") ?: emptyArray()
            if (kids.isEmpty()) out += TransportFile(p.removePrefix("segment/"), asset(p))
            else kids.forEach { walk(if (p.isEmpty()) it else "$p/$it") }
        }
        walk("segment")
        return out.sortedBy { it.path }
    }

    private fun dir(name: String) = File(ctx.filesDir, name).apply { deleteRecursively(); mkdirs() }

    @Test
    fun r22SegmentTravelsThroughTorrentTransportAndPassesAdmission() {
        val expected = ids()
        val swarm = SwarmObject(
            torrentName = "eidolang-${expected.getValue("conversation_id").take(12)}-" +
                expected.getValue("segment_id").take(12),
            torrentMetainfo = asset("segment.torrent"),
            infoHashV2Hex = expected.getValue("torrent_infohash_v2"),
            files = assetFiles(),
        )
        assertEquals(6, swarm.files.size)

        val a = Libtorrent4jTransport(dir("tx-a"), listenPort = 6891, localDhtTuning = true)
        val b = Libtorrent4jTransport(dir("tx-b"), listenPort = 6892, localDhtTuning = true)
        try {
            a.seed(swarm)
            println("TX PASS seed() published the segment through TorrentTransport")

            // Kick off the fetch, then point B at A: loopback has no discovery.
            Thread { b.fetch(swarm.infoHashV2Hex) }.apply { isDaemon = true }.start()
            Thread.sleep(1500)
            b.connectPeer(swarm.infoHashV2Hex, "127.0.0.1", 6891)

            val fetched = b.fetch(swarm.infoHashV2Hex)
            assertNotNull("fetch() returned nothing", fetched)
            assertEquals(6, fetched!!.swarm.files.size)
            println("TX PASS fetch() returned the segment from a real swarm (${fetched.source})")

            val head = org.eidolang.core.transport.MutableHeadRecord(
                expected.getValue("bep44_target"), asset("head-put.bencode"),
            )
            val publisher = asset("publisher-certificate.json").toString(Charsets.UTF_8)
            val manifest = SegmentParser.parseCanonical(
                fetched.swarm.files.single { it.path == "segment-manifest.json" }
                    .bytes.toString(Charsets.UTF_8)
            )
            val sender = manifest.identityEntries
                .first { it.deviceId == ArchivePublisherParser.parseCanonical(publisher).deviceId }
            val identity = fetched.swarm.files.single { it.path == sender.path }
                .bytes.toString(Charsets.UTF_8)

            val accepted = NetworkAdmissionGate.admit(fetched, head, publisher, identity)
            assertEquals(expected.getValue("segment_id"), accepted.artifact.manifest.segmentId)
            println("TX PASS admission over transport output reproduces the exact R16 segment_id")
            println("TX SEGMENT_ID=${accepted.artifact.manifest.segmentId}")
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun r22HeadIsPublishedAndResolvedThroughTorrentTransport() {
        val expected = ids()
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()

        // A real publisher of our own: the golden head is signed by a key we do not hold.
        val publisher = JcaArchivePublisher.create(identity)
        val seed = publisher.seed
        val pub = publisher.publicKeyRaw

        // Restoring from stored key material must yield the same DHT identity, or heads could
        // never be updated across process restarts.
        val restored = JcaArchivePublisher.restore(identity, seed)
        assertEquals(
            publisher.certificate.publisherKeyId,
            restored.certificate.publisherKeyId,
        )
        println("TX PASS publisher restored from stored seed keeps the same publisher_key_id")

        val value = ConversationHeadValueV1(
            conversationId = expected.getValue("conversation_id"),
            segmentId = expected.getValue("segment_id"),
            torrentInfoHashV2 = expected.getValue("torrent_infohash_v2"),
            headMessageIds = listOf(expected.getValue("head_message_id")),
            publisherKeyId = restored.certificate.publisherKeyId,
            publisherDeviceId = restored.certificate.deviceId,
        )
        val head = ConversationHeadCodec.create(value, seq = 1, publisher = restored)
        val record = org.eidolang.core.transport.MutableHeadRecord(
            head.targetSha1Hex,
            org.eidolang.core.archive.Bencode.encode(ConversationHeadCodec.mutablePutFields(head)),
        )

        // A loopback DHT needs a handful of nodes before anything will store.
        val nodes = (0 until 8).map {
            Libtorrent4jTransport(
                dir("dht-$it"), listenPort = 6900 + it, localDhtTuning = true,
                publisherSeed = seed, publisherPublicKey = pub,
            )
        }
        try {
            nodes.forEachIndexed { i, n ->
                for (j in nodes.indices) if (i != j) n.addDhtNode("127.0.0.1", 6900 + j)
            }
            val end = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < end && nodes[0].dhtNodes() < 2) Thread.sleep(500)
            assertTrue("private DHT never formed (${nodes[0].dhtNodes()} nodes)", nodes[0].dhtNodes() >= 2)
            println("TX PASS private DHT formed (${nodes[0].dhtNodes()} nodes)")

            assertTrue("putHead() reported failure", nodes[0].putHead(record))
            println("TX PASS putHead() published the head through TorrentTransport")

            val reader = nodes[7]
            reader.knowHead(head.publicKeyRaw, head.salt)
            val got = reader.getHead(head.targetSha1Hex)
            assertNotNull("getHead() returned nothing", got)
            assertEquals(head.targetSha1Hex, got!!.targetSha1Hex)

            val parsed = ConversationHeadCodec.parseMutablePutFields(got.fieldsBencoded)
            assertTrue(
                "retrieved head fails the project's own signature check",
                ConversationHeadCodec.verify(parsed, restored.certificate, publicBundle(identity)),
            )
            assertEquals(head.seq, parsed.seq)
            val decoded = ConversationHeadCodec.decodeValue(parsed.value)
            assertEquals(expected.getValue("segment_id"), decoded.segmentId)
            assertEquals(expected.getValue("torrent_infohash_v2"), decoded.torrentInfoHashV2)
            println("TX PASS getHead() returned a head that verifies under the project's own rules")
            println("TX HEAD_TARGET=${head.targetSha1Hex}")
        } finally {
            nodes.forEach { it.close() }
        }
    }

    private fun publicBundle(identity: org.eidolang.core.crypto.DevicePrivateCrypto) =
        org.eidolang.core.crypto.PublicIdentityBundleV1(identity.user, identity.certificate)

    @Test
    fun r22PublisherKeySurvivesRestartSoTheDhtTargetIsStable() {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val store = AndroidArchivePublisherStore(ctx)
        store.delete()

        val first = store.ensure(identity)
        assertTrue("nothing was persisted", store.exists())

        // A second store instance stands in for a fresh process.
        val second = AndroidArchivePublisherStore(ctx).loadIfPresent(identity)
        assertNotNull("publisher did not survive a restart", second)
        assertEquals(first.certificate.publisherKeyId, second!!.certificate.publisherKeyId)
        assertEquals(first.publicKeyRaw.toList(), second.publicKeyRaw.toList())

        // The stored blob must not be readable as plaintext key material.
        val raw = java.io.File(ctx.filesDir, "eidolang-archive-publisher-v1.bin").readText()
        assertTrue("publisher key is not sealed", raw.startsWith("v1."))
        assertTrue(
            "seed appears verbatim in the stored blob",
            !raw.contains(org.eidolang.core.crypto.B64Url.encode(first.seed)),
        )
        println("TX PASS publisher key persists sealed and keeps publisher_key_id ${first.certificate.publisherKeyId.take(20)}…")
        store.delete()
    }
}
