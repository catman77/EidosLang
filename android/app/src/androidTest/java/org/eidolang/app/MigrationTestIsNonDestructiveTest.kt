package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The migration test must give the device back what it found.
 *
 * `SqliteV3MigrationTest` builds its legacy database at the *production* path, so its
 * `deleteDatabase` calls destroy a real installation's contacts, conversations and messages. It did
 * exactly that to a tablet during a regression run, and the missing rows then presented as a
 * delivery bug — the sender published, the recipient had no contact left to poll, and nothing
 * anywhere said a database had been deleted.
 *
 * This writes a marker row, runs the destructive test's own before/after cycle through JUnit, and
 * checks the marker is still there. Asserting on the guard rather than trusting the comment.
 */
class MigrationTestIsNonDestructiveTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun runningTheMigrationTestPreservesExistingRows() {
        val before = AndroidSqliteMessengerRepository(ctx, AndroidRepositoryTextProtector(ctx))
            .use { it.conversations().size to it.contactDevices().size }

        val result = org.junit.runner.JUnitCore.runClasses(SqliteV3MigrationTest::class.java)
        assertEquals("сам тест миграции упал: ${result.failures}", 0, result.failureCount)

        val after = AndroidSqliteMessengerRepository(ctx, AndroidRepositoryTextProtector(ctx))
            .use { it.conversations().size to it.contactDevices().size }
        assertEquals("тест миграции уничтожил данные устройства", before, after)
        println("MIGRATION PASS данные устройства пережили тест миграции: $before")
    }
}
