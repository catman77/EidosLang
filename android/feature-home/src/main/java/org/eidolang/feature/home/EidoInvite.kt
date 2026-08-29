package org.eidolang.feature.home

import org.eidolang.core.crypto.HexSha256

/**
 * The link and QR code that introduce two people, so that nobody has to type an address or send a file.
 *
 * The obvious design is to put the whole contact card in the link. Measured on a real card, that is
 * 2380 bytes of canonical JSON — 1732 characters even after Deflate and base64url, which needs a
 * 145×145-module QR code. That is at the edge of what a phone camera reads across a table and past
 * the edge of what survives being printed or screenshotted. The RSA-2048 recipient key alone is a
 * third of it, and none of it can be dropped: it is what encryption and admission need.
 *
 * So the link carries a **locator**, not the card:
 *
 * ```
 * eidolang://invite/<56-char onion>/<sha-256 of the card, hex>?n=<nickname>
 * ```
 *
 * about 155 characters — a small, forgiving QR. The card itself is fetched from that address the
 * moment the link is opened, exactly as an avatar now is. This is the same trade the avatar forced,
 * applied one level up, and it needs no new wire entity: `/card` serves the very file people already
 * exchange by hand.
 *
 * Two things make the locator safe to act on:
 *
 *  - A v3 onion address **is** an Ed25519 public key, so the circuit is authenticated to the holder
 *    of that key before a byte of card arrives. There is nobody in the middle to substitute one.
 *  - The hash pins *which* card that address may answer with. Without it, a device that later
 *    re-keyed — or was taken over — could answer an old, still-circulating link with a different
 *    identity, and the person scanning would have no way to notice.
 *
 * The nickname rides along unauthenticated and is deliberately treated as a label, not a fact: it
 * lets a link forwarded in a chat say who it is from before anyone goes online, and it is replaced
 * by the card's own nickname on import. Anyone can write anything there — which is why what ends up
 * in the address book is the self-certifying handle from [EidoHandle], suffix and all.
 */
object EidoInvite {

    const val SCHEME = "eidolang"
    const val HOST = "invite"

    /** How big a card this device will accept from a stranger's address. Real ones are ~2.4 KB. */
    const val MAX_CARD_BYTES = 64 * 1024

    private val ONION = Regex("[a-z2-7]{56}\\.onion")
    private val HEX64 = Regex("[0-9a-f]{64}")

    data class Parsed(
        val onion: String,
        val cardSha256Hex: String,
        /** Unverified. See the class comment — a label to show while fetching, never a decision. */
        val nickname: String,
    )

    /** The link to put behind a QR code or into a share sheet. */
    fun link(onion: String, cardJson: String, nickname: String): String {
        require(onion.matches(ONION)) { "нет onion-адреса" }
        val hash = HexSha256.ofUtf8(cardJson)
        val n = java.net.URLEncoder.encode(nickname, "UTF-8")
        return "$SCHEME://$HOST/$onion/$hash" + if (nickname.isBlank()) "" else "?n=$n"
    }

    /**
     * Read a link back, or null if it is not one.
     *
     * Every field is checked against its own shape before it is returned. The onion goes to a network
     * client and the hash decides whether a stranger's identity is accepted, so neither may be a
     * string that merely happened to be in a URI somebody sent.
     */
    fun parse(text: String): Parsed? {
        val uri = runCatching { android.net.Uri.parse(text.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST, ignoreCase = true)) return null
        val parts = uri.pathSegments ?: return null
        if (parts.size != 2) return null
        val onion = parts[0].lowercase()
        val hash = parts[1].lowercase()
        if (!onion.matches(ONION) || !hash.matches(HEX64)) return null
        val nickname = runCatching { uri.getQueryParameter("n") }.getOrNull().orEmpty().take(64)
        return Parsed(onion, hash, nickname)
    }

    /**
     * Fetch the card this link points at and prove it is the one the link meant.
     *
     * Both checks are refusals, not warnings. A hash mismatch means the address answered with an
     * identity the person who wrote the link never vouched for; a card naming a different address
     * means the introduction would quietly redirect every later message somewhere else.
     */
    fun fetchCard(invite: Parsed): String? =
        EidoOnionClient.get(invite.onion, "/card/${invite.cardSha256Hex}", maxBytes = MAX_CARD_BYTES)
            ?.let { verifyCard(it, invite) }

    /**
     * Decide whether these bytes are the card the link promised.
     *
     * Kept apart from the fetch so it can be exercised without a Tor circuit. That is not tidiness:
     * a locator design fails open if the hash is not really enforced, and a check reachable only
     * through a ten-minute network test is a check nobody re-runs when they change it.
     */
    fun verifyCard(bytes: ByteArray, invite: Parsed): String? {
        if (bytes.size > MAX_CARD_BYTES) return null
        if (HexSha256.of(bytes) != invite.cardSha256Hex) return null
        val json = bytes.toString(Charsets.UTF_8)
        val card = runCatching { ContactCard.read(json) }.getOrNull() ?: return null
        if (card.onion != invite.onion) return null
        return json
    }
}
