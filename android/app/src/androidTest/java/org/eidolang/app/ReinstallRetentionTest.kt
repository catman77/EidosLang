package org.eidolang.app

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.*
import org.eidolang.core.message.ConversationCanonical
import org.eidolang.core.message.MessageCanonical
import org.eidolang.core.model.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.vault.AndroidBootstrapRecoverySecret
import org.eidolang.feature.home.EidoProfileStore
import org.eidolang.feature.home.OnionServiceKey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore

/** Only tools/reinstall-retention.py may drive this fixture, in its separate application ID. */
@TwoDeviceOnly
class ReinstallRetentionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "org.eidolang.app.retentioncheck") {
            "Reinstall tests cannot run against the owner's application"
        }
    }
    private val expected get() = File(context.filesDir, "reinstall-retention-expected.json")
    private fun repository() = AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context))
    private fun bundle(identity: DevicePrivateCrypto) = IdentityCanonical.publicBundleJson(
        PublicIdentityBundleV1(identity.user, identity.certificate))
    private val avatar get() = android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZWQAAAAASUVORK5CYII=", 0)

    @Test fun seedOnlyEmptyCheckInstallation() {
        assertFalse(expected.exists())
        val store = AndroidKeystoreIdentityStore(context)
        assertFalse(store.exists())
        assertFalse(store.hasSavedProfile())
        val identity = store.ensure()
        EidoProfileStore(context).save(EidoProfileStore.Profile("Проверка сохранения — Samsung", avatar))
        repository().use { repo ->
            val service = LocalMessengerService(repo, identity)
            val roster = DeviceRosterAuthority.issue(AndroidRootAuthority(), null,
                listOf(identity.certificate.deviceId), emptyList())
            service.importDeviceRoster(DeviceRosterCanonical.json(roster), 100)
            val peer = JvmPrivateIdentity.generate()
            service.importContact(bundle(peer), "Контакт до переустановки", 200)
            val conversation = service.createConversation(listOf(peer.user.userId),
                title = "Диалог до переустановки", createdAtMs = 300)
            val document = EidogramDocumentV1(listOf(EidogramAction.Add(
                0, "g000001", "circle.red.m", FixedTransform(390_000, 500_000), 0)))
            service.send(conversation.conversationId, document, 400, caption = "Исходящее до переустановки")
            val peerService = LocalMessengerService(InMemoryMessengerRepository(), peer)
            peerService.importContact(bundle(identity), "Владелец", 200)
            peerService.importConversation(ConversationCanonical.descriptorJson(conversation), "Диалог", 300)
            val reply = peerService.send(conversation.conversationId, document, 500,
                caption = "Входящее до переустановки")
            service.admitIncoming(MessageCanonical.envelopeJson(reply), 600)
            val items = JSONArray()
            service.timeline(conversation.conversationId).forEach { item ->
                items.put(JSONObject().put("id", item.messageId).put("caption", item.caption)
                    .put("document", EidogramCanonical.contentHash(item.document)))
            }
            val secret = AndroidBootstrapRecoverySecret.ensure(context).copyBytes()
            val snapshot = JSONObject().put("uid", Process.myUid()).put("bundle", bundle(identity))
                .put("conversation", conversation.conversationId).put("messages", items)
                .put("onion_key", OnionServiceKey.fromRecoverySecret(secret))
            secret.fill(0)
            expected.writeText(snapshot.toString())
        }
    }

    @Test fun verifyRetainedProfileContactsAndCompleteMessages() {
        val before = JSONObject(expected.readText())
        val identity = AndroidKeystoreIdentityStore(context).loadIfPresent()!!
        assertEquals(before.getInt("uid"), Process.myUid())
        assertEquals(before.getString("bundle"), bundle(identity))
        val profile = EidoProfileStore(context).load()!!
        assertEquals("Проверка сохранения — Samsung", profile.nickname)
        assertArrayEquals(avatar, profile.avatarPng)
        val challenge = "retained-private-identity".toByteArray()
        assertTrue(JcaCrypto.verify(PublicKeyCodec.ec(identity.certificate.body.signingPublicKeyB64),
            challenge, identity.signMessage(challenge)))
        val secret = AndroidBootstrapRecoverySecret.ensure(context).copyBytes()
        try { assertEquals(before.getString("onion_key"), OnionServiceKey.fromRecoverySecret(secret)) }
        finally { secret.fill(0) }
        repository().use { repo ->
            val service = LocalMessengerService(repo, identity)
            assertEquals("Контакт до переустановки", service.contactSummaries().single().alias)
            assertEquals("Диалог до переустановки", service.conversationSummaries().single().title)
            val items = service.timeline(before.getString("conversation"))
            val saved = before.getJSONArray("messages")
            assertEquals(2, saved.length())
            assertEquals(saved.length(), items.size)
            items.forEachIndexed { index, item ->
                assertEquals(saved.getJSONObject(index).getString("id"), item.messageId)
                assertEquals(saved.getJSONObject(index).getString("caption"), item.caption)
                assertEquals(saved.getJSONObject(index).getString("document"), EidogramCanonical.contentHash(item.document))
            }
        }
    }

    @Test fun unreadableProfileIsNotTreatedAsANewInstallation() {
        val file = File(context.filesDir, "eidolang-profile-v1.bin")
        val saved = file.readBytes()
        try {
            file.writeText("invalid-profile-ciphertext")
            assertTrue(runCatching { EidoProfileStore(context).load() }.isFailure)
            assertEquals("invalid-profile-ciphertext", file.readText())
        } finally { file.writeBytes(saved) }
    }

    @Test fun missingKeysNeverRegenerateOverSavedData() {
        // This destructive fault injection is guarded by the separate application ID above.
        val ctx = context
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val profileFile = File(ctx.filesDir, "eidolang-profile-v1.bin")
        val profile = profileFile.readBytes()
        ks.deleteEntry(AndroidLocalSecretBox.ALIAS)
        assertTrue(runCatching { EidoProfileStore(ctx).load() }.isFailure)
        assertFalse(ks.containsAlias(AndroidLocalSecretBox.ALIAS))
        assertArrayEquals(profile, profileFile.readBytes())
        val vaultFile = File(ctx.filesDir, "history-vault-secret-" + "0".repeat(64) + ".bin")
        val vault = vaultFile.readBytes()
        val vaultAlias = "eidolang.history-vault-secret-wrap.v1"
        ks.deleteEntry(vaultAlias)
        assertTrue(runCatching { AndroidBootstrapRecoverySecret.ensure(ctx) }.isFailure)
        assertFalse(ks.containsAlias(vaultAlias))
        assertArrayEquals(vault, vaultFile.readBytes())
        // An unreadable recovery copy must never be replaced by a newly generated identity.
        ks.deleteEntry(AndroidRecoverableKeyStore.WRAP_ALIAS)
        ks.deleteEntry(AndroidKeystoreIdentityStore.DEVICE_SIGN_ALIAS)
        val store = AndroidKeystoreIdentityStore(ctx)
        assertTrue(runCatching { store.loadIfPresent() }.isFailure)
        assertTrue(runCatching { store.ensure() }.isFailure)
        assertFalse(ks.containsAlias(AndroidKeystoreIdentityStore.DEVICE_SIGN_ALIAS))
        ks.deleteEntry(AndroidKeystoreIdentityStore.ROOT_ALIAS)
        ks.deleteEntry(AndroidKeystoreIdentityStore.DEVICE_ENC_ALIAS)
        assertTrue(runCatching { store.ensure() }.isFailure)
        assertFalse(store.exists())
        assertArrayEquals(profile, profileFile.readBytes())
    }
}
