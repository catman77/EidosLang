package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.archive.SegmentParser
import org.eidolang.core.archive.ArchivePublisherParser
import org.eidolang.core.transport.FetchResult
import org.eidolang.core.transport.MutableHeadRecord
import org.eidolang.core.transport.NetworkAdmissionGate
import org.eidolang.core.transport.SwarmObject
import org.eidolang.core.transport.TransportFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.security.MessageDigest

/**
 * R17.1 items 2-5 and 7 on the Android device itself.
 *
 * `R17Main` proves the same admission over `ReferenceTransportNetwork`, which its own comment
 * calls "NOT BitTorrent". This runs two independent libtorrent sessions inside the app process:
 * A seeds the fixed R16 golden segment, B starts from the v2 magnet alone and pulls metadata
 * (BEP9) and data (BEP52) over a real connection, and the bytes B wrote to disk are then fed to
 * the production `NetworkAdmissionGate`.
 *
 * The backend is the prebuilt `org.libtorrent4j` binding of libtorrent 2.1.0, wired as a
 * *test-only* dependency. It is not the project's own `native-torrent` JNI and does not discharge
 * `ENABLE_NATIVE_LIBTORRENT.md`; it establishes that the R16 wire format survives a real
 * BitTorrent v2 stack on real hardware.
 *
 * Strictly loopback: DHT, LSD, UPnP/NAT-PMP and trackers are off and B is pointed at A with an
 * explicit peer, so nothing leaves the device.
 */
@NativeSessionHeavy
class LibtorrentOnDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets get() = InstrumentationRegistry.getInstrumentation().context.assets

    private val portA = 6881
    private val portB = 6882
    private val deadlineMs = 120_000L

    private fun asset(path: String): ByteArray = assets.open("golden-r16/$path").use { it.readBytes() }

    private fun assetTree(dir: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(p: String) {
            val kids = assets.list("golden-r16/$p") ?: emptyArray()
            if (kids.isEmpty()) out += p else kids.forEach { walk(if (p.isEmpty()) it else "$p/$it") }
        }
        walk(dir)
        return out.sorted()
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun ids(): Map<String, String> =
        asset("ids.txt").toString(Charsets.UTF_8).lineSequence()
            .filter { "=" in it }
            .associate { val p = it.split("=", limit = 2); p[0].trim() to p[1].trim() }

    private fun session(port: Int): SessionManager {
        val sp = SettingsPack()
            .setString(settings_pack.string_types.listen_interfaces.swigValue(), "127.0.0.1:$port")
            .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), false)
            .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false)
            .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
        val s = SessionManager()
        s.start(SessionParams(sp))
        return s
    }

    private fun waitFor(what: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (predicate()) return
            Thread.sleep(200)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun r17RealSwarmTransferAndAdmissionOnDevice() {
        val expected = ids()
        val info = TorrentInfo(asset("segment.torrent"))
        val name = info.name()

        // libtorrent must independently agree with the R16 builder on the v2 infohash.
        val v2 = info.infoHashes().swig().v2.to_hex()
        assertEquals("v2 infohash disagreement", expected.getValue("torrent_infohash_v2"), v2)
        println("SWARM PASS libtorrent recomputes the exact R16 v2 infohash (on device)")

        val root = File(ctx.filesDir, "r17-swarm").apply { deleteRecursively(); mkdirs() }
        val seedRoot = File(root, "a").apply { mkdirs() }
        val leechRoot = File(root, "b").apply { mkdirs() }

        val relative = assetTree("segment")
        val original = LinkedHashMap<String, String>()
        relative.forEach { rel ->
            val bytes = asset(rel)
            val target = File(seedRoot, "$name/${rel.removePrefix("segment/")}")
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            original[rel.removePrefix("segment/")] = sha256(bytes)
        }
        assertEquals(6, original.size)

        val a = session(portA)
        val b = session(portB)
        try {
            a.download(info, seedRoot, null, null, null, TorrentFlags.SEED_MODE)
            waitFor("A to seed") { a.find(info.infoHash())?.status()?.isSeeding == true }
            println("SWARM PASS session A seeds the fixed golden segment")

            val magnet = asset("magnet.txt").toString(Charsets.UTF_8).trim()
            b.download(magnet, leechRoot, torrent_flags_t())
            waitFor("B to register the magnet") { b.find(info.infoHash()) != null }
            b.find(info.infoHash()).swig().connect_peer(TcpEndpoint("127.0.0.1", portA).swig())

            waitFor("B to pull metadata over BEP9") { b.find(info.infoHash())?.status()?.hasMetadata() == true }
            println("SWARM PASS session B obtains metadata from the swarm via magnet only")

            waitFor("B to complete the transfer") { b.find(info.infoHash())?.status()?.isSeeding == true }
            // Guard against a false pass: the payload must have arrived from a peer, not from
            // files that were somehow already on disk.
            val st = b.find(info.infoHash()).status()
            assertTrue(
                "B reported completion without downloading payload (${st.totalPayloadDownload()} bytes)",
                st.totalPayloadDownload() > 0,
            )
            println("SWARM PASS session B completes the transfer over a real BitTorrent connection " +
                "(${st.totalPayloadDownload()} payload bytes from ${st.numPeers()} peer(s))")
        } finally {
            runCatching { a.stop() }
            runCatching { b.stop() }
        }

        val downloadedRoot = File(leechRoot, name)
        val got = LinkedHashMap<String, String>()
        downloadedRoot.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
            got[it.relativeTo(downloadedRoot).path] = sha256(it.readBytes())
        }
        assertEquals("transferred tree differs from the seeded original", original, got)
        println("SWARM PASS every transferred file is byte-identical to the seeded original")

        // Admission over what libtorrent actually wrote to this device's storage.
        fun transferred(): List<TransportFile> =
            downloadedRoot.walkTopDown().filter { it.isFile }
                .map { TransportFile(it.relativeTo(downloadedRoot).path.replace('\\', '/'), it.readBytes()) }
                .toList().sortedBy { it.path }

        fun swarmOf(files: List<TransportFile>) = SwarmObject(
            torrentName = name,
            torrentMetainfo = asset("segment.torrent"),
            infoHashV2Hex = expected.getValue("torrent_infohash_v2"),
            files = files,
        )

        val head = MutableHeadRecord(expected.getValue("bep44_target"), asset("head-put.bencode"))
        val publisher = asset("publisher-certificate.json").toString(Charsets.UTF_8)
        val manifest = SegmentParser.parseCanonical(
            transferred().single { it.path == "segment-manifest.json" }.bytes.toString(Charsets.UTF_8)
        )
        val senderDevice = manifest.identityEntries
            .first { it.deviceId == ArchivePublisherParser.parseCanonical(publisher).deviceId }
        val identity = transferred().single { it.path == senderDevice.path }.bytes.toString(Charsets.UTF_8)

        val accepted = NetworkAdmissionGate.admit(
            FetchResult(swarmOf(transferred()), "libtorrent4j:on-device"), head, publisher, identity,
        )
        assertEquals(expected.getValue("segment_id"), accepted.artifact.manifest.segmentId)
        println("SWARM PASS transferred bytes reproduce the exact R16 segment_id under admission")

        // Item 7: corrupt a downloaded file after libtorrent reported completion.
        val victim = File(downloadedRoot, manifest.messageEntries.first().path)
        val pristine = victim.readBytes()
        victim.writeBytes(pristine.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() })
        try {
            val rejected = runCatching {
                NetworkAdmissionGate.admit(
                    FetchResult(swarmOf(transferred()), "libtorrent4j:on-device"), head, publisher, identity,
                )
            }
            assertTrue("admission accepted a post-download corruption", rejected.isFailure)
            println("SWARM PASS post-completion corruption is rejected on device")
        } finally {
            victim.writeBytes(pristine)
        }

        val reaccepted = NetworkAdmissionGate.admit(
            FetchResult(swarmOf(transferred()), "libtorrent4j:on-device"), head, publisher, identity,
        )
        assertEquals(expected.getValue("segment_id"), reaccepted.artifact.manifest.segmentId)
        println("SWARM PASS restoring the byte restores admission")
        println("SWARM SEGMENT_ID=${accepted.artifact.manifest.segmentId}")
    }
}
