package org.eidolang.feature.home

import android.content.Context
import org.eidolang.core.archive.*
import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.AndroidAccountRecovery
import org.eidolang.core.hardening.AndroidMessengerRepositoryProvider
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*

/** Public, root-authenticated device metadata; private keys never enter a contact card. */
data class ContactDeviceState(val bundles: List<String>, val roster: String?) {
    fun json(): String = CanonicalJson.obj(mapOf(
        "bundles" to CanonicalJson.arr(bundles), "roster" to (roster ?: "null")))

    companion object {
        fun capture(context: Context, identity: DevicePrivateCrypto): ContactDeviceState {
            val repo = AndroidMessengerRepositoryProvider.get(context)
            val current = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(identity.user, identity.certificate))
            val bundles = (repo.ownDevices().filter { it.userId == identity.user.userId }.map { it.bundleCanonicalJson } + current)
                .associateBy { IdentityParser.parsePublicBundleCanonical(it).device.deviceId }.toSortedMap().values.toList()
            return ContactDeviceState(bundles, repo.deviceRosters().firstOrNull { it.userId == identity.user.userId }?.rosterCanonicalJson)
        }

        fun parse(value: JValue, owner: PublicIdentityBundleV1): ContactDeviceState {
            val obj = value as JValue.Obj
            require(obj.fields.keys == setOf("bundles", "roster"))
            val bundles = (obj.fields.getValue("bundles") as JValue.Arr).items.map(CanonicalReserialize::of)
            require(bundles.size <= 64)
            val parsed = bundles.map(IdentityParser::parsePublicBundleCanonical)
            require(parsed.all { it.user == owner.user })
            require(parsed.map { it.device.deviceId }.distinct().size == parsed.size)
            val roster = (obj.fields["roster"] as? JValue.Obj)?.let(CanonicalReserialize::of)
            if (roster != null) {
                val r = DeviceRosterCanonical.parseCanonical(roster)
                require(DeviceRosterVerifier.verifySignature(r, owner.user))
                val known = parsed.map { it.device.deviceId }.toSet() + owner.device.deviceId
                require((r.body.activeDeviceIds + r.body.revokedDeviceIds).all { it in known })
                require(owner.device.deviceId in r.body.activeDeviceIds)
            }
            return ContactDeviceState(bundles, roster)
        }
    }
}

/** Validate a replacement against the existing root and publisher key before changing contact state. */
fun adoptRecoveredContact(
    service: LocalMessengerService, repository: MessengerRepository, expectedUserId: String,
    pinned: ArchivePublisherCertificateV1, card: ContactCard.Parsed,
) {
    val owner = IdentityParser.parsePublicBundleCanonical(card.identityCanonical)
    val publisher = card.publisher ?: error("Визитка не содержит ключ публикации")
    require(owner.user.userId == expectedUserId && pinned.userId == expectedUserId)
    require(publisher.dhtPublicKeyRawB64 == pinned.dhtPublicKeyRawB64) { "Publisher key changed" }
    require(ArchivePublisherVerifier.verify(publisher, owner))
    val rawBundles = (card.deviceState?.bundles.orEmpty() + card.identityCanonical)
        .associateBy { IdentityParser.parsePublicBundleCanonical(it).device.deviceId }.values
    rawBundles.forEach { require(IdentityParser.parsePublicBundleCanonical(it).user == owner.user) }
    val alias = service.contactSummaries().first { it.userId == expectedUserId }.alias
    fun apply(target: LocalMessengerService) {
        rawBundles.forEach { target.importContact(it, alias, System.currentTimeMillis()) }
        card.deviceState?.roster?.let { target.importDeviceRosterSnapshot(it, System.currentTimeMillis()) }
        require(target.isDeviceActive(expectedUserId, owner.device.deviceId)) { "Устройство собеседника отозвано" }
    }
    val mirror = InMemoryMessengerRepository()
    AndroidAccountRecovery.copyRepository(repository, mirror)
    apply(LocalMessengerService(mirror, service.localIdentity))
    apply(service)
}

/** Whole conversation publication must carry the identities of its pre-recovery senders too. */
fun buildConversationPublication(repository: MessengerRepository, identity: DevicePrivateCrypto, conversationId: String): ConversationSegmentArtifactV1 {
    val conversation = org.eidolang.core.message.ConversationParser.parseCanonical(
        repository.conversations().first { it.conversationId == conversationId }.descriptorCanonicalJson)
    val current = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(identity.user, identity.certificate))
    val bundles = (repository.ownDevices().map { it.bundleCanonicalJson } + repository.contactDevices().map { it.bundleCanonicalJson } + current)
        .map(IdentityParser::parsePublicBundleCanonical).filter { it.user.userId in conversation.participantUserIds }
        .associateBy { it.device.deviceId }.values
    return ConversationSegmentBuilder.build(conversation, bundles, repository.messages(conversationId).map { it.envelopeCanonicalJson })
}
