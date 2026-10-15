package org.eidolang.app

import android.graphics.Bitmap
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.message.ConversationCanonical
import org.eidolang.core.message.MessageCanonical
import org.eidolang.core.model.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.feature.home.EidoProfileStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Run seed only on a deliberately emptied test installation, then verify after adb install -r. */
@TwoDeviceOnly
class PublicReleaseUpdateTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val expected get() = File(context.filesDir, "public-release-update-check.json")
    private fun repo() = AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context))
    private fun owner(identity: DevicePrivateCrypto) = IdentityCanonical.publicBundleJson(
        PublicIdentityBundleV1(identity.user, identity.certificate)
    )

    @Test fun seedFreshProfileAndMessages() {
        val store = AndroidKeystoreIdentityStore(context)
        assertFalse("Never replace an existing user's profile", store.exists())
        val identity = store.ensure()
        val avatar = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xff5379c9.toInt())
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            bitmap.recycle()
            output.toByteArray()
        }
        EidoProfileStore(context).save(EidoProfileStore.Profile("Проверка обновления GitHub", avatar))
        repo().use { repository ->
            val service = LocalMessengerService(repository, identity)
            val roster = DeviceRosterAuthority.issue(AndroidRootAuthority(), null,
                listOf(identity.certificate.deviceId), emptyList())
            service.importDeviceRoster(DeviceRosterCanonical.json(roster), 100)
            val peer = JvmPrivateIdentity.generate()
            service.importContact(owner(peer), "Тестовый собеседник", 200)
            val conversation = service.createConversation(listOf(peer.user.userId),
                title = "Проверка сохранности переписки", createdAtMs = 300)
            val document = EidogramDocumentV1(listOf(EidogramAction.Add(
                0, "g000001", "circle.red.m", FixedTransform(390_000, 500_000), 0)))
            service.send(conversation.conversationId, document, 400, caption = "Текст до обновления — исходящее")
            val peerService = LocalMessengerService(InMemoryMessengerRepository(), peer)
            peerService.importContact(owner(identity), "Владелец", 200)
            peerService.importConversation(ConversationCanonical.descriptorJson(conversation), "Диалог", 300)
            val incoming = peerService.send(conversation.conversationId, document, 500,
                caption = "Текст до обновления — входящее")
            service.admitIncoming(MessageCanonical.envelopeJson(incoming), 600)
            val messages = JSONArray()
            service.timeline(conversation.conversationId).forEach {
                messages.put(JSONObject().put("id", it.messageId).put("caption", it.caption)
                    .put("document", EidogramCanonical.contentHash(it.document)))
            }
            assertEquals(2, messages.length())
            expected.writeText(JSONObject().put("uid", Process.myUid())
                .put("owner", owner(identity)).put("avatar", HexSha256.of(avatar))
                .put("conversation", conversation.conversationId).put("messages", messages).toString())
        }
    }

    @Test fun profileAndCompleteMessagesSurviveOrdinaryUpdate() {
        val snapshot = JSONObject(expected.readText())
        val identity = AndroidKeystoreIdentityStore(context).loadIfPresent()!!
        assertEquals(snapshot.getInt("uid"), Process.myUid())
        assertEquals(snapshot.getString("owner"), owner(identity))
        val profile = EidoProfileStore(context).load()!!
        assertEquals("Проверка обновления GitHub", profile.nickname)
        assertEquals(snapshot.getString("avatar"), HexSha256.of(profile.avatarPng!!))
        repo().use { repository ->
            // LocalMessengerService registers its own device in its constructor. Verification must
            // read the phone's repository without issuing that write, so run it against a mirror.
            val mirror = InMemoryMessengerRepository().apply {
                repository.contactDevices().forEach(::putContact)
                repository.ownDevices().forEach(::putOwnDevice)
                repository.deviceRosters().forEach(::putDeviceRoster)
                repository.keyGrants().forEach(::putKeyGrant)
                repository.conversations().forEach { conversation ->
                    putConversation(conversation)
                    repository.messages(conversation.conversationId).forEach(::insertMessage)
                }
            }
            val service = LocalMessengerService(mirror, identity)
            assertEquals("Тестовый собеседник", service.contactSummaries().single().alias)
            val conversation = snapshot.getString("conversation")
            assertEquals("Проверка сохранности переписки", service.conversationSummaries().single().title)
            val timeline = service.timeline(conversation)
            val messages = snapshot.getJSONArray("messages")
            assertTrue(timeline.size >= messages.length())
            timeline.take(messages.length()).forEachIndexed { i, item ->
                assertEquals(messages.getJSONObject(i).getString("id"), item.messageId)
                assertEquals(messages.getJSONObject(i).getString("caption"), item.caption)
                assertEquals(messages.getJSONObject(i).getString("document"), EidogramCanonical.contentHash(item.document))
            }
            // Verify possession of the original private key without modifying the transcript.
            val challenge = "public-release-update-check".toByteArray(Charsets.UTF_8)
            assertTrue(JcaCrypto.verify(PublicKeyCodec.ec(identity.certificate.body.signingPublicKeyB64),
                challenge, identity.signMessage(challenge)))
            assertTrue(JcaCrypto.verify(PublicKeyCodec.ec(identity.user.rootSigningPublicKeyB64),
                challenge, AndroidRootAuthority().signRoot(challenge)))
        }
    }
}
