package org.eidolang.feature.home

import android.content.Context
import org.eidolang.core.canonical.CanonicalJson
import org.eidolang.core.crypto.*
import org.eidolang.core.archive.ArchivePublisherCanonical
import org.eidolang.core.hardening.AndroidGroupConversationStore
import org.eidolang.core.hardening.AndroidMessengerRepositoryProvider
import org.eidolang.core.message.GroupMemberV1
import org.eidolang.core.message.GroupStateCodec
import org.eidolang.core.repository.GroupChatService
import org.eidolang.core.repository.LocalMessengerService

fun groupChatService(context: Context, messenger: LocalMessengerService): GroupChatService {
    val repository = AndroidMessengerRepositoryProvider.get(context)
    val identity = messenger.localIdentity
    return GroupChatService(messenger, repository, AndroidGroupConversationStore(context), { uid ->
        if (uid == identity.user.userId) {
            val nickname = EidoProfileStore(context).load()?.nickname.orEmpty()
            val card = ContactCard.write(identity, EidoTransport.of(context, identity).publisher.certificate,
                nickname = nickname, onion = EidoOnionNode.address.orEmpty(), context = context)
            GroupMemberV1(uid, messenger.exportLocalIdentityCanonical(), EidoHandle.of(nickname, uid), card)
        } else {
            val contact = messenger.contactSummaries().first { it.userId == uid }
            val publisher = ContactPublisherStore(context).get(uid)
                ?: error("У ${contact.alias} нет ключа доставки. Добавьте контакт заново по ссылке или визитке.")
            val bundle = repository.contactDevices().firstOrNull { it.userId == uid && it.deviceId == publisher.deviceId }
                ?.bundleCanonicalJson ?: error("Обновите визитку ${contact.alias}")
            val deviceState = ContactDeviceState(repository.contactDevices().filter { it.userId == uid }
                .map { it.bundleCanonicalJson }, repository.deviceRosters().firstOrNull { it.userId == uid }?.rosterCanonicalJson)
            val card = CanonicalJson.obj(mapOf(
                "card_version" to CanonicalJson.string("eido-contact-card-v1"),
                "identity" to bundle,
                "profile" to CanonicalJson.obj(mapOf("nickname" to CanonicalJson.string(EidoHandle.nicknameOf(contact.alias)),
                    "avatar_png_b64" to CanonicalJson.string(""))),
                "publisher" to ArchivePublisherCanonical.certificateJson(publisher),
                "transport" to CanonicalJson.obj(mapOf("onion" to CanonicalJson.string(ContactOnionStore(context).get(uid).orEmpty()))),
                "device_state" to deviceState.json(),
            ))
            ContactCard.read(card)
            GroupMemberV1(uid, bundle, contact.alias.take(120), card)
        }
    })
}

/** Validate the complete set before writing any routing pins. Existing pins cannot be replaced. */
fun importGroupDeliveryCards(context: Context, service: LocalMessengerService, groups: GroupChatService) {
    val members = groups.groups().flatMap { GroupStateCodec.verifyChain(it.chain) }
        .flatMap { it.members }.filter { it.userId != service.localIdentity.user.userId && it.deliveryCardCanonical.isNotEmpty() }
    val cards = members.map { member ->
        val card = ContactCard.read(member.deliveryCardCanonical)
        val owner = IdentityParser.parsePublicBundleCanonical(card.identityCanonical)
        require(owner.user.userId == member.userId)
        require(card.identityCanonical == member.identityCanonical)
        val pin = ContactPublisherStore(context).get(member.userId)
        require(pin == null || card.publisher?.dhtPublicKeyRawB64 == pin.dhtPublicKeyRawB64) { "Изменился ключ публикации участника группы" }
        member.userId to card
    }
    cards.forEach { (id, card) ->
        card.publisher?.let { ContactPublisherStore(context).put(id, it) }
        if (card.onion.isNotEmpty()) ContactOnionStore(context).put(id, card.onion)
        card.deviceState?.bundles.orEmpty().forEach { raw ->
            val alias = service.contactSummaries().first { it.userId == id }.alias
            service.importContact(raw, alias, System.currentTimeMillis())
        }
        card.deviceState?.roster?.let { raw ->
            val incoming = org.eidolang.core.multidevice.DeviceRosterCanonical.parseCanonical(raw)
            val current = service.latestRoster(id)
            if (current == null || incoming.body.epoch >= current.body.epoch)
                service.importDeviceRosterSnapshot(raw, System.currentTimeMillis())
        }
    }
}

/** One peer's failure leaves their own durable outbox entry and never blocks the other peers. */
fun flushGroupOutbox(context: Context, service: LocalMessengerService, groups: GroupChatService, onlyGroupId: String? = null): String {
    val identity = service.localIdentity
    groups.recoverPendingMessages()
    val through = DeliveryLedger(context).deliveredThroughByContact()
    var ready = 0
    val problems = mutableListOf<String>()
    synchronized(GROUP_PUBLICATION_LOCK) { groups.pending().filter { onlyGroupId == null || it.first == onlyGroupId }.forEach { (id, pending) ->
        runCatching {
            val staged = groups.stage(id, pending.id)
            if ((through[staged.peerUserId] ?: 0) >= staged.outerCreatedAtMs) {
                groups.published(id, staged.id)
                ready++
                return@runCatching
            }
            var durable = false
            publishConversation(context, service, EidoTransport.of(context, identity), identity,
                groups.pairConversation(staged.peerUserId), service.contactBundleCanonical(staged.peerUserId),
                onOfflineCopyReady = { durable = it })
            if (durable) { groups.published(id, staged.id); ready++ }
            else problems += "ожидает сети"
        }.onFailure { problems += it.message ?: "нет связи" }
    } }
    return when {
        problems.isNotEmpty() -> "Сохранено; доставка ожидает сети (${groups.pending().count { onlyGroupId == null || it.first == onlyGroupId }}). ${problems.first()}"
        ready > 0 -> "Доставляется участникам: $ready"
        else -> "Готово"
    }
}

private val GROUP_PUBLICATION_LOCK = Any()
