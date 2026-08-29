package org.eidolang.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.crypto.IdentityCanonical
import org.eidolang.core.crypto.JvmPrivateIdentity
import org.eidolang.core.crypto.PublicIdentityBundleV1
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

/**
 * R22 SQLite v2 -> v3 sensitive-text migration, exercised on a populated legacy database.
 *
 * `R22_SQLITE_V3.md` specifies that every legacy row's plaintext `contacts.alias` and
 * `conversations.title` is encrypted in place with row-specific AAD, that reads fail closed if a
 * field is still plaintext afterwards, and that cross-row ciphertext substitution fails
 * authentication. None of that is reachable from the pure-JVM harness, and the runtime admission
 * probe only audits an already-migrated database — so this is the only place those claims are
 * actually tested.
 */
class SqliteV3MigrationTest {

    private lateinit var context: Context

    /** Must match `AndroidSqliteMessengerRepository.DB_NAME`, which is private. */
    private val dbName = "eidolang-messenger-r18.db"

    private val aliceAlias = "Алиса — основной телефон"
    private val bobAlias = "Bob's tablet"
    private val chatTitle = "Тестовая беседа №1"
    private val otherTitle = "Second conversation"

    private lateinit var alice: Row
    private lateinit var bob: Row

    private data class Row(val userId: String, val deviceId: String, val bundleJson: String)

    private fun protector() = AndroidRepositoryTextProtector(context)

    private fun openRepository() = AndroidSqliteMessengerRepository(context, protector())

    /** Where the device's own database is parked while this test owns the real path. */
    private val backup get() = java.io.File(context.cacheDir, "$dbName.preserved")

    /**
     * Take the hand-built database away again.
     *
     * It deletes in `@Before` only, which left the synthetic legacy rows in place for whatever ran
     * next — and one of them is `descriptor_json = {"conversation_id": ...}`, a stub that
     * `ConversationParser` rightly refuses. Any later screen calling `conversationSummaries()` then
     * died with `Object schema mismatch`. `connectedDebugAndroidTest` happened to hide it by running
     * `MessengerSmokeTest` first; running the classes in any other order surfaced it immediately.
     * A test may build whatever wreckage it needs, but it does not get to leave it behind.
     */
    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
        // Give the device back the database this test displaced. Without this the suite is
        // destructive on any real installation: it builds its legacy database *at the production
        // path*, so `deleteDatabase` here and in `setUp` takes the owner's contacts, conversations
        // and messages with it. That is not hypothetical — it wiped a tablet mid-session, and the
        // resulting "the sender publishes but nothing is received" looked like a delivery bug for
        // an hour. A test may own the path; it may not keep what it found there.
        if (backup.exists()) {
            backup.copyTo(context.getDatabasePath(dbName), overwrite = true)
            backup.delete()
        }
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val live = context.getDatabasePath(dbName)
        if (live.exists()) live.copyTo(backup, overwrite = true)
        context.deleteDatabase(dbName)

        val random = SecureRandom()
        alice = newRow(random)
        bob = newRow(random)
    }

    private fun newRow(random: SecureRandom): Row {
        val id = JvmPrivateIdentity.generate(random)
        return Row(
            id.user.userId,
            id.certificate.deviceId,
            IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(id.user, id.certificate)),
        )
    }

    /** Recreates the pre-R22 schema by hand: a real legacy database, not one produced by v3 code. */
    private fun createLegacyDatabase(version: Int) {
        val path = context.getDatabasePath(dbName)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contacts(
                user_id TEXT NOT NULL, device_id TEXT NOT NULL, alias TEXT NOT NULL,
                bundle_json TEXT NOT NULL, imported_at_ms INTEGER NOT NULL,
                PRIMARY KEY(user_id, device_id))
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS conversations(
                conversation_id TEXT PRIMARY KEY, descriptor_json TEXT NOT NULL,
                title TEXT NOT NULL, created_at_ms INTEGER NOT NULL)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages(
                message_id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL,
                sender_user_id TEXT NOT NULL, sender_device_id TEXT NOT NULL,
                sender_seq INTEGER NOT NULL, created_at_ms INTEGER NOT NULL,
                parent_ids TEXT NOT NULL, envelope_json TEXT NOT NULL,
                direction TEXT NOT NULL, local_state TEXT NOT NULL, stored_at_ms INTEGER NOT NULL,
                UNIQUE(conversation_id, sender_device_id, sender_seq))
            """.trimIndent()
        )
        if (version >= 2) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS own_devices(
                    user_id TEXT NOT NULL, device_id TEXT NOT NULL,
                    bundle_json TEXT NOT NULL, imported_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(user_id, device_id))
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS device_rosters(
                    user_id TEXT PRIMARY KEY, epoch INTEGER NOT NULL, roster_id TEXT NOT NULL,
                    roster_json TEXT NOT NULL, imported_at_ms INTEGER NOT NULL)
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS message_key_grants(
                    message_id TEXT NOT NULL, target_encryption_key_id TEXT NOT NULL,
                    grant_json TEXT NOT NULL, stored_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(message_id, target_encryption_key_id))
                """.trimIndent()
            )
        }

        insertLegacyContact(db, alice, aliceAlias)
        insertLegacyContact(db, bob, bobAlias)
        insertLegacyConversation(db, "cnv:1111111111111111111111111111111111111111111111111111111111111111", chatTitle)
        insertLegacyConversation(db, "cnv:2222222222222222222222222222222222222222222222222222222222222222", otherTitle)

        db.version = version
        db.close()
    }

    private fun insertLegacyContact(db: SQLiteDatabase, row: Row, alias: String) {
        db.insert("contacts", null, ContentValues().apply {
            put("user_id", row.userId); put("device_id", row.deviceId)
            put("alias", alias)                       // plaintext, as pre-R22 builds stored it
            put("bundle_json", row.bundleJson); put("imported_at_ms", 100L)
        })
    }

    private fun insertLegacyConversation(db: SQLiteDatabase, id: String, title: String) {
        db.insert("conversations", null, ContentValues().apply {
            put("conversation_id", id); put("descriptor_json", """{"conversation_id":"$id"}""")
            put("title", title)                       // plaintext
            put("created_at_ms", 100L)
        })
    }

    private fun rawColumn(db: SQLiteDatabase, table: String, column: String): List<String> {
        val out = mutableListOf<String>()
        db.rawQuery("SELECT $column FROM $table", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    @Test
    fun v2LegacyDatabaseMigratesEveryRowToProtectedV3() {
        createLegacyDatabase(version = 2)

        // Precondition: the legacy database really is plaintext, or the test proves nothing.
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null).use { legacy ->
            assertEquals(2, legacy.version)
            val aliases = rawColumn(legacy, "contacts", "alias")
            assertEquals(setOf(aliceAlias, bobAlias), aliases.toSet())
            assertTrue("legacy aliases must start out plaintext", aliases.none { it.startsWith("enc1:") })
        }

        val repo = openRepository()
        val db = repo.readableDatabase                 // triggers onUpgrade(2 -> 3)

        assertEquals("database must be at v3 after migration", 3, db.version)

        rawColumn(db, "contacts", "alias").let { stored ->
            assertEquals(2, stored.size)
            stored.forEach { assertTrue("alias left plaintext: $it", it.startsWith("enc1:")) }
        }
        rawColumn(db, "conversations", "title").let { stored ->
            assertEquals(2, stored.size)
            stored.forEach { assertTrue("title left plaintext: $it", it.startsWith("enc1:")) }
        }

        val audit = repo.sensitiveTextAudit()
        assertTrue("audit must be non-vacuous: $audit", audit.contactRows == 2 && audit.conversationRows == 2)
        assertTrue("audit reports unprotected rows: $audit", audit.pass)

        // Round-trip: the exact legacy plaintext must come back out, UTF-8 intact.
        assertEquals(
            setOf(aliceAlias, bobAlias),
            repo.contactDevices().map { it.alias }.toSet(),
        )
        assertEquals(
            setOf(chatTitle, otherTitle),
            repo.conversations().map { it.title }.toSet(),
        )
        // Non-sensitive columns must survive untouched.
        assertEquals(
            setOf(alice.bundleJson, bob.bundleJson),
            repo.contactDevices().map { it.bundleCanonicalJson }.toSet(),
        )
        repo.close()
    }

    @Test
    fun v1LegacyDatabaseGainsR20TablesAndMigrates() {
        createLegacyDatabase(version = 1)

        val repo = openRepository()
        val db = repo.readableDatabase                 // onUpgrade(1 -> 3): createR20 then migrate

        assertEquals(3, db.version)
        listOf("own_devices", "device_rosters", "message_key_grants").forEach { table ->
            db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
                it.moveToFirst()
                assertEquals("missing R20 table $table after v1 upgrade", 1, it.getInt(0))
            }
        }
        assertTrue(repo.sensitiveTextAudit().pass)
        assertEquals(setOf(aliceAlias, bobAlias), repo.contactDevices().map { it.alias }.toSet())
        repo.close()
    }

    @Test
    fun migratedRowsRejectCrossRowCiphertextSubstitution() {
        createLegacyDatabase(version = 2)
        val repo = openRepository()
        repo.readableDatabase

        // R22_SQLITE_V3.md: AAD binds each ciphertext to its own row, so moving Alice's encrypted
        // alias onto Bob's row must fail authentication rather than silently decrypt.
        val aliceCipher = repo.readableDatabase.rawQuery(
            "SELECT alias FROM contacts WHERE user_id=? AND device_id=?",
            arrayOf(alice.userId, alice.deviceId),
        ).use { it.moveToFirst(); it.getString(0) }

        // Guard against passing for the wrong reason: if migration had not encrypted this row, the
        // read below would fail closed on plaintext instead of on AAD authentication.
        assertTrue("row was not encrypted, so this proves nothing: $aliceCipher", aliceCipher.startsWith("enc1:"))

        repo.writableDatabase.update(
            "contacts",
            ContentValues().apply { put("alias", aliceCipher) },
            "user_id=? AND device_id=?",
            arrayOf(bob.userId, bob.deviceId),
        )

        try {
            repo.contactDevices()
            fail("cross-row ciphertext substitution was accepted")
        } catch (expected: Exception) {
            // authentication failure is the required outcome
        }
        repo.close()
    }

    @Test
    fun plaintextRowSurvivingMigrationFailsClosedOnRead() {
        createLegacyDatabase(version = 2)
        val repo = openRepository()
        repo.readableDatabase

        // Simulate a field that was somehow not migrated; R22 requires reads to refuse it.
        repo.writableDatabase.update(
            "contacts",
            ContentValues().apply { put("alias", "все ещё открытый текст") },
            "user_id=? AND device_id=?",
            arrayOf(bob.userId, bob.deviceId),
        )

        assertTrue("audit must notice the plaintext row", !repo.sensitiveTextAudit().pass)
        try {
            repo.contactDevices()
            fail("plaintext alias was accepted on read")
        } catch (expected: IllegalArgumentException) {
            // fail-closed, as specified
        }
        repo.close()
    }
}
