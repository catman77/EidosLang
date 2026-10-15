package org.eidolang.core.hardening

import android.content.Context
import android.net.Uri
import android.os.FileObserver
import android.provider.DocumentsContract
import androidx.work.*
import org.eidolang.core.crypto.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Writes a new verified generation before retiring any previous copy. */
object AccountBackups {
    private const val FILE = "eidolang-account-backup-settings-v1.bin"
    private const val PREFS = "eidolang-account-backup-status-v1"
    private const val WORK = "eidolang-account-backup"
    private val lock = Any()
    private val versionLock = Any()
    private val observers = mutableMapOf<String, FileObserver>()
    private var observingPackage: String? = null

    fun isConfigured(context: Context) = context.filesDir.resolve(FILE).isFile
    fun latestBackupUri(context: Context): Uri? {
        if (!isConfigured(context)) return null
        val owned = read(context).getJSONArray("owned")
        return if (owned.length() == 0) null else Uri.parse(owned.getString(owned.length() - 1))
    }
    fun savedAt(context: Context): Long = context.getSharedPreferences(PREFS, 0).getLong("saved_at", 0)
    fun error(context: Context): String? = context.getSharedPreferences(PREFS, 0).getString("error", null)
    fun isPending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, 0)
        return prefs.getLong("version", 0) > prefs.getLong("saved_version", -1)
    }

    fun configure(context: Context, tree: Uri, password: CharArray) = synchronized(lock) {
        require(password.size >= 12) { "Пароль копии должен содержать не менее 12 символов" }
        require(DocumentsContract.isTreeUri(tree))
        require(!DocumentsContract.getTreeDocumentId(tree).substringAfter(':').startsWith("Android/", ignoreCase = true)) {
            "Выберите папку в Documents или Download. Папки Android могут удаляться вместе с приложением."
        }
        val salt = JcaCrypto.randomBytes(16)
        val key = AccountBackupCipher.deriveKey(password, salt)
        try {
            val config = JSONObject().put("tree", tree.toString()).put("salt", B64Url.encode(salt))
                .put("key", B64Url.encode(key)).put("owned", JSONArray())
            publish(context, config)
            start(context)
        } finally { key.fill(0) }
    }

    fun backupNow(context: Context) = synchronized(lock) {
        if (!isConfigured(context)) return@synchronized
        try { publish(context, read(context)) }
        catch (t: Exception) {
            context.getSharedPreferences(PREFS, 0).edit()
                .putString("error", "Не удалось обновить копию. Проверьте доступ к папке и свободное место.").commit()
            throw t
        }
    }

    private fun publish(context: Context, config: JSONObject) {
        val identity = AndroidAccountRecovery.currentIdentity(context) ?: error("Нет доступного профиля для копирования")
        val prefs = context.getSharedPreferences(PREFS, 0)
        val version = prefs.getLong("version", 0)
        val plaintext = AndroidAccountRecovery.snapshot(context, identity)
        val key = B64Url.decode(config.getString("key"))
        val encoded = try { AccountBackupCipher.encryptWithKey(plaintext, key, B64Url.decode(config.getString("salt"))) }
        finally { plaintext.fill(0); key.fill(0) }
        val tree = Uri.parse(config.getString("tree"))
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val now = System.currentTimeMillis()
        val name = "eidolang-${identity.user.userId.take(12)}-$now-${UUID.randomUUID().toString().take(8)}.eidobackup"
        val pending = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", "$name.pending")
            ?: error("Папка не разрешает создание копии")
        // The final filename is published only after a successful, verified write.
        context.contentResolver.openOutputStream(pending, "wt")!!.use { output ->
            output.write(encoded); output.flush()
            if (output is FileOutputStream) output.fd.sync()
        }
        val readback = readDocument(context, pending)
        check(encoded.contentEquals(readback)) { "Проверка записанной копии не прошла" }
        val uri = DocumentsContract.renameDocument(context.contentResolver, pending, name)
            ?: error("Папка не поддерживает завершение записи копии")
        check(encoded.contentEquals(readDocument(context, uri))) { "Копия недоступна после завершения записи" }
        val owned = config.getJSONArray("owned")
        owned.put(uri.toString())
        save(context, config)
        check(prefs.edit().putLong("saved_version", version).putLong("saved_at", now).remove("error").commit())
        // Only URIs created and verified by this configuration are eligible for retention pruning.
        while (owned.length() > 3) {
            val old = Uri.parse(owned.getString(0))
            if (!runCatching { DocumentsContract.deleteDocument(context.contentResolver, old) }.getOrDefault(false)) break
            owned.remove(0)
            save(context, config)
        }
    }

    fun readDocument(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= AccountBackupCipher.MAX_BYTES) { "Копия слишком велика" }
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }

    private fun read(context: Context): JSONObject {
        val bytes = AndroidLocalSecretBox(context).open("account-backup-settings", "default",
            context.filesDir.resolve(FILE).readText(Charsets.US_ASCII))
        return try { JSONObject(bytes.toString(Charsets.UTF_8)) } finally { bytes.fill(0) }
    }

    private fun save(context: Context, config: JSONObject) {
        val raw = config.toString().toByteArray(Charsets.UTF_8)
        try { AndroidAccountRecovery.atomicWrite(context.filesDir.resolve(FILE),
            AndroidLocalSecretBox(context).seal("account-backup-settings", "default", raw).toByteArray(Charsets.US_ASCII)) }
        finally { raw.fill(0) }
    }

    fun request(context: Context) {
        if (!isConfigured(context)) return
        synchronized(versionLock) {
            val prefs = context.getSharedPreferences(PREFS, 0)
            check(prefs.edit().putLong("version", prefs.getLong("version", 0) + 1).commit())
        }
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<AccountBackupWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (!isConfigured(app)) return
        synchronized(observers) {
            if (observingPackage == app.packageName) return
            observingPackage = app.packageName
            fun watch(directory: File, database: Boolean = false) {
                if (!directory.isDirectory || directory.path in observers) return
                val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.CREATE or
                    (if (database) FileObserver.MODIFY else 0)
                val observer = object : FileObserver(directory.path, mask) {
                    override fun onEvent(event: Int, path: String?) {
                        path ?: return
                        if (database) {
                            if (path == "eidolang-messenger-r18.db" || path == "eidolang-messenger-r18.db-wal") request(app)
                            return
                        }
                        if (directory == app.filesDir && path in watchedDirectories &&
                            event and CREATE != 0 && File(directory, path).isDirectory) {
                            synchronized(observers) { watch(File(directory, path)) }
                        }
                        val relative = File(directory, path).relativeTo(app.filesDir).invariantSeparatorsPath
                        if (relative == "eidolang-profile-v1.bin" || relative == "eidolang-archive-publisher-v1.bin" ||
                            relative.startsWith("eidogram-draft-") || relative.substringBefore('/') in watchedDirectories ||
                            relative in setOf("relay-host.txt", "directory-listed.flag", "directory-confirmed.txt")) request(app)
                    }
                }
                observers[directory.path] = observer
                observer.startWatching()
            }
            watch(app.filesDir)
            watchedDirectories.forEach { watch(app.filesDir.resolve(it)) }
            watch(app.getDatabasePath("eidolang-messenger-r18.db").parentFile!!, database = true)
        }
        val periodic = PeriodicWorkRequestBuilder<AccountBackupWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK + "-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
        request(app)
    }

    private val watchedDirectories = setOf("eidogram-catalog", "contact-avatars", "contact-publishers", "contact-onion", "delivery-ledger", "onion")
}

class AccountBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        if (AccountBackups.isConfigured(applicationContext) &&
            (AccountBackups.isPending(applicationContext) || AccountBackups.error(applicationContext) != null))
            AccountBackups.backupNow(applicationContext)
        Result.success()
    } catch (_: Exception) { Result.retry() }
}
