package org.eidolang.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.vault.*
import org.eidolang.feature.home.*
import org.eidolang.feature.onboarding.SecondaryEnrollment
import org.eidolang.transport.libtorrent4j.AndroidArchivePublisherStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

@TwoDeviceOnly
class AccountRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "org.eidolang.app.recoverycheck") { "Full-removal checks must never target the owner's app" }
    }
    private fun exchange(name: String) = File(context.cacheDir, "recovery-test/$name").apply { parentFile!!.mkdirs() }
    private val password get() = "fixture-only-recovery-password-2026".toCharArray()
    private val avatar get() = android.util.Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZWQAAAAASUVORK5CYII=", 0)
    private fun bundle(id: DevicePrivateCrypto) = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(id.user, id.certificate))

    @Test fun seedRecoverablePrimaryAndExport() = seed(false)
    @Test fun seedLegacyHardwarePrimaryAndExport() = seed(true)

    private fun seed(legacy: Boolean) {
        val ctx = context
        assertFalse(AndroidKeystoreIdentityStore(ctx).hasSavedProfile())
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(ks.aliases().hasMoreElements())
        if (legacy) {
            for (alias in listOf(AndroidKeystoreIdentityStore.ROOT_ALIAS, AndroidKeystoreIdentityStore.DEVICE_SIGN_ALIAS)) {
                KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256).build())
                }.generateKeyPair()
            }
            KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(AndroidKeystoreIdentityStore.DEVICE_ENC_ALIAS, KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
            }.generateKeyPair()
        }
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        if (!legacy) {
            val original = bundle(identity)
            ks.deleteEntry(AndroidKeystoreIdentityStore.DEVICE_SIGN_ALIAS)
            assertEquals(original, bundle(AndroidKeystoreIdentityStore(ctx).loadIfPresent()!!))
        }
        EidoProfileStore(ctx).save(EidoProfileStore.Profile("Профиль после полного удаления", avatar))
        val repo = AndroidMessengerRepositoryProvider.get(ctx)
        val service = LocalMessengerService(repo, identity)
        val peer = JvmPrivateIdentity.generate()
        service.importContact(bundle(peer), "Сохранённый контакт", 100)
        ContactAvatarStore(ctx).put(peer.user.userId, avatar)
        ContactOnionStore(ctx).put(peer.user.userId, "a".repeat(56) + ".onion")
        val conversation = service.createConversation(listOf(peer.user.userId), title = "Исходная переписка", createdAtMs = 200)
        val document = EidogramDocumentV1(listOf(EidogramAction.Add(0, "g000001", "circle.red.m", FixedTransform(400_000, 500_000), 0)))
        val first = service.send(conversation.conversationId, document, 300, caption = "Исходящий текст сохранён полностью")
        val peerService = LocalMessengerService(InMemoryMessengerRepository(), peer)
        peerService.importContact(bundle(identity), "Владелец", 100)
        peerService.importConversation(ConversationCanonical.descriptorJson(conversation), "Диалог", 200)
        val second = peerService.send(conversation.conversationId, document, 400, caption = "Входящий текст сохранён полностью")
        service.admitIncoming(MessageCanonical.envelopeJson(second), 500)
        EidogramCatalogStore(ctx).save(EidogramCatalogStore.Entry("saved-eidogram", 600, "Моя сохранённая эйдограмма", document, emptyList()))
        val publisher = AndroidArchivePublisherStore(ctx).ensure(identity)
        val cardHash = OnionStore(ctx).putCard(ContactCard.write(identity, publisher.certificate,
            onion = "b".repeat(56) + ".onion", context = ctx).toByteArray())
        val expected = JSONObject().put("legacy", legacy).put("original_bundle", bundle(identity))
            .put("conversation", conversation.conversationId).put("peer", peer.user.userId)
            .put("first", first.messageId).put("second", second.messageId)
            .put("document_hash", EidogramCanonical.contentHash(document))
            .put("publisher_key", B64Url.encode(publisher.publicKeyRaw))
            .put("original_card_hash", cardHash)
        val secret = AndroidBootstrapRecoverySecret.forOnion(ctx).copyBytes()
        try { expected.put("onion_key", OnionServiceKey.fromRecoverySecret(secret)) } finally { secret.fill(0) }
        val plain = AndroidAccountRecovery.snapshot(ctx, identity)
        try {
            val validation = AndroidAccountRecovery.validate(plain)
            try {
                expected.put("restored_bundle", validation.identity.bundleCanonical)
                assertEquals(!legacy, validation.identity.rootPrivateB64 != null)
            } finally { validation.close() }
            exchange("backup.eidobackup").writeBytes(AccountBackupCipher.encrypt(plain, password))
        } finally { plain.fill(0) }
        exchange("expected.json").writeText(expected.toString())
    }

    @Test fun rejectWrongPasswordAndEditedFileBeforeAnyKeyOrProfileWrite() {
        val ctx = context
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(ks.aliases().hasMoreElements())
        val bytes = exchange("backup.eidobackup").readBytes()
        assertTrue(runCatching { AndroidAccountRecovery.restore(ctx, bytes, "wrong-password".toCharArray()) }.isFailure)
        assertFalse(ks.aliases().hasMoreElements())
        val changed = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { AndroidAccountRecovery.restore(ctx, changed, password) }.isFailure)
        assertFalse(ks.aliases().hasMoreElements())
        assertFalse(File(ctx.filesDir, "eidolang-profile-v1.bin").exists())
    }

    @Test fun restoreAfterFullRemovalAndVerifyEveryPart() {
        val ctx = context
        val expected = JSONObject(exchange("expected.json").readText())
        val identity = AndroidAccountRecovery.restore(ctx, exchange("backup.eidobackup").readBytes(), password)
        verify(identity, expected)
        assertNull(AndroidAccountRecovery.resumePending(ctx))
        // Replacing an already restored account is forbidden, even with a valid backup.
        assertTrue(runCatching { AndroidAccountRecovery.restore(ctx, exchange("backup.eidobackup").readBytes(), password) }.isFailure)
        verify(identity, expected)
    }

    @Test fun reopenRestoredProfileAfterProcessRestart() {
        val ctx = context
        val expected = JSONObject(exchange("expected.json").readText())
        val id = AndroidKeystoreIdentityStore(ctx).loadIfPresent() ?: SecondaryEnrollment.readyIdentity(ctx)!!
        verify(id, expected)
    }

    @Test fun restoreLatestAutomaticSnapshot() {
        val ctx = context
        AndroidAccountRecovery.restore(ctx, exchange("automatic.eidobackup").readBytes(), password)
        verifyAutomaticSnapshot()
    }

    @Test fun verifyAutomaticSnapshotAfterRestart() = verifyAutomaticSnapshot()

    @Test fun restoreProvidedAccountBackupAndCompareEverySavedFileAndMessage() {
        val ctx = context
        val chars = exchange("provided-password.key").readText().removeSuffix("\n").toCharArray()
        val encoded = exchange("provided.eidobackup").readBytes()
        val plain = AccountBackupCipher.decrypt(encoded, chars)
        val expected = try { AndroidAccountRecovery.validate(plain) } finally { plain.fill(0) }
        try {
            val restored = AndroidAccountRecovery.restore(ctx, encoded, chars)
            assertEquals(expected.identity.bundleCanonical, bundle(restored))
            val afterRaw = AndroidAccountRecovery.snapshot(ctx, restored)
            val after = try { AndroidAccountRecovery.validate(afterRaw) } finally { afterRaw.fill(0) }
            try {
                assertEquals(expected.files.keys, after.files.keys)
                expected.files.forEach { (path, data) -> assertArrayEquals(path, data, after.files.getValue(path)) }
                val oldProvider = HistoryVaultDocumentProvider(expected.history, expected.secret)
                val newProvider = HistoryVaultDocumentProvider(after.history, after.secret)
                try {
                    val ids = HistoryVaultArchivePackageCodec.parseCanonical(expected.history).entryCanonicalJson
                        .map { HistoryVaultEntryCodec.parseCanonical(it).body.messageId }.sorted()
                    assertEquals(expected.messageCount, after.messageCount)
                    ids.forEach { id ->
                        assertEquals(oldProvider.messageEnvelopeFor(id), newProvider.messageEnvelopeFor(id))
                        val a = oldProvider.payloadFor(id)!!; val b = newProvider.payloadFor(id)!!
                        assertEquals(a.caption, b.caption)
                        assertEquals(EidogramCanonical.contentHash(a.document), EidogramCanonical.contentHash(b.document))
                    }
                    println("PROVIDED_BACKUP_RESTORED messages=${ids.size} files=${expected.files.size}")
                } finally { oldProvider.close(); newProvider.close() }
            } finally { after.close() }
        } finally { expected.close(); chars.fill('\u0000'); exchange("provided-password.key").delete() }
    }

    private fun verifyAutomaticSnapshot() {
        val ctx = context
        val plain = AccountBackupCipher.decrypt(exchange("automatic.eidobackup").readBytes(), password)
        val data = try { AndroidAccountRecovery.validate(plain) } finally { plain.fill(0) }
        try {
            val id = AndroidAccountRecovery.currentIdentity(ctx)!!
            assertEquals(data.identity.bundleCanonical, bundle(id))
            val profile = EidoProfileStore(ctx).load()!!
            val expectedProfile = JSONObject(data.files.getValue("eidolang-profile-v1.bin").toString(Charsets.UTF_8))
            assertEquals(expectedProfile.getString("nickname"), profile.nickname)
            assertArrayEquals(android.util.Base64.decode(expectedProfile.getString("avatar_png_b64"), 0), profile.avatarPng)
            val provider = RestoredHistory.open(ctx)!!
            try {
                val service = LocalMessengerService(AndroidMessengerRepositoryProvider.get(ctx), id, historicalDocumentProvider = provider)
                val items = service.conversationSummaries().flatMap { service.timeline(it.conversationId) }
                assertEquals(data.messageCount, items.size)
                assertTrue(items.any { it.caption == "Новое сообщение после настройки копии" })
                assertEquals("Сохранённый контакт", service.contactSummaries().single().alias)
            } finally { provider.close() }
        } finally { data.close() }
    }

    private fun verify(identity: DevicePrivateCrypto, expected: JSONObject) {
        val ctx = context
        assertEquals(expected.getString("restored_bundle"), bundle(identity))
        val original = IdentityParser.parsePublicBundleCanonical(expected.getString("original_bundle"))
        assertEquals(original.user, identity.user)
        if (!expected.getBoolean("legacy")) {
            assertEquals(original.device, identity.certificate)
            val challenge = "restored-root-authority".toByteArray()
            assertTrue(JcaCrypto.verify(PublicKeyCodec.ec(identity.user.rootSigningPublicKeyB64), challenge,
                AndroidRootAuthority().signRoot(challenge)))
        } else assertNotNull(SecondaryEnrollment.readyIdentity(ctx))
        val profile = EidoProfileStore(ctx).load()!!
        assertEquals("Профиль после полного удаления", profile.nickname)
        assertArrayEquals(avatar, profile.avatarPng)
        assertArrayEquals(avatar, ContactAvatarStore(ctx).get(expected.getString("peer")))
        assertEquals("a".repeat(56) + ".onion", ContactOnionStore(ctx).get(expected.getString("peer")))
        val entry = EidogramCatalogStore(ctx).list().single()
        assertEquals("Моя сохранённая эйдограмма", entry.caption)
        assertEquals(expected.getString("document_hash"), EidogramCanonical.contentHash(entry.document))
        val raw = AndroidHistoryVaultPackageStore(ctx).loadActiveCanonical()!!
        val pkg = HistoryVaultArchivePackageCodec.parseCanonical(raw)
        val descriptor = HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
        val secret = AndroidHistoryVaultSecretStore(ctx).load(descriptor.vaultId)!!
        val provider = HistoryVaultDocumentProvider(raw, secret)
        try {
            val service = LocalMessengerService(AndroidMessengerRepositoryProvider.get(ctx), identity, historicalDocumentProvider = provider)
            assertEquals("Сохранённый контакт", service.contactSummaries().single().alias)
            assertEquals("Исходная переписка", service.conversationSummaries().single().title)
            val messages = service.timeline(expected.getString("conversation"))
            assertEquals(2, messages.size)
            assertEquals(listOf(expected.getString("first"), expected.getString("second")), messages.map { it.messageId })
            assertEquals(listOf("Исходящий текст сохранён полностью", "Входящий текст сохранён полностью"), messages.map { it.caption })
            messages.forEach { assertEquals(expected.getString("document_hash"), EidogramCanonical.contentHash(it.document)) }
            // Force the independent archive path even for a restored primary, checking captions.
            messages.forEach { assertEquals(it.caption, provider.payloadFor(it.messageId)!!.caption) }
        } finally { provider.close() }
        val rawSecret = AndroidBootstrapRecoverySecret.forOnion(ctx).copyBytes()
        try { assertEquals(expected.getString("onion_key"), OnionServiceKey.fromRecoverySecret(rawSecret)) }
        finally { rawSecret.fill(0) }
        assertEquals(expected.getString("publisher_key"), B64Url.encode(AndroidArchivePublisherStore(ctx).ensure(identity).publicKeyRaw))
        expected.optString("original_card_hash").takeIf { it.isNotEmpty() }?.let {
            assertEquals(it, HexSha256.of(OnionStore(ctx).card(it)!!))
        }
    }
}
