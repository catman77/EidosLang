package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.LocalMessengerService
import org.eidolang.feature.home.ContactPublisherStore
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Remove the contacts this project's own test suite seeded into a real address book.
 *
 * `AndroidAdmissionTest` and friends import a synthetic contact so the admission probe is not
 * auditing an empty table, and those rows stayed: two real phones ended up showing several
 * unreadable names that could never deliver anything. That is pollution I caused, and it is cleaned
 * with the same `deleteContact` the app now offers rather than by editing the database underneath
 * it.
 *
 * The rule is deliberately narrow. A contact goes only if it has **no publisher certificate** —
 * meaning it can never deliver and was never introduced through the product's own flow — and its
 * alias carries no `#` handle, which every real introduction produces. Messages are never touched.
 */
class PhantomContactCleanupTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun onlyContactsThatCanNeverDeliverAreRemoved() {
        val service = LocalMessengerService(
            AndroidSqliteMessengerRepository(ctx, AndroidRepositoryTextProtector(ctx)),
            AndroidKeystoreIdentityStore(ctx).ensure(), SecureRandom(),
        )
        val before = service.contactSummaries()
        println("CLEAN контактов было: ${before.size}")
        before.forEach {
            val hasKey = ContactPublisherStore(ctx).get(it.userId) != null
            println("CLEAN   ${it.alias} | ключ=${hasKey} | ${it.userId.take(12)}")
        }

        val phantoms = before.filter {
            !it.alias.contains('#') && ContactPublisherStore(ctx).get(it.userId) == null
        }
        phantoms.forEach {
            service.deleteContact(it.userId)
            println("CLEAN удалён фантом: ${it.alias} (${it.userId.take(12)})")
        }

        val after = service.contactSummaries()
        println("CLEAN осталось: ${after.size} -> ${after.map { it.alias }}")
        assertTrue(
            "удалён настоящий контакт",
            before.filter { it.alias.contains('#') }.all { real -> after.any { it.userId == real.userId } },
        )
        assertTrue("фантомы остались", after.none { it.userId in phantoms.map { p -> p.userId } })
        println("CLEAN PASS адресная книга чиста, настоящие контакты на месте")
    }
}
