package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.archive.JcaArchivePublisher
import org.eidolang.core.crypto.JvmPrivateIdentity
import org.eidolang.feature.home.ContactCard
import org.eidolang.feature.home.ContactOnionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The address has to travel, or the second device never learns where to ask.
 *
 * Cheap and Tor-free on purpose: this is about the card and the store, and running it behind a
 * two-minute bootstrap would mean it rarely gets run.
 */
class ContactCardOnionTest {

    private val onion = "fl3h3yb4pzhelkia3bx4z6g2syx5dw34c4bbrrfa7rvbl5leagfzofqd.onion"

    @Test
    fun cardCarriesTheOnionAddressAndRefusesRubbish() {
        val random = SecureRandom()
        val identity = JvmPrivateIdentity.generate(random)
        val publisher = JcaArchivePublisher.create(identity, random)

        val card = ContactCard.write(identity, publisher.certificate, "Аня", null, onion)
        assertEquals(onion, ContactCard.read(card).onion)

        // Cards written before the field existed are already in people's hands, and the app must not
        // start refusing them.
        val old = ContactCard.write(identity, publisher.certificate, "Аня", null)
        assertEquals("", ContactCard.read(old).onion)

        // The address is handed to a network client as a hostname. Anything that is not a v3 address
        // is dropped rather than passed along — a card is a file somebody else wrote.
        listOf("evil.example.com", "../../etc/hosts", "FL3H3Y.onion", "short.onion", onion.dropLast(6))
            .forEach { bad ->
                val forged = card.replace(onion, bad)
                assertEquals("принят мусорный адрес: $bad", "", ContactCard.read(forged).onion)
            }
    }

    @Test
    fun storeKeepsOnlyValidAddresses() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ContactOnionStore(ctx)
        val user = "test-" + java.lang.Long.toHexString(random())

        store.put(user, onion)
        assertEquals(onion, store.get(user))

        // A later bad write must not overwrite a good address with nothing, and must not store it.
        store.put(user, "nonsense")
        assertEquals("плохая запись затёрла хороший адрес", onion, store.get(user))
        assertNull(store.get(user + "-unknown"))
        assertTrue(java.io.File(ctx.filesDir, "contact-onion/$user.txt").delete())
    }

    private fun random() = SecureRandom().nextLong() and 0xFFFFFFFFL
}
