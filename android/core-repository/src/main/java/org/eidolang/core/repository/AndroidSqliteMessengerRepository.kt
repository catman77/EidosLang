package org.eidolang.core.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.eidolang.core.crypto.HexSha256
import org.eidolang.core.message.MessageCanonical
import org.eidolang.core.message.MessageParser

class AndroidSqliteMessengerRepository(
    context: Context,
    private val textProtector: RepositoryTextProtector,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION),
    MessengerRepository {

    override fun onCreate(db: SQLiteDatabase) {
        createBase(db)
        createR20(db)
    }

    private fun createBase(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS contacts(
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                alias TEXT NOT NULL,
                bundle_json TEXT NOT NULL,
                imported_at_ms INTEGER NOT NULL,
                PRIMARY KEY(user_id, device_id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS conversations(
                conversation_id TEXT PRIMARY KEY,
                descriptor_json TEXT NOT NULL,
                title TEXT NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS messages(
                message_id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                sender_user_id TEXT NOT NULL,
                sender_device_id TEXT NOT NULL,
                sender_seq INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL,
                parent_ids TEXT NOT NULL,
                envelope_json TEXT NOT NULL,
                direction TEXT NOT NULL,
                local_state TEXT NOT NULL,
                stored_at_ms INTEGER NOT NULL,
                UNIQUE(conversation_id, sender_device_id, sender_seq)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_contacts_user ON contacts(user_id)")
    }

    private fun createR20(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS own_devices(
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                bundle_json TEXT NOT NULL,
                imported_at_ms INTEGER NOT NULL,
                PRIMARY KEY(user_id, device_id)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS device_rosters(
                user_id TEXT PRIMARY KEY,
                epoch INTEGER NOT NULL,
                roster_id TEXT NOT NULL,
                roster_json TEXT NOT NULL,
                imported_at_ms INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS message_key_grants(
                message_id TEXT NOT NULL,
                target_encryption_key_id TEXT NOT NULL,
                grant_json TEXT NOT NULL,
                stored_at_ms INTEGER NOT NULL,
                PRIMARY KEY(message_id, target_encryption_key_id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_key_grants_message ON message_key_grants(message_id)")
    }


    private fun migrateSensitiveTextToV3(db: SQLiteDatabase) {
        db.rawQuery("SELECT user_id,device_id,alias FROM contacts", null).use { c ->
            while (c.moveToNext()) {
                val userId=c.getString(0)
                val deviceId=c.getString(1)
                val alias=c.getString(2)
                if (!textProtector.isProtected(alias)) {
                    val v=ContentValues().apply {
                        put("alias", textProtector.protect("contact-alias", contactLogicalId(userId, deviceId), alias))
                    }
                    db.update(
                        "contacts",v,"user_id=? AND device_id=?",
                        arrayOf(userId,deviceId)
                    )
                }
            }
        }
        db.rawQuery("SELECT conversation_id,title FROM conversations", null).use { c ->
            while (c.moveToNext()) {
                val id=c.getString(0)
                val title=c.getString(1)
                if (!textProtector.isProtected(title)) {
                    val v=ContentValues().apply {
                        put("title", textProtector.protect("conversation-title", conversationLogicalId(id), title))
                    }
                    db.update("conversations",v,"conversation_id=?",arrayOf(id))
                }
            }
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var v = oldVersion
        if (v < 2) {
            createR20(db)
            v = 2
        }
        if (v < 3) {
            migrateSensitiveTextToV3(db)
            v = 3
        }
        require(v == newVersion) { "Unsupported DB migration $oldVersion -> $newVersion" }
    }

    override fun putContact(record: ContactDeviceRecord) {
        val v = ContentValues().apply {
            put("user_id", record.userId); put("device_id", record.deviceId)
            put("alias", textProtector.protect("contact-alias", contactLogicalId(record.userId, record.deviceId), record.alias))
            put("bundle_json", record.bundleCanonicalJson); put("imported_at_ms", record.importedAtMs)
        }
        writableDatabase.insertWithOnConflict("contacts", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun deleteContact(userId: String) {
        writableDatabase.delete("contacts", "user_id=?", arrayOf(userId))
    }

    override fun contactDevices(): List<ContactDeviceRecord> {
        val out = mutableListOf<ContactDeviceRecord>()
        readableDatabase.rawQuery("""
            SELECT user_id,device_id,alias,bundle_json,imported_at_ms FROM contacts
            ORDER BY user_id,device_id
        """.trimIndent(), null).use { c ->
            while (c.moveToNext()) {
                val userId=c.getString(0)
                val deviceId=c.getString(1)
                val storedAlias=c.getString(2)
                require(textProtector.isProtected(storedAlias)) { "Plaintext contact alias remained after v3 migration" }
                out += ContactDeviceRecord(
                    userId,deviceId,
                    textProtector.unprotect("contact-alias",contactLogicalId(userId, deviceId),storedAlias),
                    c.getString(3),c.getLong(4)
                )
            }
        }
        return out.sortedWith(compareBy<ContactDeviceRecord>{it.alias}.thenBy{it.userId}.thenBy{it.deviceId})
    }

    override fun putOwnDevice(record: OwnDeviceRecord) {
        val v = ContentValues().apply {
            put("user_id",record.userId); put("device_id",record.deviceId)
            put("bundle_json",record.bundleCanonicalJson); put("imported_at_ms",record.importedAtMs)
        }
        writableDatabase.insertWithOnConflict("own_devices",null,v,SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun ownDevices(): List<OwnDeviceRecord> {
        val out=mutableListOf<OwnDeviceRecord>()
        readableDatabase.rawQuery("""
            SELECT user_id,device_id,bundle_json,imported_at_ms FROM own_devices
            ORDER BY user_id,device_id
        """.trimIndent(),null).use { c ->
            while(c.moveToNext()) out += OwnDeviceRecord(c.getString(0),c.getString(1),c.getString(2),c.getLong(3))
        }
        return out
    }

    override fun putDeviceRoster(record: DeviceRosterRecord) {
        val old = queryOne("SELECT epoch,roster_id FROM device_rosters WHERE user_id=?", arrayOf(record.userId)) {
            it.getInt(0) to it.getString(1)
        }
        if(old!=null){
            require(record.epoch >= old.first)
            if(record.epoch==old.first) require(record.rosterId==old.second)
        }
        val v=ContentValues().apply{
            put("user_id",record.userId); put("epoch",record.epoch); put("roster_id",record.rosterId)
            put("roster_json",record.rosterCanonicalJson); put("imported_at_ms",record.importedAtMs)
        }
        writableDatabase.insertWithOnConflict("device_rosters",null,v,SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun deviceRosters(): List<DeviceRosterRecord> {
        val out=mutableListOf<DeviceRosterRecord>()
        readableDatabase.rawQuery("""
            SELECT user_id,epoch,roster_id,roster_json,imported_at_ms FROM device_rosters ORDER BY user_id
        """.trimIndent(),null).use { c ->
            while(c.moveToNext()) out += DeviceRosterRecord(c.getString(0),c.getInt(1),c.getString(2),c.getString(3),c.getLong(4))
        }
        return out
    }

    override fun putKeyGrant(record: MessageKeyGrantRecord) {
        val old = queryOne("""
            SELECT grant_json FROM message_key_grants
            WHERE message_id=? AND target_encryption_key_id=?
        """.trimIndent(), arrayOf(record.messageId,record.targetEncryptionKeyId)) { it.getString(0) }
        if(old!=null){
            val a=org.eidolang.core.multidevice.MessageKeyGrantCanonical.parseCanonical(old)
            val b=org.eidolang.core.multidevice.MessageKeyGrantCanonical.parseCanonical(record.grantCanonicalJson)
            require(a.body==b.body) { "Conflicting grant body for same message/target" }
        }
        val v=ContentValues().apply{
            put("message_id",record.messageId); put("target_encryption_key_id",record.targetEncryptionKeyId)
            put("grant_json",record.grantCanonicalJson); put("stored_at_ms",record.storedAtMs)
        }
        writableDatabase.insertWithOnConflict("message_key_grants",null,v,SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun keyGrants(messageId:String?):List<MessageKeyGrantRecord>{
        val out=mutableListOf<MessageKeyGrantRecord>()
        val sql=if(messageId==null)
            "SELECT message_id,target_encryption_key_id,grant_json,stored_at_ms FROM message_key_grants ORDER BY message_id,target_encryption_key_id"
        else
            "SELECT message_id,target_encryption_key_id,grant_json,stored_at_ms FROM message_key_grants WHERE message_id=? ORDER BY target_encryption_key_id"
        readableDatabase.rawQuery(sql,if(messageId==null)null else arrayOf(messageId)).use{c->
            while(c.moveToNext()) out += MessageKeyGrantRecord(c.getString(0),c.getString(1),c.getString(2),c.getLong(3))
        }
        return out
    }

    override fun putConversation(record: ConversationRecord) {
        val existing = queryOne("SELECT descriptor_json FROM conversations WHERE conversation_id=?", arrayOf(record.conversationId)){it.getString(0)}
        if(existing!=null) require(existing==record.descriptorCanonicalJson){"Conflicting conversation descriptor"}
        val v=ContentValues().apply{
            put("conversation_id",record.conversationId);put("descriptor_json",record.descriptorCanonicalJson)
            put("title",textProtector.protect("conversation-title",conversationLogicalId(record.conversationId),record.title))
            put("created_at_ms",record.createdAtMs)
        }
        writableDatabase.insertWithOnConflict("conversations",null,v,SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun conversations(): List<ConversationRecord> {
        val out=mutableListOf<ConversationRecord>()
        readableDatabase.rawQuery("""
            SELECT conversation_id,descriptor_json,title,created_at_ms FROM conversations
            ORDER BY created_at_ms DESC,conversation_id
        """.trimIndent(),null).use{c->
            while(c.moveToNext()){
                val id=c.getString(0)
                val storedTitle=c.getString(2)
                require(textProtector.isProtected(storedTitle)) { "Plaintext conversation title remained after v3 migration" }
                out += ConversationRecord(
                    id,c.getString(1),
                    textProtector.unprotect("conversation-title",conversationLogicalId(id),storedTitle),
                    c.getLong(3)
                )
            }
        }
        return out
    }

    override fun insertMessage(record: StoredMessageRecord): InsertMessageResult {
        writableDatabase.beginTransaction()
        try{
            val byId=queryOne("SELECT envelope_json FROM messages WHERE message_id=?",arrayOf(record.messageId)){it.getString(0)}
            if(byId!=null){
                val old=MessageParser.parseCanonical(byId)
                val incoming=MessageParser.parseCanonical(record.envelopeCanonicalJson)
                require(MessageCanonical.bodyJson(old.body)==MessageCanonical.bodyJson(incoming.body)){
                    "Same message_id with different message body"
                }
                writableDatabase.setTransactionSuccessful()
                return InsertMessageResult.Duplicate
            }
            val bySeq=queryOne("""
                SELECT message_id FROM messages WHERE conversation_id=? AND sender_device_id=? AND sender_seq=?
            """.trimIndent(),arrayOf(record.conversationId,record.senderDeviceId,record.senderSeq.toString())){it.getString(0)}
            require(bySeq==null){"Sender sequence equivocation already belongs to $bySeq"}
            val v=ContentValues().apply{
                put("message_id",record.messageId);put("conversation_id",record.conversationId)
                put("sender_user_id",record.senderUserId);put("sender_device_id",record.senderDeviceId)
                put("sender_seq",record.senderSeq);put("created_at_ms",record.createdAtMs)
                put("parent_ids",record.parentMessageIds.joinToString(","));put("envelope_json",record.envelopeCanonicalJson)
                put("direction",record.direction.name);put("local_state",record.localState.name);put("stored_at_ms",record.storedAtMs)
            }
            writableDatabase.insertOrThrow("messages",null,v)
            writableDatabase.setTransactionSuccessful()
            return InsertMessageResult.Inserted
        }finally{writableDatabase.endTransaction()}
    }

    override fun messages(conversationId:String):List<StoredMessageRecord>{
        val out=mutableListOf<StoredMessageRecord>()
        readableDatabase.rawQuery("""
            SELECT message_id,conversation_id,sender_user_id,sender_device_id,sender_seq,created_at_ms,
                   parent_ids,envelope_json,direction,local_state,stored_at_ms
            FROM messages WHERE conversation_id=?
        """.trimIndent(),arrayOf(conversationId)).use{c->
            while(c.moveToNext()) out += StoredMessageRecord(
                c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4),c.getLong(5),
                c.getString(6).takeIf{it.isNotEmpty()}?.split(",")?:emptyList(),c.getString(7),
                MessageDirection.valueOf(c.getString(8)),MessageLocalState.valueOf(c.getString(9)),c.getLong(10)
            )
        }
        return out
    }


    data class SensitiveTextAudit(
        val contactRows:Int,
        val protectedContactAliases:Int,
        val conversationRows:Int,
        val protectedConversationTitles:Int,
    ) {
        val pass:Boolean
            get() = contactRows==protectedContactAliases &&
                conversationRows==protectedConversationTitles
    }

    /**
     * Row identities such as `dev:<hex>` and the `userId|deviceId` pair contain `:` and `|`, which
     * [org.eidolang.core.hardening.AndroidLocalSecretBox] rejects because `|` is its AAD delimiter.
     * Hashing yields a delimiter-safe id while still binding the ciphertext to the logical row.
     */
    private fun contactLogicalId(userId: String, deviceId: String): String =
        HexSha256.of("$userId|$deviceId".toByteArray(Charsets.UTF_8))

    private fun conversationLogicalId(conversationId: String): String =
        HexSha256.of(conversationId.toByteArray(Charsets.UTF_8))

    fun sensitiveTextAudit():SensitiveTextAudit{
        var contacts=0
        var protectedAliases=0
        readableDatabase.rawQuery("SELECT alias FROM contacts",null).use{c->
            while(c.moveToNext()){
                contacts++
                if(textProtector.isProtected(c.getString(0)))protectedAliases++
            }
        }
        var conversations=0
        var protectedTitles=0
        readableDatabase.rawQuery("SELECT title FROM conversations",null).use{c->
            while(c.moveToNext()){
                conversations++
                if(textProtector.isProtected(c.getString(0)))protectedTitles++
            }
        }
        return SensitiveTextAudit(contacts,protectedAliases,conversations,protectedTitles)
    }

    private fun <T> queryOne(sql:String,args:Array<String>,mapper:(android.database.Cursor)->T):T? =
        readableDatabase.rawQuery(sql,args).use{c->if(c.moveToFirst())mapper(c) else null}

    companion object {
        private const val DB_NAME="eidolang-messenger-r18.db"
        private const val DB_VERSION=3
    }
}
