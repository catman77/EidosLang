package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.admission.AndroidAdmissionProbe
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.crypto.IdentityCanonical
import org.eidolang.core.crypto.JvmPrivateIdentity
import org.eidolang.core.crypto.PublicIdentityBundleV1
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.LocalMessengerService
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * R22.1 runtime admission gate.
 *
 * `R22_ANDROID_ADMISSION.md` states the required product condition as
 * `AndroidAdmissionReport.passed = true`, observed on an actual Android runtime. The Diagnostics
 * screen reaches the same probe through a button; this drives it deterministically so the gate
 * can be recorded without manual UI interaction.
 *
 * The repository is seeded first on purpose: `repository.sensitive_text` audits stored contact
 * aliases and conversation titles, so on an empty database it passes over zero rows and proves
 * nothing. Seeding makes that check non-vacuous.
 */
class AndroidAdmissionTest {

    @Test
    fun r22AndroidRuntimeAdmissionPasses() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val random = SecureRandom()

        val identity = AndroidKeystoreIdentityStore(context).ensure()
        val repository = AndroidSqliteMessengerRepository(
            context,
            AndroidRepositoryTextProtector(context),
        )

        val service = LocalMessengerService(repository, identity, random)
        val peer = JvmPrivateIdentity.generate(random)
        service.importContact(
            IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(peer.user, peer.certificate)),
            "Проба ${System.nanoTime()}",
            100,
        )
        service.createConversation(
            remoteUserIds = listOf(peer.user.userId),
            title = "Тестовая беседа",
            createdAtMs = 100,
        )

        val audit = repository.sensitiveTextAudit()
        println("ADMISSION audit rows contacts=${audit.contactRows} conversations=${audit.conversationRows}")
        assertTrue(
            "sensitive_text audit would be vacuous: $audit",
            audit.contactRows > 0 && audit.conversationRows > 0,
        )

        val report = AndroidAdmissionProbe(context).run(identity, repository)

        report.checks.forEach {
            println("ADMISSION ${if (it.passed) "PASS" else "FAIL"} ${it.id} — ${it.detail}")
        }
        println("ADMISSION ${if (report.passed) "PASS" else "FAIL"} sdk=${report.sdkInt} device=${report.deviceId}")

        val required = listOf(
            "identity.bundle",
            "identity.sign",
            "identity.rsa_oaep",
            "local.secret_box",
            "vault.secret_store",
            "repository.sensitive_text",
        )
        assertTrue(
            "missing required checks: ${required - report.checks.map { it.id }.toSet()}",
            report.checks.map { it.id }.containsAll(required),
        )
        assertTrue(
            report.checks.joinToString("; ") { "${it.id}=${it.passed} (${it.detail})" },
            report.passed,
        )
    }
}
