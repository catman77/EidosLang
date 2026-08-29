package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.feature.home.ContactCard
import org.eidolang.feature.home.ContactOnionStore
import org.eidolang.feature.home.ContactPublisherStore
import org.eidolang.feature.home.Relay
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adding somebody from the nickname directory must leave behind everything receiving needs.
 *
 * A contact row on its own is not enough and the app never said so: `receiveFrom` starts with
 * `ContactPublisherStore.get(id) ?: return 0`, so a contact stored without its publisher
 * certificate can never be received from — silently, forever. Both devices in this project ended
 * up in exactly that state, with the contact visible in the list and no `contact-publishers`
 * directory on disk at all.
 *
 * This walks the same three steps the "Найти людей" dialog walks and checks each one separately,
 * so a failure names which step lost the data instead of leaving it to be inferred from what is
 * missing afterwards.
 */
class DirectoryAddDiagnosticTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theDirectoryAddPathKeepsPublisherAndOnion() {
        val hits = Relay.searchDirectory("Catman")
        assertNotNull("каталог недоступен", hits)
        println("DIAG найдено записей: ${hits!!.size} -> ${hits.map { it.nickname }}")
        val mine = org.eidolang.core.crypto.AndroidKeystoreIdentityStore(ctx).ensure().user.userId
        val hit = hits.firstOrNull { it.userId != mine }
        assertNotNull("в каталоге нет никого, кроме меня самого", hit)

        val card = ContactCard.read(hit!!.cardJson)
        println("DIAG ник=${card.nickname} publisher=${card.publisher != null} onion='${card.onion}'")
        assertNotNull("карточка из каталога без publisher — приём был бы невозможен", card.publisher)
        assertTrue("карточка из каталога без onion-адреса", card.onion.isNotEmpty())

        ContactPublisherStore(ctx).put(hit.userId, card.publisher!!)
        ContactOnionStore(ctx).put(hit.userId, card.onion)
        assertNotNull("publisher не сохранился", ContactPublisherStore(ctx).get(hit.userId))
        assertNotNull("onion не сохранился", ContactOnionStore(ctx).get(hit.userId))
        println("DIAG PASS publisher и onion сохранены для ${hit.userId.take(12)}")
    }
}
