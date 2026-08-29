package org.eidolang.feature.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.eidolang.core.canonical.CanonicalJson
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.canonical.EidogramParser
import org.eidolang.core.canonical.JValue
import org.eidolang.core.canonical.StrictJsonParser
import org.eidolang.core.hardening.AndroidLocalSecretBox
import org.eidolang.core.model.EidogramDocumentV1
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Who the user is, locally: a nickname and an avatar.
 *
 * Neither travels anywhere. The identity that goes on the wire is the R15 key material; this is
 * presentation only, and it is sealed with the same R21 `AndroidLocalSecretBox` that protects
 * contact aliases, so a lifted app directory does not spill the user's chosen name or face.
 */
class EidoProfileStore(private val context: Context) {

    data class Profile(val nickname: String, val avatarPng: ByteArray?) {
        override fun equals(other: Any?) = other is Profile && other.nickname == nickname &&
            (other.avatarPng?.contentEquals(avatarPng ?: ByteArray(0)) ?: (avatarPng == null))
        override fun hashCode() = nickname.hashCode()
    }

    private val box = AndroidLocalSecretBox(context)
    private val file get() = File(context.filesDir, "eidolang-profile-v1.bin")

    fun exists() = file.exists()

    fun load(): Profile? {
        if (!file.exists()) return null
        return runCatching {
            val raw = box.open(NS, ID, file.readText(Charsets.UTF_8)).toString(Charsets.UTF_8)
            val o = StrictJsonParser(raw).parse() as JValue.Obj
            val nick = (o.fields["nickname"] as JValue.Str).value
            val avatar = (o.fields["avatar_png_b64"] as? JValue.Str)?.value
                ?.takeIf { it.isNotEmpty() }
                ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
            Profile(nick, avatar)
        }.getOrNull()
    }

    fun save(profile: Profile) {
        val json = CanonicalJson.obj(
            mapOf(
                "avatar_png_b64" to CanonicalJson.string(
                    profile.avatarPng?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: ""
                ),
                "nickname" to CanonicalJson.string(profile.nickname),
            )
        )
        file.writeText(box.seal(NS, ID, json.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
    }

    private companion object {
        const val NS = "profile"
        const val ID = "local-profile-v1"
    }
}

/** Downscale and re-encode a picked image so an avatar stays small enough to seal comfortably. */
fun compressAvatar(source: Bitmap, maxEdge: Int = 256): ByteArray {
    val scale = maxEdge.toFloat() / maxOf(source.width, source.height)
    val bitmap = if (scale >= 1f) source else Bitmap.createScaledBitmap(
        source, (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1), true,
    )
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

fun decodeAvatar(png: ByteArray): Bitmap? = runCatching {
    BitmapFactory.decodeByteArray(png, 0, png.size)
}.getOrNull()

/**
 * The user's own eidograms, before and after they are sent.
 *
 * A composed eidogram is worth keeping whether or not it was addressed to anyone — the catalogue is
 * the user's own record, and sending is a separate act performed on an entry.
 */
class EidogramCatalogStore(private val context: Context) {

    data class Entry(
        val id: String,
        val createdAtMs: Long,
        val caption: String,
        val document: EidogramDocumentV1,
        val sentTo: List<String>,
        /**
         * When each recipient was sent this, against which [DeliveryLedger] collections are read.
         *
         * Separate from [createdAtMs]: composing on Monday and sending on Friday would otherwise
         * let any collection made in between count as delivering this eidogram.
         */
        val sentAtMs: Map<String, Long> = emptyMap(),
    )

    private val box = AndroidLocalSecretBox(context)
    private val dir get() = File(context.filesDir, "eidogram-catalog").apply { mkdirs() }

    fun list(): List<Entry> = dir.listFiles { f -> f.name.endsWith(".bin") }
        ?.mapNotNull { read(it) }
        ?.sortedByDescending { it.createdAtMs }
        ?: emptyList()

    fun save(entry: Entry) {
        val json = CanonicalJson.obj(
            mapOf(
                "caption" to CanonicalJson.string(entry.caption),
                "created_at_ms" to CanonicalJson.long(entry.createdAtMs),
                "document" to EidogramCanonical.documentJson(entry.document),
                "id" to CanonicalJson.string(entry.id),
                "sent_at_ms" to CanonicalJson.obj(
                    entry.sentAtMs.mapValues { (_, ms) -> CanonicalJson.long(ms) }
                ),
                "sent_to" to CanonicalJson.arr(entry.sentTo.map { CanonicalJson.string(it) }),
            )
        )
        File(dir, "${entry.id}.bin")
            .writeText(box.seal(NS, entry.id, json.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
    }

    fun markSent(entry: Entry, contactUserId: String, atMs: Long) = save(
        entry.copy(
            sentTo = (entry.sentTo + contactUserId).distinct(),
            sentAtMs = entry.sentAtMs + (contactUserId to atMs),
        )
    )

    fun delete(id: String) {
        File(dir, "$id.bin").delete()
    }

    private fun read(f: File): Entry? = runCatching {
        val id = f.name.removeSuffix(".bin")
        val raw = box.open(NS, id, f.readText(Charsets.UTF_8)).toString(Charsets.UTF_8)
        val o = StrictJsonParser(raw).parse() as JValue.Obj
        Entry(
            id = (o.fields["id"] as JValue.Str).value,
            createdAtMs = (o.fields["created_at_ms"] as JValue.IntNum).value,
            caption = (o.fields["caption"] as JValue.Str).value,
            document = EidogramParser.parseCanonical(
                org.eidolang.core.canonical.CanonicalReserialize.of(o.fields["document"]!!)
            ),
            sentTo = (o.fields["sent_to"] as JValue.Arr).items.map { (it as JValue.Str).value },
            // Absent in entries written before delivery was tracked. `createdAtMs` is the only other
            // instant on record and is never later than the send, so an old entry can show two ticks
            // slightly early — but only within its own compose-to-send gap, and only once its
            // recipient has genuinely collected something.
            sentAtMs = (o.fields["sent_at_ms"] as? JValue.Obj)?.fields.orEmpty()
                .mapValues { (_, v) -> (v as JValue.IntNum).value },
        )
    }.getOrNull()

    private companion object {
        const val NS = "eidogram-catalog"
    }
}

/** Avatars of contacts, sealed like every other local file. The nickname lives in the repository's alias. */
class ContactAvatarStore(private val context: Context) {
    private val box = AndroidLocalSecretBox(context)
    private val dir get() = File(context.filesDir, "contact-avatars").apply { mkdirs() }

    fun put(userId: String, png: ByteArray) {
        File(dir, "$userId.bin").writeText(
            box.seal(NS, userId, android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
                .toByteArray(Charsets.UTF_8)),
            Charsets.UTF_8,
        )
    }

    fun get(userId: String): ByteArray? = runCatching {
        val f = File(dir, "$userId.bin")
        if (!f.exists()) return null
        android.util.Base64.decode(
            box.open(NS, userId, f.readText(Charsets.UTF_8)).toString(Charsets.UTF_8),
            android.util.Base64.NO_WRAP,
        )
    }.getOrNull()

    private companion object { const val NS = "contact-avatar" }
}
