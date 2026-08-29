package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.feature.home.ContactOnionStore
import org.eidolang.feature.home.refreshContactTransport
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A contact introduced before they had an address must not stay unreachable forever.
 *
 * This is the failure that produced "the sender publishes and the recipient never receives". The
 * tablet made itself findable seconds after installing, before Tor had come up, so the card in the
 * directory carried no onion address; the phone imported that card at 02:04 and stored nothing for
 * transport. The tablet obtained an address at 02:12 — and the phone never looked again, so every
 * later poll silently fell back to the DHT, which this project already knows does not find things
 * across devices.
 *
 * The test removes the stored address the way that history left it — absent — and asserts it comes
 * back on its own.
 */
class ContactTransportRefreshTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aMissingContactAddressIsRecoveredFromTheDirectory() {
        val service = org.eidolang.core.repository.LocalMessengerService(
            org.eidolang.core.repository.AndroidSqliteMessengerRepository(
                ctx, org.eidolang.core.hardening.AndroidRepositoryTextProtector(ctx)
            ),
            org.eidolang.core.crypto.AndroidKeystoreIdentityStore(ctx).ensure(),
            java.security.SecureRandom(),
        )
        // Whichever real contact this device has; the test scaffolding contacts have no directory
        // entry and are skipped by the same rule the product uses — an unknown nickname finds no one.
        val contact = service.contactSummaries().firstOrNull { it.alias.contains('#') }
        org.junit.Assume.assumeTrue("на устройстве нет контакта из каталога", contact != null)

        val file = File(ctx.filesDir, "contact-onion/${contact!!.userId}.txt")
        file.delete()
        assertNull("адрес не удалось стереть для чистоты пробы", ContactOnionStore(ctx).get(contact.userId))

        val ok = refreshContactTransport(ctx, contact.userId, contact.alias)
        assertTrue("адрес контакта не восстановился из каталога", ok)
        val onion = ContactOnionStore(ctx).get(contact.userId)
        assertNotNull("адрес не сохранился", onion)
        println("REFRESH PASS ${contact.alias} -> $onion")
    }
}
