package org.eidolang.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.crypto.AccountBackupCipher
import org.eidolang.core.hardening.*
import org.eidolang.core.repository.LocalMessengerService
import org.eidolang.feature.home.EidoProfileStore
import org.eidolang.feature.home.RestoredHistory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@TwoDeviceOnly
class AccountBackupAutosaveTest {
    @Test fun changesAreSavedAutomaticallyAndFailedReconfigurationKeepsGoodCopy() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName == "org.eidolang.app.recoverycheck")
        assertTrue(AccountBackups.isConfigured(ctx))
        AccountBackups.start(ctx)
        val original = EidoProfileStore(ctx).load()!!
        val changedAt = System.currentTimeMillis()
        EidoProfileStore(ctx).save(original.copy(nickname = "Автоматически сохранённый профиль"))
        val identity = AndroidAccountRecovery.currentIdentity(ctx)!!
        val provider = RestoredHistory.open(ctx)
        val repo = AndroidMessengerRepositoryProvider.get(ctx)
        val service = LocalMessengerService(repo, identity, historicalDocumentProvider = provider)
        val conversation = service.conversationSummaries().single().conversationId
        val previousCount = service.timeline(conversation).size
        val document = service.timeline(conversation).first().document
        service.send(conversation, document, changedAt + 1, caption = "Новое сообщение после настройки копии")
        provider?.close()
        val deadline = android.os.SystemClock.elapsedRealtime() + 60_000
        while (AccountBackups.savedAt(ctx) <= changedAt || AccountBackups.isPending(ctx)) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "Автоматическая копия не обновилась: ${AccountBackups.error(ctx)}" }
            Thread.sleep(200)
        }
        val uri = AccountBackups.latestBackupUri(ctx)!!
        val good = AccountBackups.readDocument(ctx, uri)
        val plain = AccountBackupCipher.decrypt(good, "fixture-only-recovery-password-2026".toCharArray())
        try {
            val snapshot = AndroidAccountRecovery.validate(plain)
            try {
                val profile = JSONObject(snapshot.files.getValue("eidolang-profile-v1.bin").toString(Charsets.UTF_8))
                assertEquals("Автоматически сохранённый профиль", profile.getString("nickname"))
                assertEquals(previousCount + 1, snapshot.messageCount)
            } finally { snapshot.close() }
        } finally { plain.fill(0) }
        assertTrue(runCatching {
            AccountBackups.configure(ctx, Uri.parse("content://com.android.externalstorage.documents/tree/primary%3Adoes-not-exist"),
                "fixture-only-recovery-password-2026".toCharArray())
        }.isFailure)
        assertEquals(uri, AccountBackups.latestBackupUri(ctx))
        assertArrayEquals(good, AccountBackups.readDocument(ctx, uri))
    }
}
