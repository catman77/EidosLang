package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.LocalMessengerService
import org.eidolang.feature.home.ContactOnionStore
import org.eidolang.feature.home.EidoTransport
import org.eidolang.feature.home.receiveFrom
import org.eidolang.feature.home.refreshContactTransport
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.security.SecureRandom

/**
 * Actually receive from the real contact on this device, and say what happened.
 *
 * The product path, not a rehearsal of it: same `refreshContactTransport`, same `receiveFrom`, same
 * retries. It exists because "nothing arrived" had four different causes over one evening — a
 * deleted database, an address captured before it existed, a directory that was down, and a head
 * asked for exactly once — and each of them looked identical from the screen.
 *
 * Requires the *other* device to be awake with the app open: an onion service exists only while its
 * process does, and there is no longer a server standing in for it.
 */
@NativeSessionHeavy
class ReceiveFromContactTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theRealContactDelivers() {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val service = LocalMessengerService(
            AndroidSqliteMessengerRepository(ctx, AndroidRepositoryTextProtector(ctx)),
            identity, SecureRandom(),
        )
        val contact = service.contactSummaries().firstOrNull { it.alias.contains('#') }
        Assume.assumeTrue("на устройстве нет настоящего контакта", contact != null)
        println("RECV контакт=${contact!!.alias}")

        // Bring this device's own Tor up first and wait for it. Without this the test measured
        // nothing but its own missing SOCKS port — every onion attempt died on `127.0.0.1:59050`.
        org.eidolang.feature.home.EidoOnionNode.ensureStarted(ctx)
        val until = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < until && !org.eidolang.feature.home.EidoOnionNode.isReachable) {
            Thread.sleep(5_000)
        }
        println("RECV свой Tor поднят=${org.eidolang.feature.home.EidoOnionNode.isReachable}")

        val hasAddress = refreshContactTransport(ctx, contact.userId, contact.alias)
        println("RECV адрес=${ContactOnionStore(ctx).get(contact.userId)} (дозаполнен=$hasAddress)")

        val before = service.timeline(
            org.eidolang.feature.home.ConversationConvention
                .oneToOne(identity.user.userId, contact.userId).conversationId
        ).size
        val started = System.currentTimeMillis()
        val r = receiveFrom(
            ctx, service, EidoTransport.of(ctx, identity), identity,
            contact.userId, service.contactBundleCanonical(contact.userId),
        )
        val after = service.timeline(
            org.eidolang.feature.home.ConversationConvention
                .oneToOne(identity.user.userId, contact.userId).conversationId
        ).size
        println("RECV за ${System.currentTimeMillis() - started}мс")
        println("RECV принято=${r.admitted} проблема=${r.problem ?: "нет"} лента $before -> $after")
        // Against the timeline growing, not against `before > 0`. As first written this said
        // `r.admitted > 0 || before > 0`, so a device that already had two old messages reported
        // "PASS доставка работает" while receiving precisely nothing.
        assertTrue(
            "ничего не принято: ${r.problem ?: "причина не названа"}",
            r.admitted > 0 && after > before,
        )
        println("RECV PASS доставка с контакта работает")
    }
}
