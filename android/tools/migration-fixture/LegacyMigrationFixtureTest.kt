package org.eidolang.app

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.archive.JcaArchivePublisher
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.*
import org.eidolang.core.message.ConversationCanonical
import org.eidolang.core.model.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.vault.*
import org.eidolang.feature.home.ContactCard
import org.eidolang.feature.home.EidoProfileStore
import org.eidolang.feature.home.EidoOnionNode
import org.eidolang.feature.home.EidoTor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Copied into a reconstructed legacy source fixture, not the actual published R22.1 APK. */
@TwoDeviceOnly
class LegacyMigrationFixtureTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun ex(name: String) = File(context.filesDir, "exchange/$name").apply { parentFile!!.mkdirs() }
    private fun repo() = AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context))

    @Test fun createExistingProfileAndTranscript() {
        assertEquals("org.eidolang.app", context.packageName)
        val identity = AndroidKeystoreIdentityStore(context).ensure()
        val owner = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(identity.user, identity.certificate))
        ex("original-owner.json").writeText(owner)
        val avatar = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=", Base64.DEFAULT)
        val profile = EidoProfileStore.Profile("Профиль до обновления", avatar)
        EidoProfileStore(context).save(profile)
        ex("owner-card.json").writeText(ContactCard.write(identity, JcaArchivePublisher.create(identity).certificate, profile.nickname, avatar))
        repo().use { repository ->
            val service = LocalMessengerService(repository, identity)
            val root = AndroidRootAuthority()
            val roster = DeviceRosterAuthority.issue(root, null, listOf(identity.certificate.deviceId), emptyList())
            service.importDeviceRoster(DeviceRosterCanonical.json(roster), 100)
            ex("roster-before.json").writeText(DeviceRosterCanonical.json(roster))
            val peer = JvmPrivateIdentity.generate()
            val peerBundle = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(peer.user, peer.certificate))
            service.importContact(peerBundle, "Собеседник до обновления", 200)
            val conversation = service.createConversation(listOf(peer.user.userId), title = "Переписка до обновления", createdAtMs = 300)
            val document = EidogramDocumentV1(listOf(EidogramAction.Add(0, "g000001", "circle.red.m", FixedTransform(390_000, 500_000), 0)))
            service.send(conversation.conversationId, document, 400, caption = "Исходящая текстовая подпись")
            val peerRepository = InMemoryMessengerRepository()
            val peerService = LocalMessengerService(peerRepository, peer)
            peerService.importContact(owner, "Владелец", 200)
            peerService.importConversation(ConversationCanonical.descriptorJson(conversation), "Диалог", 300)
            val incoming = peerService.send(conversation.conversationId, document, 500, caption = "Входящая текстовая подпись")
            service.admitIncoming(org.eidolang.core.message.MessageCanonical.envelopeJson(incoming), 600)
            ex("conversation-id.txt").writeText(conversation.conversationId)
            val expected = JSONArray()
            service.timeline(conversation.conversationId).forEach {
                expected.put(JSONObject().put("id", it.messageId).put("document", EidogramCanonical.contentHash(it.document)).put("caption", it.caption))
            }
            ex("expected-history.json").writeText(expected.toString())
            assertEquals(2, expected.length())
        }
    }

    @Test fun authorizeNewInstallationAndExportHistory() {
        val identity = AndroidKeystoreIdentityStore(context).ensure()
        val root = AndroidRootAuthority()
        val request = EnrollmentCanonical.parseCanonical(ex("enrollment-request.json").readText())
        val certificate = DeviceEnrollmentAuthority.authorize(root, request)
        val bundle = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(root.user, certificate))
        ex("authorized-bundle.json").writeText(bundle)
        repo().use { repository ->
            val service = LocalMessengerService(repository, identity)
            service.importOwnDevice(bundle, 700)
            val roster = DeviceRosterAuthority.issue(root, service.latestRoster(root.user.userId), listOf(identity.certificate.deviceId, certificate.deviceId), emptyList())
            val rosterRaw = DeviceRosterCanonical.json(roster)
            service.importDeviceRoster(rosterRaw, 800)
            ex("roster.json").writeText(rosterRaw)
            val secret = AndroidBootstrapRecoverySecret.ensure(context)
            val vault = HistoryVaultController.create(identity, secret)
            vault.createEpoch(roster.body.activeDeviceIds)
            assertEquals(2, vault.archiveAll(repository, service))
            ex("vault-package.json").writeText(vault.exportPackage(repository))
            val backup = RecoveryBackupCrypto.create(vault.descriptor.vaultId, secret)
            ex("recovery-backup.json").writeText(RecoveryBackupCodec.json(backup.backup))
            ex("recovery-code.txt").writeText(RecoveryBackupCodeCodec.encode(vault.descriptor.vaultId, backup.recoveryCode))
        }
    }

    @Test fun verifyOriginalProfileAndAcceptNewMessage() {
        val identity = AndroidKeystoreIdentityStore(context).loadIfPresent()!!
        assertEquals(ex("original-owner.json").readText(), IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(identity.user, identity.certificate)))
        assertEquals("Профиль до обновления", EidoProfileStore(context).load()!!.nickname)
        repo().use { repository ->
            val service = LocalMessengerService(repository, identity)
            val conversation = ex("conversation-id.txt").readText()
            val before = service.timeline(conversation)
            val expected = JSONArray(ex("expected-history.json").readText())
            assertEquals(2, before.size)
            for (i in 0 until expected.length()) {
                assertEquals(expected.getJSONObject(i).getString("id"), before[i].messageId)
                assertEquals(expected.getJSONObject(i).getString("caption"), before[i].caption)
            }
            service.admitIncoming(ex("new-message.json").readText(), 2_000)
            assertEquals("Сообщение после перехода", service.timeline(conversation).last().caption)
        }
    }

    @Test fun exportOriginalNetworkIdentity() {
        ex("original-network.json").writeText(JSONObject()
            .put("onionKeyHash", HexSha256.ofUtf8(EidoOnionNode.derivedKey(context)!!))
            .put("localPort", EidoTor.LOCAL_PORT)
            .put("socksPort", EidoTor.SOCKS_PORT)
            .put("controlPort", EidoTor.CONTROL_PORT).toString())
    }
}
