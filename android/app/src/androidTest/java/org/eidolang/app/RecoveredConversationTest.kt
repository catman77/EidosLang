package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.archive.*
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.transport.*
import org.eidolang.feature.home.*
import org.eidolang.transport.libtorrent4j.AndroidArchivePublisherStore
import org.junit.Assert.*
import org.junit.Test

@TwoDeviceOnly
class RecoveredConversationTest {
    private fun bundle(identity: DevicePrivateCrypto) = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(identity.user, identity.certificate))

    @Test fun realRestoredHistoryCanBePublishedWithEveryHistoricalSenderIdentity() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        check(ctx.packageName == "org.eidolang.app.recoverycheck")
        val identity = AndroidAccountRecovery.currentIdentity(ctx)!!
        val repo = AndroidMessengerRepositoryProvider.get(ctx)
        val conversation = repo.conversations().single().conversationId
        val artifact = buildConversationPublication(repo, identity, conversation)
        assertEquals(repo.messages(conversation).size, artifact.manifest.messageEntries.size)
        val senderIds = repo.messages(conversation).map { it.senderDeviceId }.toSet()
        assertTrue(artifact.manifest.identityEntries.map { it.deviceId }.containsAll(senderIds))
        val publisher = AndroidArchivePublisherStore(ctx).ensure(identity)
        val card = ContactCard.read(ContactCard.write(identity, publisher.certificate, context = ctx))
        assertTrue(card.deviceState!!.bundles.isNotEmpty())
    }

    @Test fun existingContactContinuesBothDirectionsAfterAnAuthorizedRecovery() {
        val old = JvmPrivateIdentity.generate()
        val peer = JvmPrivateIdentity.generate()
        val signing = JcaCrypto.p256()
        val encryption = JcaCrypto.rsa2048()
        val body = IdentityFactory.deviceBody(old.user, signing.public, encryption.public)
        val certificate = IdentityFactory.certificate(body, old.rootSigning.private)
        val recovered = PortableIdentity(IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(old.user, certificate)),
            B64Url.encode(signing.private.encoded), B64Url.encode(encryption.private.encoded))
        recovered.validate()
        val authority = object : RootAuthority {
            override val user = old.user
            override fun signRoot(bytes: ByteArray) = JcaCrypto.sign(old.rootSigning.private, bytes)
        }
        val repo = InMemoryMessengerRepository()
        val otherRepo = InMemoryMessengerRepository()
        val original = LocalMessengerService(repo, old)
        val other = LocalMessengerService(otherRepo, peer)
        original.importContact(bundle(peer), "Собеседник", 1)
        other.importContact(bundle(old), "Прежний контакт", 1)
        val firstRoster = DeviceRosterAuthority.issue(authority, null, listOf(old.certificate.deviceId), emptyList())
        original.importDeviceRoster(DeviceRosterCanonical.json(firstRoster), 1)
        other.importDeviceRoster(DeviceRosterCanonical.json(firstRoster), 1)
        val conversation = original.createConversation(listOf(peer.user.userId), title = "Диалог", createdAtMs = 2)
        other.importConversation(ConversationCanonical.descriptorJson(conversation), "Диалог", 2)
        val document = EidogramDocumentV1(listOf(EidogramAction.Add(0, "g000001", "circle.red.m", FixedTransform(400_000, 500_000), 0)))
        val before = original.send(conversation.conversationId, document, 3, caption = "До восстановления")
        other.admitIncoming(MessageCanonical.envelopeJson(before), 3)
        val current = LocalMessengerService(repo, recovered)
        current.importOwnDevice(bundle(recovered), 4)
        val roster = DeviceRosterAuthority.issue(authority, firstRoster,
            listOf(old.certificate.deviceId, recovered.certificate.deviceId), emptyList())
        current.importDeviceRoster(DeviceRosterCanonical.json(roster), 4)
        val oldPublisher = JcaArchivePublisher.create(old)
        val newPublisher = JcaArchivePublisher.restore(recovered, oldPublisher.seed)
        val card = ContactCard.read(ContactCard.write(recovered, newPublisher.certificate,
            deviceState = ContactDeviceState(listOf(bundle(old), bundle(recovered)), DeviceRosterCanonical.json(roster))))
        adoptRecoveredContact(other, otherRepo, old.user.userId, oldPublisher.certificate, card)
        assertEquals("Прежний контакт", other.contactSummaries().single().alias)
        val after = current.send(conversation.conversationId, document, 5, caption = "После восстановления")
        other.admitIncoming(MessageCanonical.envelopeJson(after), 5)
        assertEquals(listOf("До восстановления", "После восстановления"), other.timeline(conversation.conversationId).map { it.caption })
        val reply = other.send(conversation.conversationId, document, 6, caption = "Ответ восстановленному профилю")
        current.admitIncoming(MessageCanonical.envelopeJson(reply), 6)
        assertEquals("Ответ восстановленному профилю", MessageCrypto.openPayload(reply, peer.user, peer.certificate, recovered, conversation).caption)
        val artifact = buildConversationPublication(repo, recovered, conversation.conversationId)
        assertEquals(3, artifact.manifest.messageEntries.size)
        val head = ConversationHeadCodec.create(ConversationHeadValueV1(
            conversationId = conversation.conversationId, segmentId = artifact.manifest.segmentId,
            torrentInfoHashV2 = artifact.torrent.infoHashV2Hex, headMessageIds = artifact.manifest.headMessageIds,
            publisherKeyId = newPublisher.certificate.publisherKeyId, publisherDeviceId = recovered.certificate.deviceId), 7, newPublisher)
        val admitted = NetworkAdmissionGate.admit(FetchResult(TransportObjects.fromArtifact(artifact), "recovery-test"),
            MutableHeadRecord(head.targetSha1Hex, Bencode.encode(ConversationHeadCodec.mutablePutFields(head))),
            ArchivePublisherCanonical.certificateJson(newPublisher.certificate), bundle(recovered))
        assertEquals(artifact.manifest.segmentId, admitted.artifact.manifest.segmentId)
        // A foreign root cannot replace this contact even if it reuses the same publisher seed.
        val foreign = JvmPrivateIdentity.generate()
        val badCard = ContactCard.read(ContactCard.write(foreign, JcaArchivePublisher.restore(foreign, oldPublisher.seed).certificate))
        assertTrue(runCatching { adoptRecoveredContact(other, otherRepo, old.user.userId, oldPublisher.certificate, badCard) }.isFailure)
        assertEquals(old.user.userId, other.contactSummaries().single().userId)
    }
}
