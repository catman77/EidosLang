package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.crypto.HexSha256
import org.eidolang.feature.home.ContactCard
import org.eidolang.feature.home.EidoInvite
import org.eidolang.feature.home.qrBitmap
import org.eidolang.transport.libtorrent4j.AndroidArchivePublisherStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The introduction people will actually use: a code to show, or a link to send.
 *
 * The measurement is the reason this design exists, so it is asserted rather than remembered. A link
 * carrying the whole card came to 1732 characters after Deflate and base64url — a 145×145-module QR,
 * which is at the edge of what a phone camera reads across a table. A locator link is an order of
 * magnitude smaller, and the assertion below fails if a future change starts inflating it again.
 *
 * No Tor here; resolving a link over a real circuit is `InviteOverOnionTest`.
 */
class InviteLinkTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val onion = "a".repeat(56) + ".onion"

    private fun card(nickname: String): String {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        return ContactCard.write(
            identity, AndroidArchivePublisherStore(ctx).ensure(identity).certificate,
            nickname = nickname, avatarPng = null, onion = onion,
        )
    }

    @Test
    fun aLinkCarriesNicknameAndResolvesToExactlyOneCard() {
        val nickname = "Катман Джо"
        val json = card(nickname)
        val link = EidoInvite.link(onion, json, nickname)

        val parsed = EidoInvite.parse(link)
        assertNotNull("ссылка не разобралась", parsed)
        parsed!!
        assertEquals("адрес потерялся", onion, parsed.onion)
        assertEquals("ник не доехал в ссылке", nickname, parsed.nickname)
        assertEquals("ссылка указывает не на эту визитку", HexSha256.ofUtf8(json), parsed.cardSha256Hex)

        // The whole point of the locator design. A full-card link measured 1732 characters.
        val modules = Encoder.encode(
            link, ErrorCorrectionLevel.Q, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
        ).matrix.width
        println("INVITE link=${link.length} chars, QR=${modules}x$modules modules")
        assertTrue("ссылка разрослась: ${link.length}", link.length < 260)
        assertTrue("QR стал слишком плотным для камеры: $modules", modules <= 77)
        assertNotNull("QR не отрисовался", qrBitmap(link))
        println("INVITE PASS a link with the nickname fits a QR a phone camera can read")
    }

    @Test
    fun anythingThatIsNotOneOfOurLinksIsRefused() {
        val json = card("Катман")
        val link = EidoInvite.link(onion, json, "Катман")
        val hash = HexSha256.ofUtf8(json)

        // Each of these is a string that could plausibly arrive from a scanner or a chat message.
        // They are refused at parse, before anything is asked of the network, because both fields
        // are acted on: the address is dialled and the hash decides whose identity is accepted.
        listOf(
            "https://example.com/$onion/$hash",
            "eidolang://directory/$onion/$hash",
            "eidolang://invite/$onion",
            "eidolang://invite/$onion/$hash/extra",
            "eidolang://invite/not-an-onion.onion/$hash",
            "eidolang://invite/$onion/" + "z".repeat(64),
            "eidolang://invite/$onion/" + hash.dropLast(1),
            "",
            "просто текст",
        ).forEach { assertNull("принята негодная ссылка: $it", EidoInvite.parse(it)) }

        // A nickname is free text from a stranger and must not be able to smuggle structure.
        val hostile = EidoInvite.parse(EidoInvite.link(onion, json, "Аня <b>&n=x&nbsp;/../"))
        assertNotNull("ссылка с недобрым ником не разобралась", hostile)
        assertEquals("ник изменил адрес", onion, hostile!!.onion)
        assertEquals("ник изменил хэш визитки", hash, hostile.cardSha256Hex)
        println("INVITE PASS malformed and hostile links are refused before anything is dialled")
    }

    /**
     * What the address answers with is accepted only if it is what the link named.
     *
     * The transport is proven in `InviteOverOnionTest`; this is the same decision without a circuit,
     * so the rule can be re-checked in seconds by whoever next touches it. Every case here is a card
     * that a perfectly reachable, perfectly well-behaved onion service could return.
     */
    @Test
    fun onlyTheCardTheLinkNamedIsAccepted() {
        val json = card("Катман")
        val invite = EidoInvite.parse(EidoInvite.link(onion, json, "Катман"))!!
        val bytes = json.toByteArray(Charsets.UTF_8)

        assertEquals("своя же визитка не принята", json, EidoInvite.verifyCard(bytes, invite))

        // A different card that is in every other way beyond reproach: same identity, same address,
        // valid canonical JSON, valid signatures — only the nickname differs, so only the hash can
        // tell it apart. This is the assertion the hash exists for, and it is the only one here that
        // fails when the hash check is removed.
        //
        // It replaced a flipped-byte case that looked like it did this job and did not: corrupt
        // bytes are refused by the JSON parser whether or not the hash is checked at all, so with
        // the check deleted the whole test still passed. Verified by deleting it.
        val different = card("Другое имя").toByteArray(Charsets.UTF_8)
        assertNull("принята чужая, но исправная визитка", EidoInvite.verifyCard(different, invite))

        val flipped = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertNull("принята повреждённая визитка", EidoInvite.verifyCard(flipped, invite))

        // The right card, but the link said to expect a different address. Accepting it would point
        // every later message at somebody else's device.
        assertNull(
            "принята визитка с чужим адресом",
            EidoInvite.verifyCard(bytes, invite.copy(onion = "b".repeat(56) + ".onion")),
        )

        assertNull("принято пустое тело", EidoInvite.verifyCard(ByteArray(0), invite))
        assertNull(
            "принято не-JSON тело",
            EidoInvite.verifyCard("не визитка".toByteArray(Charsets.UTF_8), invite),
        )
        println("INVITE PASS a card is accepted only when it matches the link's hash and address")
    }
}
