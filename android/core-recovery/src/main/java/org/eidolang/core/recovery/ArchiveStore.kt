package org.eidolang.core.recovery


data class StoredArchiveSegment(
    val blob: ArchiveSegmentBlobV1,
    val pinned: Boolean,
    val storedAtMs: Long,
)

sealed interface PutArchiveResult {
    data object Inserted : PutArchiveResult
    data object Duplicate : PutArchiveResult
}

interface ArchiveStore {
    fun put(blob: ArchiveSegmentBlobV1, pinned: Boolean, storedAtMs: Long): PutArchiveResult
    fun get(segmentId: String): StoredArchiveSegment?
    fun list(conversationId: String? = null): List<StoredArchiveSegment>
    fun setPinned(segmentId: String, pinned: Boolean)
    fun remove(segmentId: String): Boolean
}

class InMemoryArchiveStore : ArchiveStore {
    private val rows = linkedMapOf<String, StoredArchiveSegment>()

    override fun put(blob: ArchiveSegmentBlobV1, pinned: Boolean, storedAtMs: Long): PutArchiveResult {
        val canonical = ArchiveSegmentCodec.json(blob)
        val old = rows[blob.segmentId]
        if (old != null) {
            require(ArchiveSegmentCodec.json(old.blob) == canonical) { "Conflicting bytes for same segment_id" }
            rows[blob.segmentId] = old.copy(pinned = old.pinned || pinned)
            return PutArchiveResult.Duplicate
        }
        rows[blob.segmentId] = StoredArchiveSegment(blob, pinned, storedAtMs)
        return PutArchiveResult.Inserted
    }

    override fun get(segmentId: String): StoredArchiveSegment? = rows[segmentId]

    override fun list(conversationId: String?): List<StoredArchiveSegment> =
        rows.values.filter { conversationId == null || it.blob.conversationId == conversationId }
            .sortedWith(compareBy<StoredArchiveSegment> { it.blob.conversationId }.thenBy { it.blob.segmentId })

    override fun setPinned(segmentId: String, pinned: Boolean) {
        val old = rows[segmentId] ?: error("Unknown segment $segmentId")
        rows[segmentId] = old.copy(pinned = pinned)
    }

    override fun remove(segmentId: String): Boolean = rows.remove(segmentId) != null
}
