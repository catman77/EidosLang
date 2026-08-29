package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.model.EidogramAction
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.model.FixedTransform
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.LocalMessengerService
import org.eidolang.feature.home.ConversationConvention
import org.eidolang.feature.home.EidoTransport
import org.eidolang.feature.home.publishConversation
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.security.SecureRandom

/**
 * Send to the real contact by the product path, so the other device has something to collect.
 *
 * Half of the scenario the locker exists for: this device authors, publishes and then goes away.
 * The other half runs on the recipient with this app force-stopped, which is the state every
 * earlier attempt failed in.
 */
@NativeSessionHeavy
class SendToContactTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sendOneEidogramToTheRealContact() {
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        val service = LocalMessengerService(
            AndroidSqliteMessengerRepository(ctx, AndroidRepositoryTextProtector(ctx)),
            identity, SecureRandom(),
        )
        val contact = service.contactSummaries().firstOrNull { it.alias.contains('#') }
        Assume.assumeTrue("нет настоящего контакта", contact != null)
        val conversation = ConversationConvention.oneToOne(identity.user.userId, contact!!.userId)
        runCatching {
            service.importConversation(
                org.eidolang.core.message.ConversationCanonical.descriptorJson(conversation),
                contact.alias, System.currentTimeMillis(),
            )
        }
        // A triangle inside a circle — the overlap the editor could not do until this week, so the
        // eidogram that arrives is recognisable as this one and not an older leftover.
        val document = EidogramDocumentV1(
            listOf(
                EidogramAction.Add(0, "g000001", "circle.red.l", FixedTransform(500_000, 500_000), 0),
                EidogramAction.Add(1, "g000002", "triangle.blue.s", FixedTransform(500_000, 520_000), 1),
            )
        )
        service.send(conversation.conversationId, document, System.currentTimeMillis(), "проверка камеры хранения")

        val started = System.currentTimeMillis()
        val outcome = publishConversation(
            ctx, service, EidoTransport.of(ctx, identity), identity,
            conversation.conversationId, service.contactBundleCanonical(contact.userId),
        )
        val took = System.currentTimeMillis() - started
        println("SEND кому=${contact.alias} за ${took}мс итог=$outcome")
        assertTrue("сообщение не попало в камеру хранения: $outcome", outcome.contains("заберут"))
        println("SEND PASS сообщение оставлено там, где его возьмут без этого устройства")
    }
}
