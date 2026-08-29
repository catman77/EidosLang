package org.eidolang.core.repository

import org.eidolang.core.model.EidogramDocumentV1


interface RepositoryTextProtector {
    fun protect(namespace: String, logicalId: String, plaintext: String): String
    fun unprotect(namespace: String, logicalId: String, stored: String): String
    fun isProtected(stored: String): Boolean
}

interface HistoricalDocumentProvider {
    /**
     * Returns an authenticated recovered document for the exact immutable message id,
     * or null when this provider has no recovery record for that message.
     */
    fun documentFor(messageId: String): EidogramDocumentV1?
}

data class ContactDeviceRecord(
    val userId: String,
    val deviceId: String,
    val alias: String,
    val bundleCanonicalJson: String,
    val importedAtMs: Long,
)


data class OwnDeviceRecord(
    val userId: String,
    val deviceId: String,
    val bundleCanonicalJson: String,
    val importedAtMs: Long,
)

data class DeviceRosterRecord(
    val userId: String,
    val epoch: Int,
    val rosterId: String,
    val rosterCanonicalJson: String,
    val importedAtMs: Long,
)

data class MessageKeyGrantRecord(
    val messageId: String,
    val targetEncryptionKeyId: String,
    val grantCanonicalJson: String,
    val storedAtMs: Long,
)

data class ConversationRecord(
    val conversationId: String,
    val descriptorCanonicalJson: String,
    val title: String,
    val createdAtMs: Long,
)

enum class MessageDirection { OUTGOING, INCOMING }
enum class MessageLocalState { READY_FOR_ARCHIVE, ACCEPTED, ARCHIVED }

data class StoredMessageRecord(
    val messageId: String,
    val conversationId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val senderSeq: Int,
    val createdAtMs: Long,
    val parentMessageIds: List<String>,
    val envelopeCanonicalJson: String,
    val direction: MessageDirection,
    val localState: MessageLocalState,
    val storedAtMs: Long,
)

sealed interface InsertMessageResult {
    data object Inserted : InsertMessageResult
    data object Duplicate : InsertMessageResult
}

interface MessengerRepository {
    fun putContact(record: ContactDeviceRecord)
    fun contactDevices(): List<ContactDeviceRecord>

    /**
     * Forget a contact and every device of theirs.
     *
     * Contacts were append-only: there was no way, at any level, to remove one — so a mistaken
     * import, or a row left behind by a test, stayed in the address book for the life of the
     * installation. Messages are deliberately not touched: they are signed, immutable and part of
     * a DAG that recovery and migration rely on, and dropping the person you got them from is not
     * a reason to rewrite history.
     */
    fun deleteContact(userId: String)

    fun putOwnDevice(record: OwnDeviceRecord)
    fun ownDevices(): List<OwnDeviceRecord>

    fun putDeviceRoster(record: DeviceRosterRecord)
    fun deviceRosters(): List<DeviceRosterRecord>

    fun putKeyGrant(record: MessageKeyGrantRecord)
    fun keyGrants(messageId: String? = null): List<MessageKeyGrantRecord>

    fun putConversation(record: ConversationRecord)
    fun conversations(): List<ConversationRecord>

    fun insertMessage(record: StoredMessageRecord): InsertMessageResult
    fun messages(conversationId: String): List<StoredMessageRecord>
}
