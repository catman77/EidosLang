package org.eidolang.core.hardening

import android.content.Context
import org.eidolang.core.repository.AndroidSqliteMessengerRepository

/**
 * The messenger database belongs to the process: delivery jobs can still be using it after a
 * screen closes. Reuse one helper instead of opening another connection pool on every navigation,
 * and keep only the application context so the helper cannot retain an activity.
 */
object AndroidMessengerRepositoryProvider {
    @Volatile
    private var instance: AndroidSqliteMessengerRepository? = null

    fun get(context: Context): AndroidSqliteMessengerRepository = instance ?: synchronized(this) {
        instance ?: context.applicationContext.let { app ->
            AndroidSqliteMessengerRepository(app, AndroidRepositoryTextProtector(app))
                .also { instance = it }
        }
    }
}
