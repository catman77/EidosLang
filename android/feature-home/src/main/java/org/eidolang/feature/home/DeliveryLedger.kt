package org.eidolang.feature.home

import android.content.Context
import org.eidolang.core.canonical.CanonicalJson
import org.eidolang.core.canonical.JValue
import org.eidolang.core.canonical.StrictJsonParser
import org.eidolang.core.hardening.AndroidLocalSecretBox
import java.io.File

/**
 * Which sent eidograms have actually been collected, and which are still waiting.
 *
 * Once the relay was removed, "отправлено" stopped meaning anything a person could act on. A send
 * writes the segment into this device's own onion store and returns immediately — it cannot fail for
 * want of a network, because no network is involved. What can take hours is the other half: the
 * recipient's phone has to be awake, online, and pointed at this address before a single byte moves.
 * The old wording hid exactly the part that varies.
 *
 * Nothing new goes on the wire to measure it. The onion server already sees every request it
 * answers, and a segment is collected when all of its files have gone out. That is an observation of
 * work this device did anyway, not a receipt protocol — which is why it needs no counterexample
 * under the R22 rule, and also why its claim is narrow:
 *
 *  - Two ticks mean **the bytes left this device**. They do not mean the recipient decrypted them,
 *    or opened the app, or is a person rather than a script.
 *  - The fetcher is not authenticated. The onion address is on the contact card and the head is
 *    published to the public DHT, so anyone holding a card could pull the segment and cause a tick.
 *    They would get ciphertext — the per-recipient key wrap is unaffected — but they would make the
 *    sender believe in a delivery that never reached the addressee. Tightening this needs a
 *    challenge over the circuit, i.e. a new wire interaction, and no counterexample yet forces one.
 *
 * Sealed like every other local file: it records who this device talks to and when, which is the
 * kind of thing a lifted app directory should not give up.
 */
class DeliveryLedger(private val context: Context) {

    /**
     * One published segment.
     *
     * [publishedAtMs] rather than a per-message list, because a segment carries the *whole*
     * conversation up to the moment it was built. Collecting it therefore delivers every message
     * older than that instant at once, so a single timestamp answers the question for all of them
     * and the ledger never has to be rewritten when a message is added.
     */
    data class Record(
        val infoHashV2Hex: String,
        val contactUserId: String,
        val publishedAtMs: Long,
        val fileCount: Int,
        val servedPaths: Set<String>,
        val collectedAtMs: Long,
        /** Whether the copy left in the relay's locker has since been removed. */
        val droppedFromLocker: Boolean = false,
    ) {
        val collected: Boolean get() = collectedAtMs > 0L
    }

    private val box = AndroidLocalSecretBox(context)
    private val dir get() = File(context.filesDir, "delivery-ledger").apply { mkdirs() }

    /** Note that a segment is now on offer to [contactUserId]. */
    fun published(infoHashV2Hex: String, contactUserId: String, fileCount: Int, atMs: Long) {
        if (!isHex(infoHashV2Hex)) return
        synchronized(LOCK) {
            // Re-publishing an unchanged conversation rebuilds byte-identical files and therefore
            // the same infohash. Keeping the existing record matters: resetting it would drop a
            // collection that already happened and turn two ticks back into one.
            val existing = read(infoHashV2Hex)
            write(
                existing?.copy(fileCount = fileCount)
                    ?: Record(infoHashV2Hex, contactUserId, atMs, fileCount, emptySet(), 0L)
            )
        }
    }

    /**
     * One file of one segment has been handed out.
     *
     * Collected only when every file has gone, never on the first. The recipient fetches the
     * manifest before anything else and may then fail — on a broken circuit, on the admission gate,
     * on a phone going to sleep — and a tick raised by that first request would say "delivered" for
     * a transfer that never completed.
     */
    fun served(infoHashV2Hex: String, path: String, atMs: Long) {
        if (!isHex(infoHashV2Hex)) return
        synchronized(LOCK) {
            val record = read(infoHashV2Hex) ?: return
            if (record.collected) return
            val paths = record.servedPaths + path
            val done = record.fileCount > 0 && paths.size >= record.fileCount
            write(record.copy(servedPaths = paths, collectedAtMs = if (done) atMs else 0L))
        }
    }

    /**
     * For each contact, the newest publication instant they have collected through.
     *
     * A message sent to that contact before this instant was inside the segment they took, so this
     * one number decides the ticks for the whole conversation.
     */
    /**
     * Record a collection the locker performed on this device's behalf.
     *
     * Separate from [served], which counts files this device handed out itself: there is nothing to
     * count here, only the locker's word that the whole segment went out. The claim is exactly as
     * narrow as the other one — bytes left, not that anybody read them — and it is the locker's
     * word rather than an observation, which is why it is written by its own method.
     */
    fun markCollectedByLocker(infoHashV2Hex: String) {
        synchronized(LOCK) {
            read(infoHashV2Hex)?.takeIf { !it.collected }?.let {
                write(it.copy(collectedAtMs = System.currentTimeMillis()))
            }
        }
    }

    fun markDropped(infoHashV2Hex: String) {
        synchronized(LOCK) {
            read(infoHashV2Hex)?.let { write(it.copy(droppedFromLocker = true)) }
        }
    }

    fun deliveredThroughByContact(): Map<String, Long> = all()
        .filter { it.collected }
        .groupBy { it.contactUserId }
        .mapValues { (_, records) -> records.maxOf { it.publishedAtMs } }

    fun all(): List<Record> = dir.listFiles { f -> f.name.endsWith(".bin") }
        ?.mapNotNull { read(it.name.removeSuffix(".bin")) }
        ?: emptyList()

    private fun read(infoHash: String): Record? = runCatching {
        val f = File(dir, "$infoHash.bin")
        if (!f.isFile) return null
        val o = StrictJsonParser(
            box.open(NS, infoHash, f.readText(Charsets.UTF_8)).toString(Charsets.UTF_8)
        ).parse() as JValue.Obj
        Record(
            infoHashV2Hex = infoHash,
            contactUserId = (o.fields["contact_user_id"] as JValue.Str).value,
            publishedAtMs = (o.fields["published_at_ms"] as JValue.IntNum).value,
            fileCount = (o.fields["file_count"] as JValue.IntNum).value.toInt(),
            servedPaths = (o.fields["served_paths"] as JValue.Arr).items
                .map { (it as JValue.Str).value }.toSet(),
            collectedAtMs = (o.fields["collected_at_ms"] as JValue.IntNum).value,
            // Optional: records written before the locker existed have no such field, and losing
            // them to a missing key would make every one of them unreadable.
            droppedFromLocker = (o.fields["dropped_from_locker"] as? JValue.IntNum)?.value == 1L,
        )
    }.getOrNull()

    private fun write(record: Record) {
        val json = CanonicalJson.obj(
            mapOf(
                "collected_at_ms" to CanonicalJson.long(record.collectedAtMs),
                "dropped_from_locker" to CanonicalJson.long(if (record.droppedFromLocker) 1L else 0L),
                "contact_user_id" to CanonicalJson.string(record.contactUserId),
                "file_count" to CanonicalJson.long(record.fileCount.toLong()),
                "published_at_ms" to CanonicalJson.long(record.publishedAtMs),
                "served_paths" to CanonicalJson.arr(
                    record.servedPaths.sorted().map { CanonicalJson.string(it) }
                ),
            )
        )
        File(dir, "${record.infoHashV2Hex}.bin")
            .writeText(box.seal(NS, record.infoHashV2Hex, json.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
    }

    // The infohash becomes a filename and the sealing AAD, and `AndroidLocalSecretBox` restricts a
    // logical id to `[A-Za-z0-9._-]`. Hex satisfies both; anything else is refused rather than
    // sanitised, so a malformed value cannot silently share a record with a well-formed one.
    private fun isHex(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

    private companion object {
        const val NS = "delivery-ledger"
        val LOCK = Any()
    }
}
