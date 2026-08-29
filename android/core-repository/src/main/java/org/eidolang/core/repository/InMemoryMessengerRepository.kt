package org.eidolang.core.repository

import org.eidolang.core.message.MessageCanonical
import org.eidolang.core.message.MessageParser

class InMemoryMessengerRepository : MessengerRepository {
    private val contacts = linkedMapOf<Pair<String,String>, ContactDeviceRecord>()
    private val ownDevices = linkedMapOf<Pair<String,String>, OwnDeviceRecord>()
    private val rosters = linkedMapOf<String, DeviceRosterRecord>()
    private val grants = linkedMapOf<Pair<String,String>, MessageKeyGrantRecord>()
    private val conversations = linkedMapOf<String, ConversationRecord>()
    private val messages = linkedMapOf<String, StoredMessageRecord>()
    private val seqIndex = linkedMapOf<Triple<String,String,Int>, String>()

    override fun putContact(record: ContactDeviceRecord) {
        val key = record.userId to record.deviceId
        // user_id/device_id identify the certified key body. The ECDSA root signature is proof
        // material and may be re-issued with different bytes for the same body. Validation is
        // performed by LocalMessengerService before storage.
        contacts[key] = record
    }

    override fun deleteContact(userId: String) {
        contacts.keys.filter { it.first == userId }.forEach { contacts.remove(it) }
    }

    override fun contactDevices(): List<ContactDeviceRecord> =
        contacts.values.sortedWith(compareBy<ContactDeviceRecord> { it.alias }.thenBy { it.userId }.thenBy { it.deviceId })


    override fun putOwnDevice(record: OwnDeviceRecord) {
        val key = record.userId to record.deviceId
        val old = ownDevices[key]
        if (old != null) {
            require(old.userId == record.userId && old.deviceId == record.deviceId)
        }
        ownDevices[key] = record
    }

    override fun ownDevices(): List<OwnDeviceRecord> =
        ownDevices.values.sortedWith(compareBy<OwnDeviceRecord> { it.userId }.thenBy { it.deviceId })

    override fun putDeviceRoster(record: DeviceRosterRecord) {
        val old = rosters[record.userId]
        if (old != null) {
            require(record.epoch >= old.epoch)
            if (record.epoch == old.epoch) require(record.rosterId == old.rosterId)
        }
        rosters[record.userId] = record
    }

    override fun deviceRosters(): List<DeviceRosterRecord> =
        rosters.values.sortedBy { it.userId }

    override fun putKeyGrant(record: MessageKeyGrantRecord) {
        val key = record.messageId to record.targetEncryptionKeyId
        val old = grants[key]
        if (old != null) {
            val oldGrant = org.eidolang.core.multidevice.MessageKeyGrantCanonical.parseCanonical(old.grantCanonicalJson)
            val newGrant = org.eidolang.core.multidevice.MessageKeyGrantCanonical.parseCanonical(record.grantCanonicalJson)
            require(oldGrant.body == newGrant.body) { "Conflicting grant body for same message/target" }
        }
        grants[key] = record
    }

    override fun keyGrants(messageId: String?): List<MessageKeyGrantRecord> =
        grants.values.filter { messageId == null || it.messageId == messageId }
            .sortedWith(compareBy<MessageKeyGrantRecord> { it.messageId }.thenBy { it.targetEncryptionKeyId })

    override fun putConversation(record: ConversationRecord) {
        val old = conversations[record.conversationId]
        if (old != null) {
            require(old.descriptorCanonicalJson == record.descriptorCanonicalJson) {
                "Conflicting conversation descriptor"
            }
        }
        conversations[record.conversationId] = record
    }

    override fun conversations(): List<ConversationRecord> =
        conversations.values.sortedWith(compareByDescending<ConversationRecord> { it.createdAtMs }.thenBy { it.conversationId })

    override fun insertMessage(record: StoredMessageRecord): InsertMessageResult {
        val existing = messages[record.messageId]
        if (existing != null) {
            val old = MessageParser.parseCanonical(existing.envelopeCanonicalJson)
            val incoming = MessageParser.parseCanonical(record.envelopeCanonicalJson)
            require(MessageCanonical.bodyJson(old.body) == MessageCanonical.bodyJson(incoming.body)) {
                "Same message_id with different message body"
            }
            return InsertMessageResult.Duplicate
        }
        val seqKey = Triple(record.conversationId, record.senderDeviceId, record.senderSeq)
        val oldId = seqIndex[seqKey]
        require(oldId == null) {
            "Sender sequence equivocation: $seqKey already belongs to $oldId"
        }
        messages[record.messageId] = record
        seqIndex[seqKey] = record.messageId
        return InsertMessageResult.Inserted
    }

    override fun messages(conversationId: String): List<StoredMessageRecord> =
        messages.values.filter { it.conversationId == conversationId }
}
