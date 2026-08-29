package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.crypto.HexSha256
import org.eidolang.feature.home.ContactCard
import org.eidolang.feature.home.EidoInvite
import org.eidolang.feature.home.EidoOnionClient
import org.eidolang.feature.home.EidoOnionServer
import org.eidolang.feature.home.EidoTor
import org.eidolang.feature.home.OnionStore
import org.eidolang.transport.libtorrent4j.AndroidArchivePublisherStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Scanning a code has to end with a contact, and only with the right one.
 *
 * The link is a locator, so everything that makes it trustworthy happens *after* it is read: the
 * card is fetched from the address in the link, over a circuit authenticated to that address, and
 * accepted only if it hashes to what the link promised. This runs that path against the device's own
 * onion service, which exercises descriptor lookup and rendezvous exactly as a stranger's phone
 * would — the one thing it cannot show is that the two ends are different devices.
 *
 * The refusals are the substance. A locator design fails open unless the hash is genuinely enforced,
 * and "it fetched something" would look identical to "it fetched the right thing".
 */
@NativeSessionHeavy
class InviteOverOnionTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aScannedLinkResolvesToTheCardItNamesAndNothingElse() {
        val tor = EidoTor.of(ctx)
        val onion = tor.startAndPublish()
        assertNotNull("Tor не подключился: ${tor.attempts}", onion)

        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val publisher = AndroidArchivePublisherStore(ctx).ensure(identity).certificate
        val card = ContactCard.write(identity, publisher, "Катман", null, onion!!)

        val store = OnionStore(ctx)
        store.putCard(card.toByteArray(Charsets.UTF_8))
        val server = EidoOnionServer(store)
        server.start()
        try {
            val link = EidoInvite.link(onion, card, "Катман")
            val invite = EidoInvite.parse(link)
            assertNotNull("своя же ссылка не разобралась", invite)

            // Wait for the descriptor the way the product does; publishing a service is not the same
            // as being reachable through it.
            assertNotNull(
                "онион не отвечает",
                EidoOnionClient.getRetrying(onion, "/hello") { println("INVITE descriptor attempt $it") },
            )

            val fetched = EidoInvite.fetchCard(invite!!)
            assertEquals("визитка вернулась не той", card, fetched)
            assertEquals(
                "имя в адресной книге не из визитки",
                "Катман",
                ContactCard.read(fetched!!).nickname,
            )
            println("INVITE PASS a link resolves to its exact card over a real circuit")

            // A link naming a card this address does not have. Note what this does and does not
            // show: cards are now served *by hash*, so an honest device answers 404 here and the
            // comparison in `verifyCard` is never reached. The case where a device answers with the
            // wrong card on purpose cannot be staged against our own server, and is covered — with
            // a mutation proving it — by `InviteLinkTest.onlyTheCardTheLinkNamedIsAccepted`.
            val wrong = invite.copy(cardSha256Hex = HexSha256.ofUtf8(card + " "))
            assertNull("отдана визитка по чужому хэшу", EidoInvite.fetchCard(wrong))
            println("INVITE PASS an address will not answer for a card the link did not name")

            // A card that names a different address than the one it was served from. Accepting it
            // would silently point every later message somewhere else.
            val elsewhere = ContactCard.write(identity, publisher, "Катман", null, OTHER_ONION)
            store.putCard(elsewhere.toByteArray(Charsets.UTF_8))
            val relinked = EidoInvite.parse(EidoInvite.link(onion, elsewhere, "Катман"))!!
            assertNull("принята визитка, указывающая на чужой адрес", EidoInvite.fetchCard(relinked))
            println("INVITE PASS a card pointing at another address is refused")
        } finally {
            store.putCard(card.toByteArray(Charsets.UTF_8))   // leave the device serving its own card
            server.stop()
        }
    }

    private companion object {
        /** Well-formed and not ours: the refusal must be about the mismatch, not about the shape. */
        const val OTHER_ONION = "ipb4lhwfordul3dbqt2mlhduvqlztjrrl3dvc2qs2zltxzbt33ff4wg3.onion"
    }
}
