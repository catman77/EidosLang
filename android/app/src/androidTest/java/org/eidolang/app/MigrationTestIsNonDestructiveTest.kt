package org.eidolang.app

import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/** The migration fixture uses a separate database and must leave existing messenger rows intact. */
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
