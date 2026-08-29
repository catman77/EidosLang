package org.eidolang.core.recovery

import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import org.eidolang.core.message.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import java.util.PriorityQueue


data class MultiDeviceRecoveryCapsuleV1(
    val sourcePackageId: String,
    val ownerUserId: String,
    val targetDeviceId: String,
    val targetEncryptionKeyId: String,
    val ownDeviceBundleCanonicalJson: List<String>,
    val deviceRosterCanonicalJson: List<String>,
    val grantCanonicalJson: List<String>,
    val capsuleId: String,
)

object MultiDeviceRecoveryCapsuleCodec {
    fun bodyJson(c: MultiDeviceRecoveryCapsuleV1): String = CanonicalJson.obj(mapOf(
        "capsule_type" to CanonicalJson.string("EidoMultiDeviceRecoveryCapsuleV1"),
        "capsule_version" to CanonicalJson.string("1.1.0"),
        "device_roster_json" to CanonicalJson.arr(c.deviceRosterCanonicalJson.map(CanonicalJson::string)),
        "grant_json" to CanonicalJson.arr(c.grantCanonicalJson.map(CanonicalJson::string)),
        "own_device_bundle_json" to CanonicalJson.arr(c.ownDeviceBundleCanonicalJson.map(CanonicalJson::string)),
        "owner_user_id" to CanonicalJson.string(c.ownerUserId),
        "source_package_id" to CanonicalJson.string(c.sourcePackageId),
        "target_device_id" to CanonicalJson.string(c.targetDeviceId),
        "target_encryption_key_id" to CanonicalJson.string(c.targetEncryptionKeyId),
    ))

    fun json(c: MultiDeviceRecoveryCapsuleV1): String = CanonicalJson.obj(mapOf(
        "body" to bodyJson(c),
        "capsule_id" to CanonicalJson.string(c.capsuleId),
    ))

    fun withId(
        sourcePackageId: String,
        ownerUserId: String,
        targetDeviceId: String,
        targetEncryptionKeyId: String,
        ownDeviceBundleCanonicalJson: Collection<String>,
        deviceRosterCanonicalJson: Collection<String>,
        grantCanonicalJson: Collection<String>,
    ): MultiDeviceRecoveryCapsuleV1 {
        val ownBundles = ownDeviceBundleCanonicalJson
            .map(IdentityParser::parsePublicBundleCanonical)
            .sortedBy { it.device.deviceId }
            .map(IdentityCanonical::publicBundleJson)
        val rosters = deviceRosterCanonicalJson
            .map(DeviceRosterCanonical::parseCanonical)
            .sortedWith(compareBy<UserDeviceRosterV1> { it.body.userId }.thenBy { it.body.epoch })
            .map(DeviceRosterCanonical::json)
        val grants = grantCanonicalJson
            .map(MessageKeyGrantCanonical::parseCanonical)
            .sortedWith(compareBy<MessageKeyGrantV1> { it.body.messageId }.thenBy { it.grantId })
            .map(MessageKeyGrantCanonical::json)
        val provisional = MultiDeviceRecoveryCapsuleV1(
            sourcePackageId, ownerUserId, targetDeviceId, targetEncryptionKeyId,
            ownBundles, rosters, grants, "",
        )
        return provisional.copy(capsuleId = HexSha256.ofUtf8(bodyJson(provisional)))
    }

    fun parseCanonical(text: String): MultiDeviceRecoveryCapsuleV1 {
        val root = StrictJsonParser(text).parse().obj()
        root.requireKeys("body", "capsule_id")
        val b = root.obj("body")
        b.requireKeys(
            "capsule_type", "capsule_version", "device_roster_json", "grant_json",
            "own_device_bundle_json", "owner_user_id", "source_package_id",
            "target_device_id", "target_encryption_key_id"
        )
        require(b.str("capsule_type") == "EidoMultiDeviceRecoveryCapsuleV1")
        require(b.str("capsule_version") == "1.1.0")
        val c = MultiDeviceRecoveryCapsuleV1(
            sourcePackageId = b.str("source_package_id"),
            ownerUserId = b.str("owner_user_id"),
            targetDeviceId = b.str("target_device_id"),
            targetEncryptionKeyId = b.str("target_encryption_key_id"),
            ownDeviceBundleCanonicalJson = b.arr("own_device_bundle_json").items.map { it.str() },
            deviceRosterCanonicalJson = b.arr("device_roster_json").items.map { it.str() },
            grantCanonicalJson = b.arr("grant_json").items.map { it.str() },
            capsuleId = root.str("capsule_id"),
        )
        val normalized = withId(
            c.sourcePackageId, c.ownerUserId, c.targetDeviceId, c.targetEncryptionKeyId,
            c.ownDeviceBundleCanonicalJson, c.deviceRosterCanonicalJson, c.grantCanonicalJson,
        )
        require(normalized == c) { "Multi-device recovery capsule is not canonical" }
        return c
    }
}

object MultiDeviceRecoveryCapsuleFactory {
    fun create(
        sourcePackageCanonical: String,
        ownDeviceBundleCanonicalJson: Collection<String>,
        deviceRosterCanonicalJson: Collection<String>,
        target: PublicIdentityBundleV1,
        grantCanonicalJson: Collection<String>,
    ): String {
        val p = ArchivePackageCodec.parseCanonical(sourcePackageCanonical)
        require(p.ownerUserId == target.user.userId)

        val own = ownDeviceBundleCanonicalJson.map(IdentityParser::parsePublicBundleCanonical)
        require(own.isNotEmpty())
        require(own.all { it.user.userId == p.ownerUserId })
        require(own.any { it.device.deviceId == target.device.deviceId })

        val rosters = deviceRosterCanonicalJson.map(DeviceRosterCanonical::parseCanonical)
        require(rosters.map { it.body.userId }.distinct().size == rosters.size) {
            "Capsule must contain at most one latest roster per user"
        }
        val ownerRoster = rosters.singleOrNull { it.body.userId == target.user.userId }
            ?: error("Missing owner device roster")
        require(DeviceRosterVerifier.verifySignature(ownerRoster, target.user))
        require(target.device.deviceId in ownerRoster.body.activeDeviceIds) { "Target device is not active" }

        // Verify remote roster roots from contact identities carried by the package.
        val packageUsers = p.contacts.flatMap { it.deviceBundleCanonicalJson }
            .map(IdentityParser::parsePublicBundleCanonical)
            .associateBy { it.user.userId }
        rosters.filter { it.body.userId != target.user.userId }.forEach { r ->
            val u = packageUsers[r.body.userId]?.user ?: error("Roster user is absent from recovery package contacts")
            require(DeviceRosterVerifier.verifySignature(r, u))
        }

        grantCanonicalJson.map(MessageKeyGrantCanonical::parseCanonical).forEach { g ->
            require(g.body.ownerUserId == target.user.userId)
            require(g.body.targetDeviceId == target.device.deviceId)
            require(g.body.targetEncryptionKeyId == target.device.body.encryptionKeyId)
        }
        return MultiDeviceRecoveryCapsuleCodec.json(
            MultiDeviceRecoveryCapsuleCodec.withId(
                sourcePackageId = p.packageId,
                ownerUserId = p.ownerUserId,
                targetDeviceId = target.device.deviceId,
                targetEncryptionKeyId = target.device.body.encryptionKeyId,
                ownDeviceBundleCanonicalJson = ownDeviceBundleCanonicalJson,
                deviceRosterCanonicalJson = deviceRosterCanonicalJson,
                grantCanonicalJson = grantCanonicalJson,
            )
        )
    }
}

class MultiDeviceRecoveryCoordinator(
    private val repository: MessengerRepository,
    private val archiveStore: ArchiveStore,
    private val targetIdentity: DevicePrivateCrypto,
) {
    fun restore(
        sourcePackageCanonical: String,
        capsuleCanonical: String,
        storedAtMs: Long,
    ): RestoreReport {
        val p = ArchivePackageCodec.parseCanonical(sourcePackageCanonical)
        val c = MultiDeviceRecoveryCapsuleCodec.parseCanonical(capsuleCanonical)
        require(c.sourcePackageId == p.packageId)
        require(c.ownerUserId == p.ownerUserId && p.ownerUserId == targetIdentity.user.userId)
        require(c.targetDeviceId == targetIdentity.certificate.deviceId)
        require(c.targetEncryptionKeyId == targetIdentity.certificate.body.encryptionKeyId)

        val rosters = c.deviceRosterCanonicalJson.map(DeviceRosterCanonical::parseCanonical)
        val roster = rosters.singleOrNull { it.body.userId == targetIdentity.user.userId }
            ?: error("Missing owner device roster")
        require(DeviceRosterVerifier.verifySignature(roster, targetIdentity.user))
        require(targetIdentity.certificate.deviceId in roster.body.activeDeviceIds) { "Recovery target is revoked" }

        // Verify every immutable segment before any actual mutation.
        p.segments.forEach(ArchiveSegmentCodec::toArtifact)

        val mirrorRepo = InMemoryMessengerRepository()
        repository.contactDevices().forEach(mirrorRepo::putContact)
        repository.ownDevices().forEach(mirrorRepo::putOwnDevice)
        repository.deviceRosters().forEach(mirrorRepo::putDeviceRoster)
        repository.keyGrants().forEach(mirrorRepo::putKeyGrant)
        repository.conversations().forEach { conv ->
            mirrorRepo.putConversation(conv)
            repository.messages(conv.conversationId).forEach { mirrorRepo.insertMessage(it) }
        }
        val mirrorArchive = InMemoryArchiveStore()
        archiveStore.list().forEach { mirrorArchive.put(it.blob, it.pinned, it.storedAtMs) }

        apply(p, c, mirrorRepo, mirrorArchive, storedAtMs)
        return apply(p, c, repository, archiveStore, storedAtMs)
    }

    private fun apply(
        p: ArchivePackageV1,
        c: MultiDeviceRecoveryCapsuleV1,
        targetRepo: MessengerRepository,
        targetArchive: ArchiveStore,
        storedAtMs: Long,
    ): RestoreReport {
        val targetService = LocalMessengerService(targetRepo, targetIdentity)

        val identities = linkedMapOf<Pair<String,String>, String>()
        identities[targetIdentity.user.userId to targetIdentity.certificate.deviceId] =
            IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(targetIdentity.user, targetIdentity.certificate))
        c.ownDeviceBundleCanonicalJson.forEach { raw ->
            val b = IdentityParser.parsePublicBundleCanonical(raw)
            require(b.user.userId == targetIdentity.user.userId)
            identities[b.user.userId to b.device.deviceId] = chooseProof(
                identities[b.user.userId to b.device.deviceId], raw
            )
        }

        // Presentation contact metadata.
        p.contacts.forEach { contact ->
            contact.deviceBundleCanonicalJson.forEach { raw ->
                val b = IdentityParser.parsePublicBundleCanonical(raw)
                require(b.user.userId == contact.userId)
                identities[b.user.userId to b.device.deviceId] = chooseProof(
                    identities[b.user.userId to b.device.deviceId], raw
                )
            }
        }

        val messageCopies = linkedMapOf<String, MutableList<String>>()
        p.segments.sortedBy { it.segmentId }.forEach { blob ->
            val artifact = ArchiveSegmentCodec.toArtifact(blob)
            val files = artifact.files.associateBy { it.path }
            artifact.manifest.identityEntries.forEach { e ->
                val raw = files.getValue(e.path).bytes.toString(Charsets.UTF_8)
                val b = IdentityParser.parsePublicBundleCanonical(raw)
                identities[b.user.userId to b.device.deviceId] = chooseProof(
                    identities[b.user.userId to b.device.deviceId], raw
                )
            }
            artifact.manifest.messageEntries.forEach { e ->
                messageCopies.getOrPut(e.messageId) { mutableListOf() }
                    .add(files.getValue(e.path).bytes.toString(Charsets.UTF_8))
            }
        }

        // All public identities before conversations/messages.
        identities.toSortedMap(compareBy<Pair<String,String>> { it.first }.thenBy { it.second }).forEach { (_, raw) ->
            val b = IdentityParser.parsePublicBundleCanonical(raw)
            if (b.user.userId == targetIdentity.user.userId) {
                targetService.importOwnDevice(raw, storedAtMs)
            } else {
                val alias = p.contacts.firstOrNull { it.userId == b.user.userId }?.alias
                targetService.importContact(raw, alias, storedAtMs)
            }
        }

        // Conversations including empty ones.
        p.conversations.sortedBy { it.conversationId }.forEach { meta ->
            targetService.importConversation(meta.descriptorCanonicalJson, meta.title, meta.createdAtMs)
        }

        // Canonical proof choice for overlapping segments.
        val chosen = linkedMapOf<String,String>()
        var duplicateCopies = 0
        messageCopies.toSortedMap().forEach { (id, copies) ->
            val parsed = copies.map { it to MessageParser.parseCanonical(it) }
            require(parsed.all { it.second.messageId == id })
            require(parsed.map { MessageCanonical.bodyJson(it.second.body) }.distinct().size == 1) {
                "Same message_id used for different encrypted bodies"
            }
            duplicateCopies += copies.size - 1
            chosen[id] = parsed.minOf { it.first }
        }

        val grants = c.grantCanonicalJson
            .map(MessageKeyGrantCanonical::parseCanonical)
            .associateBy { it.body.messageId }

        // Restore history before importing the current roster so revocation stays prospective.
        topological(chosen.values.map(MessageParser::parseCanonical)).forEach { m ->
            val raw = chosen.getValue(m.messageId)
            val direct = m.body.recipientBoxes.any {
                it.encryptionKeyId == targetIdentity.certificate.body.encryptionKeyId
            }
            if (direct) {
                targetService.admitIncoming(raw, storedAtMs, m.body.aad.conversationId)
            } else {
                val grant = grants[m.messageId] ?: error("Missing historical key grant for ${m.messageId}")
                targetService.admitHistoricalWithGrant(raw, MessageKeyGrantCanonical.json(grant), storedAtMs)
            }
        }

        // Current authorization policy is installed after historical reconstruction.
        c.deviceRosterCanonicalJson
            .map(DeviceRosterCanonical::parseCanonical)
            .sortedBy { it.body.userId }
            .forEach { targetService.importDeviceRosterSnapshot(DeviceRosterCanonical.json(it), storedAtMs) }

        // Preserve immutable archive bytes and local pin state.
        p.segments.forEach { blob ->
            targetArchive.put(blob, blob.segmentId in p.pinnedSegmentIds, storedAtMs)
        }
        p.pinnedSegmentIds.forEach { targetArchive.setPinned(it, true) }

        return RestoreReport(
            segmentCount = p.segments.size,
            contactDeviceCount = identities.keys.count { it.first != targetIdentity.user.userId },
            conversationCount = p.conversations.size,
            uniqueMessageCount = chosen.size,
            duplicateMessageCopies = duplicateCopies,
        )
    }

    private fun chooseProof(old: String?, incoming: String): String {
        if (old == null) return incoming
        val a = IdentityParser.parsePublicBundleCanonical(old)
        val b = IdentityParser.parsePublicBundleCanonical(incoming)
        require(a.user == b.user && a.device.body == b.device.body && a.device.deviceId == b.device.deviceId)
        return minOf(old, incoming)
    }

    private fun topological(messages: Collection<EidogramMessageV1>): List<EidogramMessageV1> {
        val byId = messages.associateBy { it.messageId }
        require(byId.size == messages.size)
        val children = byId.keys.associateWith { mutableListOf<String>() }
        val indegree = byId.keys.associateWith { 0 }.toMutableMap()
        messages.forEach { child ->
            child.body.aad.parentMessageIds.filter { it in byId }.forEach { parent ->
                children.getValue(parent).add(child.messageId)
                indegree[child.messageId] = indegree.getValue(child.messageId) + 1
            }
        }
        val cmp = compareBy<String>(
            { byId.getValue(it).body.aad.createdAtMs },
            { byId.getValue(it).body.aad.senderUserId },
            { byId.getValue(it).body.aad.senderSeq },
            { it },
        )
        val q = PriorityQueue(cmp)
        indegree.filterValues { it == 0 }.keys.forEach(q::add)
        val out = mutableListOf<EidogramMessageV1>()
        while (q.isNotEmpty()) {
            val id = q.remove()
            out += byId.getValue(id)
            children.getValue(id).sorted().forEach { child ->
                val n = indegree.getValue(child) - 1
                indegree[child] = n
                if (n == 0) q.add(child)
            }
        }
        require(out.size == messages.size) { "Recovery message graph contains a cycle" }
        return out
    }
}

private fun JValue.obj(): JValue.Obj = this as? JValue.Obj ?: error("Expected object")
private fun JValue.str(): String = (this as? JValue.Str)?.value ?: error("Expected string")
private fun JValue.Obj.str(k: String): String = fields[k]?.str() ?: error("Missing '$k'")
private fun JValue.Obj.obj(k: String): JValue.Obj = fields[k]?.obj() ?: error("Missing '$k'")
private fun JValue.Obj.arr(k: String): JValue.Arr = fields[k] as? JValue.Arr ?: error("Missing '$k'")
private fun JValue.Obj.requireKeys(vararg keys: String) {
    require(fields.keys == keys.toSet()) { "Object schema mismatch" }
}
