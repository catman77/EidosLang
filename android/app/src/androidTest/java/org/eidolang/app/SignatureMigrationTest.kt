package org.eidolang.app

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.*
import org.eidolang.core.message.MessageCanonical
import org.eidolang.core.multidevice.AndroidSecondaryDeviceIdentityStore
import org.eidolang.core.multidevice.DeviceEnrollmentAuthority
import org.eidolang.core.multidevice.EnrollmentCanonical
import org.eidolang.core.multidevice.JvmRootAuthority
import org.eidolang.core.repository.LocalMessengerService
import org.eidolang.core.vault.*
import org.eidolang.feature.home.ContactCard
import org.eidolang.feature.home.EidoProfileStore
import org.eidolang.feature.home.MigrationProfile
import org.eidolang.feature.home.RestoredHistory
import org.eidolang.feature.home.EidoOnionNode
import org.eidolang.feature.home.EidoTor
import org.eidolang.feature.home.OnionServiceKey
import org.eidolang.feature.onboarding.SecondaryEnrollment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Host-driven test on a fresh isolated emulator, with the first-publication app next to this one. */
@TwoDeviceOnly
class SignatureMigrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun ex(name: String) = File(context.filesDir, "exchange/$name").apply { parentFile!!.mkdirs() }
    private fun store() = AndroidSecondaryDeviceIdentityStore(context)

    @Test fun createRequestAndPreserveProfile() {
        assertTrue(BuildConfig.MIGRATION_INSTALL)
        assertEquals("org.eidolang.app.migration", context.packageName)
        ActivityScenario.launch(MainActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertFalse("Opening the migration app must never create a replacement primary profile", AndroidKeystoreIdentityStore(context).exists())
        }
        val card = ContactCard.read(ex("owner-card.json").readText())
        val owner = MigrationProfile.importOwnerCard(context, ex("owner-card.json").readText())
        assertEquals(card.identityCanonical, owner)
        val profile = EidoProfileStore(context).load()!!
        assertEquals(card.nickname, profile.nickname)
        assertArrayEquals(card.avatarPng, profile.avatarPng)
        ex("enrollment-request.json").writeText(store().createEnrollmentRequest(owner))
        assertEquals(owner, store().enrollmentOwnerCanonical())
        assertNull(SecondaryEnrollment.readyIdentity(context))
    }

    @Test fun rejectAuthorizationFromAnotherOwner() {
        val owner = store().enrollmentOwnerCanonical()!!
        val foreign = JvmPrivateIdentity.generate()
        val foreignOwner = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(foreign.user, foreign.certificate))
        val request = EnrollmentCanonical.parseCanonical(store().createEnrollmentRequest(foreignOwner))
        val certificate = DeviceEnrollmentAuthority.authorize(JvmRootAuthority.fromPrimary(foreign), request)
        val foreignBundle = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(foreign.user, certificate))
        store().createEnrollmentRequest(owner)
        try {
            store().installAuthorizedBundle(foreignBundle)
            fail("A valid certificate from a different root must not replace the selected profile")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("different profile"))
        }
        assertNull(store().load())
        assertEquals(owner, store().enrollmentOwnerCanonical())
    }

    @Test fun installCertificateButRequireRoster() {
        assertNotNull("Owner selection must survive process death", store().enrollmentOwnerCanonical())
        val identity = store().installAuthorizedBundle(ex("authorized-bundle.json").readText())
        val owner = IdentityParser.parsePublicBundleCanonical(store().enrollmentOwnerCanonical()!!)
        assertEquals(owner.user.userId, identity.user.userId)
        assertNotEquals(owner.device.deviceId, identity.certificate.deviceId)
        assertFalse(AndroidKeystoreIdentityStore(context).exists())
        assertNull("An installed certificate must not skip the final authorization step", SecondaryEnrollment.readyIdentity(context))
        ex("secondary-onion-key-hash.txt").writeText(HexSha256.ofUtf8(EidoOnionNode.derivedKey(context)!!))
        ActivityScenario.launch(MainActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertNull(SecondaryEnrollment.readyIdentity(context))
            assertFalse(AndroidKeystoreIdentityStore(context).exists())
        }
    }

    @Test fun completeEnrollmentAfterRestart() {
        assertNotNull(store().load())
        assertNull(SecondaryEnrollment.readyIdentity(context))
        try {
            SecondaryEnrollment.complete(context, ex("roster-before.json").readText())
            fail("The previous valid roster does not authorize the new installation")
        } catch (expected: IllegalArgumentException) {
            assertNull(SecondaryEnrollment.readyIdentity(context))
        }
        val identity = SecondaryEnrollment.complete(context, ex("roster.json").readText())
        assertEquals(identity.user.userId, SecondaryEnrollment.readyIdentity(context)!!.user.userId)
        // Re-selecting the same file must be harmless.
        assertEquals(identity.user.userId, SecondaryEnrollment.complete(context, ex("roster.json").readText()).user.userId)
        assertFalse(AndroidKeystoreIdentityStore(context).exists())
    }

    @Test fun restoreAvailableHistoryAndSend() {
        val identity = SecondaryEnrollment.readyIdentity(context)!!
        val (_, code) = RecoveryBackupCodeCodec.decode(ex("recovery-code.txt").readText())
        val secret = RecoveryBackupCrypto.open(RecoveryBackupCodec.parseCanonical(ex("recovery-backup.json").readText()), code)
        val raw = ex("vault-package.json").readText()
        val pkg = HistoryVaultArchivePackageCodec.parseCanonical(raw)
        val descriptor = HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
        val repo = AndroidMessengerRepositoryProvider.get(context)
        val restored = HistoryVaultRecoveryCoordinator(repo, identity).restore(raw, secret, 1_000)
        (restored.documentProvider as HistoryVaultDocumentProvider).close()
        AndroidHistoryVaultPackageStore(context).saveActive(raw)
        AndroidHistoryVaultSecretStore(context).save(descriptor.vaultId, secret)
        val expected = JSONArray(ex("expected-history.json").readText())
        val provider = RestoredHistory.open(context)!!
        try {
            val service = LocalMessengerService(repo, identity, historicalDocumentProvider = provider)
            val conversation = ex("conversation-id.txt").readText()
            val timeline = service.timeline(conversation)
            assertEquals(expected.length(), timeline.size)
            for (i in timeline.indices) {
                assertEquals(expected.getJSONObject(i).getString("id"), timeline[i].messageId)
                assertEquals(expected.getJSONObject(i).getString("document"), EidogramCanonical.contentHash(timeline[i].document))
                // The original application's vault export omitted captions. Keep its installation.
                assertEquals("", timeline[i].caption)
            }
            assertEquals("Собеседник до обновления", service.contactSummaries().single().alias)
            assertEquals("Переписка до обновления", service.conversationSummaries().single().title)
            val message = service.send(conversation, timeline.first().document, 1_500, caption = "Сообщение после перехода")
            ex("new-message.json").writeText(MessageCanonical.envelopeJson(message))
        } finally {
            provider.close()
        }
    }

    @Test fun reopenMigratedProfile() {
        val identity = SecondaryEnrollment.readyIdentity(context)!!
        assertEquals("Профиль до обновления", EidoProfileStore(context).load()!!.nickname)
        assertFalse(AndroidKeystoreIdentityStore(context).exists())
        val provider = RestoredHistory.open(context)!!
        try {
            val service = LocalMessengerService(AndroidMessengerRepositoryProvider.get(context), identity, historicalDocumentProvider = provider)
            assertEquals(3, service.timeline(ex("conversation-id.txt").readText()).size)
        } finally {
            provider.close()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            compose.onNodeWithText("Собеседник до обновления").assertIsDisplayed()
            assertEquals(identity.user.userId, SecondaryEnrollment.readyIdentity(context)!!.user.userId)
        }
    }

    @Test fun independentNetworkIdentity() {
        val original = JSONObject(ex("original-network.json").readText())
        val ports = EidoTor.portsFor(context.packageName)
        val oldPorts = setOf(original.getInt("localPort"), original.getInt("socksPort"), original.getInt("controlPort"))
        assertTrue(setOf(ports.local, ports.socks, ports.control).intersect(oldPorts).isEmpty())
        val key = EidoOnionNode.derivedKey(context)!!
        val oldSecret = AndroidHistoryVaultSecretStore(context)
            .load(AndroidHistoryVaultPackageStore(context).activeVaultId()!!)!!.copyBytes()
        try {
            assertEquals("The existing primary's address derivation must stay unchanged",
                original.getString("onionKeyHash"), HexSha256.ofUtf8(OnionServiceKey.fromRecoverySecret(oldSecret)))
        } finally {
            oldSecret.fill(0)
        }
        assertNotEquals(original.getString("onionKeyHash"), HexSha256.ofUtf8(key))
        assertEquals("Importing the original vault must not change the parallel device address",
            ex("secondary-onion-key-hash.txt").readText(), HexSha256.ofUtf8(key))
        assertEquals(key, EidoOnionNode.derivedKey(context))
        ActivityScenario.launch(MainActivity::class.java).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(ports, EidoTor.currentPorts)
        }
    }

    @Test fun unreadableArchiveIsReportedWithoutResettingProfile() {
        val id = AndroidHistoryVaultPackageStore(context).activeVaultId()!!
        val secrets = AndroidHistoryVaultSecretStore(context)
        val secret = secrets.load(id)!!
        val owner = SecondaryEnrollment.readyIdentity(context)!!.user.userId
        secrets.delete(id)
        try {
            try {
                RestoredHistory.open(context)
                fail("A missing archive key must not be reported as an empty history")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("истории"))
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                compose.onNodeWithText("Не удалось открыть восстановленную историю").assertIsDisplayed()
            }
            assertEquals(owner, SecondaryEnrollment.readyIdentity(context)!!.user.userId)
            assertFalse(AndroidKeystoreIdentityStore(context).exists())
        } finally {
            secrets.save(id, secret)
        }
    }
}
