package org.eidolang.core.recovery

import android.content.Context
import java.io.File

/**
 * Persistent local archive storage. Segment blobs are immutable canonical JSON files.
 * Pin/timestamp metadata is deliberately local and lives in a small side index; it is not
 * part of R16 transport identity.
 */
class AndroidFileArchiveStore(context: Context) : ArchiveStore {
    private val root = context.filesDir.resolve("eidolang-archive-r19")
    private val segmentsDir = root.resolve("segments")
    private val indexFile = root.resolve("index.tsv")

    init {
        segmentsDir.mkdirs()
        root.mkdirs()
    }

    override fun put(blob: ArchiveSegmentBlobV1, pinned: Boolean, storedAtMs: Long): PutArchiveResult {
        ArchiveSegmentCodec.toArtifact(blob)
        val file = segmentsDir.resolve("${blob.segmentId}.json")
        val canonical = ArchiveSegmentCodec.json(blob)
        val meta = readIndex().toMutableMap()
        if (file.exists()) {
            require(file.readText(Charsets.UTF_8) == canonical) { "Conflicting bytes for same segment_id" }
            val old = meta[blob.segmentId] ?: LocalMeta(false, file.lastModified())
            meta[blob.segmentId] = old.copy(pinned = old.pinned || pinned)
            writeIndex(meta)
            return PutArchiveResult.Duplicate
        }
        atomicWrite(file, canonical)
        meta[blob.segmentId] = LocalMeta(pinned, storedAtMs)
        writeIndex(meta)
        return PutArchiveResult.Inserted
    }

    override fun get(segmentId: String): StoredArchiveSegment? {
        val file = segmentsDir.resolve("$segmentId.json")
        if (!file.exists()) return null
        val blob = ArchiveSegmentCodec.parseCanonical(file.readText(Charsets.UTF_8))
        val meta = readIndex()[segmentId] ?: LocalMeta(false, file.lastModified())
        return StoredArchiveSegment(blob, meta.pinned, meta.storedAtMs)
    }

    override fun list(conversationId: String?): List<StoredArchiveSegment> =
        segmentsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { get(it.name.removeSuffix(".json")) }
            .filter { conversationId == null || it.blob.conversationId == conversationId }
            .sortedWith(compareBy<StoredArchiveSegment> { it.blob.conversationId }.thenBy { it.blob.segmentId })

    override fun setPinned(segmentId: String, pinned: Boolean) {
        require(segmentsDir.resolve("$segmentId.json").exists()) { "Unknown segment $segmentId" }
        val meta = readIndex().toMutableMap()
        val old = meta[segmentId] ?: LocalMeta(false, System.currentTimeMillis())
        meta[segmentId] = old.copy(pinned = pinned)
        writeIndex(meta)
    }

    override fun remove(segmentId: String): Boolean {
        val file = segmentsDir.resolve("$segmentId.json")
        val removed = file.delete()
        if (removed) {
            val meta = readIndex().toMutableMap()
            meta.remove(segmentId)
            writeIndex(meta)
        }
        return removed
    }

    private data class LocalMeta(val pinned: Boolean, val storedAtMs: Long)

    private fun readIndex(): Map<String, LocalMeta> {
        if (!indexFile.exists()) return emptyMap()
        return indexFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.associate { line ->
            val p = line.split('\t')
            require(p.size == 3)
            p[0] to LocalMeta(p[1] == "1", p[2].toLong())
        }
    }

    private fun writeIndex(meta: Map<String, LocalMeta>) {
        val text = meta.toSortedMap().entries.joinToString("\n", postfix = if (meta.isEmpty()) "" else "\n") { (id, m) ->
            "$id\t${if (m.pinned) 1 else 0}\t${m.storedAtMs}"
        }
        atomicWrite(indexFile, text)
    }

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }
}
