package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.repository.*
import org.eidolang.core.vault.*
import org.eidolang.feature.home.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Host transfers only this synthetic encrypted fixture between two freshly created emulators. */
@TwoDeviceOnly
class GroupAccountBackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(android.os.Build.MODEL.startsWith("sdk_gphone")) { "Synthetic group account tests require an emulator" }
        check(InstrumentationRegistry.getArguments().getString("groupFixture") == "fresh-emulator")
    }
    private val password get() = "synthetic-group-backup-password-2026".toCharArray()
    private fun bundle(id: DevicePrivateCrypto) = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(id.user, id.certificate))
    private fun file(name: String) = context.cacheDir.resolve("group-fixture/$name").apply { parentFile!!.mkdirs() }

    @Test fun prepareIndependentGroupBackup() {
        val ctx = context
        assertFalse(AndroidKeystoreIdentityStore(ctx).hasSavedProfile())
        val identity = AndroidKeystoreIdentityStore(ctx).ensure()
        EidoProfileStore(ctx).save(EidoProfileStore.Profile("Групповой тест Android", null))
        val repo = AndroidMessengerRepositoryProvider.get(ctx)
        val service = LocalMessengerService(repo, identity)
        val bob = JvmPrivateIdentity.generate(); val charlie = JvmPrivateIdentity.generate()
        service.importContact(bundle(bob), "Боб", 1); service.importContact(bundle(charlie), "Чарли", 1)
        val publisher = org.eidolang.transport.libtorrent4j.AndroidArchivePublisherStore(ctx).ensure(identity)
        val peers = listOf(bob, charlie)
        peers.forEach { peer -> ContactPublisherStore(ctx).put(peer.user.userId, org.eidolang.core.archive.JcaArchivePublisher.create(peer).certificate) }
        val groups = groupChatService(ctx, service)
        val group = groups.create("Три автора", peers.map { it.user.userId })
        val document = EidogramDocumentV1(listOf(EidogramAction.Add(0, "g1", "square.blue.m", FixedTransform(500_000, 500_000), 0)))
        val first = groups.send(group.groupId, document, "Сообщение создателя")
        // Stage every peer's independent outer envelope, but intentionally leave publication pending.
        groups.pending().forEach { (id, pending) -> groups.stage(id, pending.id) }
        peers.forEachIndexed { index, peer ->
            val peerRepo = InMemoryMessengerRepository()
            val peerService = LocalMessengerService(peerRepo, peer)
            peerService.importContact(bundle(identity), "Создатель", 1)
            val peerStore = InMemoryGroupConversationStore()
            val peerGroups = GroupChatService(peerService, peerRepo, peerStore, { error("Not needed for a reply") })
            val pair = groups.pairConversation(peer.user.userId)
            peerService.importConversation(service.conversationDescriptorCanonical(pair), "Создатель", 1)
            repo.messages(pair).forEach { peerService.admitIncoming(it.envelopeCanonicalJson, 2) }
            peerGroups.consume(pair) { error(it) }
            assertEquals(first.messageId, peerGroups.timeline(group.groupId).single().messageId)
            val reply = peerGroups.send(group.groupId, document, "Ответ ${index + 1}")
            peerGroups.pending().filter { it.second.peerUserId == identity.user.userId }.forEach { (id, p) ->
                val outer = peerGroups.stage(id, p.id)
                service.admitIncoming(peerService.messageCanonical(outer.outerMessageId), 3, pair)
                groups.consume(pair) { error(it) }
            }
            assertTrue(groups.timeline(group.groupId).any { it.messageId == reply.messageId })
        }
        assertEquals(3, groups.timeline(group.groupId).size)
        val storeFile = ctx.filesDir.resolve("group-chats/${group.groupId}.bin")
        assertTrue(storeFile.readText().startsWith("v1."))
        assertFalse(storeFile.readText().contains("Три автора"))
        val plain = AndroidAccountRecovery.snapshot(ctx, identity)
        try {
            val validated = AndroidAccountRecovery.validate(plain)
            try {
                val restoredFile = validated.files.getValue("group-chats/${group.groupId}.bin")
                assertEquals(groups.group(group.groupId), GroupConversationRecordCodec.parse(restoredFile.toString(Charsets.UTF_8)))
                val historical = HistoryVaultDocumentProvider(validated.history, validated.secret)
                try {
                    repo.conversations().flatMap { repo.messages(it.conversationId) }.filter { it.conversationId == groups.pairConversation(bob.user.userId) }
                        .forEach { row -> assertNotNull(historical.payloadFor(row.messageId)?.controlCanonical) }
                } finally { historical.close() }
            } finally { validated.close() }
            file("backup.eidobackup").writeBytes(AccountBackupCipher.encrypt(plain, password))
        } finally { plain.fill(0) }
        val expected = JSONObject().put("user_id", identity.user.userId).put("device_id", identity.certificate.deviceId)
            .put("group_id", group.groupId).put("messages", groups.timeline(group.groupId).size)
            .put("first_message_id", first.messageId).put("pending", groups.pending().size)
        file("expected.json").writeText(expected.toString())
        println("GROUP_BACKUP prepared: three authors, independent pending copies, control payloads in vault")
    }

    @Test fun restoreOnAnotherFreshInstallation() {
        val ctx = context
        assertFalse(AndroidKeystoreIdentityStore(ctx).hasSavedProfile())
        assertFalse(ctx.filesDir.resolve("eidolang-profile-v1.bin").exists())
        val expected = JSONObject(file("expected.json").readText())
        val identity = AndroidAccountRecovery.restore(ctx, file("backup.eidobackup").readBytes(), password)
        assertEquals(expected.getString("user_id"), identity.user.userId)
        assertEquals(expected.getString("device_id"), identity.certificate.deviceId)
        assertEquals("Групповой тест Android", EidoProfileStore(ctx).load()!!.nickname)
        val repo = AndroidMessengerRepositoryProvider.get(ctx)
        val service = LocalMessengerService(repo, identity)
        val groups = groupChatService(ctx, service)
        val id = expected.getString("group_id")
        assertEquals(expected.getInt("messages"), groups.timeline(id).size)
        assertEquals(3, groups.timeline(id).map { it.senderUserId }.toSet().size)
        assertTrue(groups.timeline(id).any { it.messageId == expected.getString("first_message_id") })
        assertEquals(expected.getInt("pending"), groups.pending().size)
        assertTrue(groups.group(id).canWrite(identity.user.userId))
        val before = groups.pending().map { it.second.outerMessageId }.toSet()
        groups.pending().forEach { (groupId, pending) -> groups.stage(groupId, pending.id) }
        assertEquals(before, groups.pending().map { it.second.outerMessageId }.toSet())
        val peers = groups.group(id).state.members.map { it.userId }.filter { it != identity.user.userId }
        groups.update(id, "Восстановленная группа", peers)
        assertEquals("Восстановленная группа", groups.group(id).state.title)
        assertEquals(3, groups.timeline(id).size)
        println("GROUP_RESTORE PASS: same profile, same devices, same three authors/messages, same outbox IDs, owner signature usable")
    }

    @Test fun verifyExistingRestoredGroupAfterOrdinaryUpdate() {
        val ctx = context
        val expected = JSONObject(file("expected.json").readText())
        val identity = AndroidAccountRecovery.currentIdentity(ctx)!!
        assertEquals(expected.getString("user_id"), identity.user.userId)
        assertEquals(expected.getString("device_id"), identity.certificate.deviceId)
        val service = LocalMessengerService(AndroidMessengerRepositoryProvider.get(ctx), identity)
        val groups = groupChatService(ctx, service)
        val groupId = expected.getString("group_id")
        assertEquals(expected.getInt("messages"), groups.timeline(groupId).size)
        assertTrue(groups.timeline(groupId).any { it.messageId == expected.getString("first_message_id") })
        val pendingIds = groups.pending().map { it.second.outerMessageId }.filter { it.isNotBlank() }.toSet()
        groups.pending().forEach { (id, pending) -> groups.stage(id, pending.id) }
        assertTrue(groups.pending().map { it.second.outerMessageId }.toSet().containsAll(pendingIds))
        val snapshot = AndroidAccountRecovery.snapshot(ctx, identity)
        try {
            val checked = AndroidAccountRecovery.validate(snapshot)
            try { assertTrue("group-chats/$groupId.bin" in checked.files) } finally { checked.close() }
        } finally { snapshot.fill(0) }
        println("GROUP_UPDATE PASS: restored profile, history and pending control IDs preserved; new consistent backup validates")
    }
}
