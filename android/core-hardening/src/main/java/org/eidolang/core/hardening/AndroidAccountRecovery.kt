package org.eidolang.core.hardening

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import org.eidolang.core.canonical.*
import org.eidolang.core.crypto.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.vault.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore

/** Complete account recovery. Exported bytes MUST be encrypted with AccountBackupCipher. */
object AndroidAccountRecovery {
    private const val TYPE = "EidoAccountBackupV1"
    private const val DB = "eidolang-messenger-r18.db"
    private const val STAGE_DB = "eidolang-account-restore-stage.db"
    private const val PENDING = "eidolang-account-restore-pending-v1.bin"
    private const val RECOVERY_SIGN = "eidolang.account-recovery-signing.v1"
    private const val RECOVERY_ENC = "eidolang.account-recovery-decrypt.v1"
    private const val RECOVERY_BUNDLE = "eidolang-account-recovery-device-v1.json"
    private val lock = Any()

    fun currentIdentity(context: Context): DevicePrivateCrypto? =
        AndroidKeystoreIdentityStore(context).loadIfPresent() ?: AndroidSecondaryDeviceIdentityStore(context).load()

    fun isFullPrimaryRecoverable(context: Context, identity: DevicePrivateCrypto): Boolean =
        AndroidRecoverableKeyStore(context).export(bundle(identity), AndroidKeystoreIdentityStore.DEVICE_SIGN_ALIAS,
            AndroidKeystoreIdentityStore.DEVICE_ENC_ALIAS, AndroidKeystoreIdentityStore.ROOT_ALIAS) != null

    fun canPrepareBackup(context: Context, identity: DevicePrivateCrypto): Boolean =
        AndroidKeystoreIdentityStore(context).exists() || AndroidRecoverableKeyStore(context).export(
            bundle(identity), AndroidSecondaryDeviceIdentityStore.SIGN_ALIAS, AndroidSecondaryDeviceIdentityStore.ENC_ALIAS) != null

    private fun bundle(identity: DevicePrivateCrypto) = IdentityCanonical.publicBundleJson(
        PublicIdentityBundleV1(identity.user, identity.certificate))

    /** Legacy non-exportable roots authorize a recovery device while the root is still available. */
    private fun recoveryIdentity(context: Context, identity: DevicePrivateCrypto): PortableIdentity {
        val keys = AndroidRecoverableKeyStore(context)
        val raw = bundle(identity)
        val primary = AndroidKeystoreIdentityStore(context).exists()
        val direct = if (primary) keys.export(raw, AndroidKeystoreIdentityStore.DEVICE_SIGN_ALIAS,
            AndroidKeystoreIdentityStore.DEVICE_ENC_ALIAS, AndroidKeystoreIdentityStore.ROOT_ALIAS)
        else keys.export(raw, AndroidSecondaryDeviceIdentityStore.SIGN_ALIAS, AndroidSecondaryDeviceIdentityStore.ENC_ALIAS)
        if (direct != null) return direct
        check(primary) { "Ключи этого старого дополнительного устройства не переносимы. Подготовьте копию на основном устройстве." }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val file = context.filesDir.resolve(RECOVERY_BUNDLE)
        val recoveryRaw = if (file.exists()) file.readText() else {
            if (!ks.containsAlias(RECOVERY_SIGN) || !ks.containsAlias(RECOVERY_ENC)) {
                if (!keys.repairIfSaved(listOf(RECOVERY_SIGN, RECOVERY_ENC))) keys.createDevice(RECOVERY_SIGN, RECOVERY_ENC)
            }
            val body = IdentityFactory.deviceBody(identity.user, ks.getCertificate(RECOVERY_SIGN).publicKey,
                ks.getCertificate(RECOVERY_ENC).publicKey)
            val bytes = IdentityCanonical.deviceBodyJson(body).toByteArray(Charsets.UTF_8)
            val cert = DeviceCertificateV1(body, "dev:" + HexSha256.of(bytes),
                B64Url.encode(AndroidRootAuthority().signRoot(bytes)))
            IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(identity.user, cert)).also {
                atomicWrite(file, it.toByteArray(Charsets.UTF_8))
            }
        }
        val recovery = keys.export(recoveryRaw, RECOVERY_SIGN, RECOVERY_ENC)
            ?: error("Не найдены подготовленные ключи восстановления")
        check(recovery.user == identity.user)
        val repo = AndroidMessengerRepositoryProvider.get(context)
        val existingRoster = repo.deviceRosters().firstOrNull { it.userId == identity.user.userId }
            ?.rosterCanonicalJson?.let(DeviceRosterCanonical::parseCanonical)
        if (repo.ownDevices().any { it.deviceId == recovery.certificate.deviceId } &&
            recovery.certificate.deviceId in existingRoster?.body?.activeDeviceIds.orEmpty()) return recovery
        val service = LocalMessengerService(repo, identity)
        service.importOwnDevice(recoveryRaw, System.currentTimeMillis())
        val previous = service.latestRoster(identity.user.userId)
        check(recovery.certificate.deviceId !in previous?.body?.revokedDeviceIds.orEmpty()) {
            "Устройство восстановления было отозвано владельцем"
        }
        if (recovery.certificate.deviceId !in previous?.body?.activeDeviceIds.orEmpty()) {
            val active = previous?.body?.activeDeviceIds.orEmpty() + identity.certificate.deviceId + recovery.certificate.deviceId
            val roster = DeviceRosterAuthority.issue(AndroidRootAuthority(), previous, active,
                previous?.body?.revokedDeviceIds.orEmpty())
            service.importDeviceRoster(DeviceRosterCanonical.json(roster), System.currentTimeMillis())
        }
        return recovery
    }

    fun snapshot(context: Context, identity: DevicePrivateCrypto): ByteArray = synchronized(lock) {
        val portable = recoveryIdentity(context, identity)
        val repo = AndroidMessengerRepositoryProvider.get(context)
        val mirror = InMemoryMessengerRepository()
        val db = repo.readableDatabase
        db.beginTransactionNonExclusive()
        try { copyRepository(repo, mirror) } finally { db.endTransaction() }
        val activeRaw = AndroidHistoryVaultPackageStore(context).loadActiveCanonical()
        val oldHistory = activeRaw?.let {
            val oldPkg = HistoryVaultArchivePackageCodec.parseCanonical(it)
            val descriptor = HistoryVaultDescriptorCodec.parseCanonical(oldPkg.vaultDescriptorCanonicalJson)
            val secret = AndroidHistoryVaultSecretStore(context).load(descriptor.vaultId)
                ?: error("Не найден ключ прежней восстановленной истории")
            HistoryVaultDocumentProvider(it, secret)
        }
        val secret = AndroidBootstrapRecoverySecret.forOnion(context)
        val history = try {
            val service = LocalMessengerService(mirror, identity, historicalDocumentProvider = oldHistory)
            val controller = HistoryVaultController.create(identity, secret)
            controller.createEpoch(service.latestRoster(identity.user.userId)?.body?.activeDeviceIds
                ?: listOf(identity.certificate.deviceId))
            controller.archiveAll(mirror, service)
            controller.exportPackage(mirror)
        } finally { oldHistory?.close() }
        val files = JSONArray()
        val box = AndroidLocalSecretBox(context)
        selectedFiles(context.filesDir).forEach { file ->
            val path = file.relativeTo(context.filesDir).invariantSeparatorsPath
            val scope = scope(path)
            val raw = if (scope == null) file.readBytes()
                else box.open(scope.first, scope.second, file.readText(Charsets.UTF_8))
            try { files.put(JSONObject().put("path", path).put("data", B64Url.encode(raw))) }
            finally { raw.fill(0) }
        }
        val rawSecret = secret.copyBytes()
        try {
            val out = JSONObject().put("type", TYPE).put("version", 1)
                .put("created_at_ms", System.currentTimeMillis()).put("identity", portable.json())
                .put("history", history).put("history_secret", B64Url.encode(rawSecret)).put("files", files)
                .toString().toByteArray(Charsets.UTF_8)
            check(out.size <= AccountBackupCipher.MAX_BYTES - 100)
            validate(out).close()
            out
        } finally { rawSecret.fill(0) }
    }

    class Validated internal constructor(
        val identity: PortableIdentity, val history: String, val secret: HistoryVaultRecoverySecret,
        val files: Map<String, ByteArray>, val createdAtMs: Long, val messageCount: Int,
    ) {
        fun close() { files.values.forEach { it.fill(0) } }
    }

    fun validate(plaintext: ByteArray): Validated {
        require(plaintext.size <= AccountBackupCipher.MAX_BYTES)
        val text = plaintext.toString(Charsets.UTF_8)
        StrictJsonParser(text).parse() // reject duplicate keys before using Android's JSONObject
        val obj = JSONObject(text)
        require(obj.keys().asSequence().toSet() == setOf("type", "version", "created_at_ms", "identity", "history", "history_secret", "files"))
        require(obj.getString("type") == TYPE && obj.getInt("version") == 1)
        val identity = PortableIdentity.parse(obj.getString("identity"))
        val history = obj.getString("history")
        val pkg = HistoryVaultArchivePackageCodec.parseCanonical(history)
        require(pkg.ownerUserId == identity.user.userId)
        require(pkg.ownDeviceBundleCanonicalJson.any {
            IdentityParser.parsePublicBundleCanonical(it).device == identity.certificate
        }) { "Ключи восстановления не включены в подписанный архив" }
        val roster = pkg.deviceRosterCanonicalJson.map(DeviceRosterCanonical::parseCanonical)
            .firstOrNull { it.body.userId == identity.user.userId }
        require(roster == null || identity.certificate.deviceId in roster.body.activeDeviceIds) { "Устройство восстановления отозвано" }
        if (identity.rootPrivateB64 == null) require(roster != null)
        val bytes = B64Url.decode(obj.getString("history_secret"))
        val secret = try { HistoryVaultRecoverySecret.fromBytes(bytes) } finally { bytes.fill(0) }
        val mirror = InMemoryMessengerRepository()
        val restored = HistoryVaultRecoveryCoordinator(mirror, identity).restore(history, secret, obj.getLong("created_at_ms"))
        try {
            val service = LocalMessengerService(mirror, identity, historicalDocumentProvider = restored.documentProvider)
            mirror.conversations().forEach { service.timeline(it.conversationId) }
        } finally { restored.documentProvider.close() }
        val files = linkedMapOf<String, ByteArray>()
        val items = obj.getJSONArray("files")
        require(items.length() <= 10_000)
        try {
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                require(item.keys().asSequence().toSet() == setOf("path", "data"))
                val path = item.getString("path")
                checkPath(path)
                require(path !in files)
                files[path] = B64Url.decode(item.getString("data"))
            }
            val profile = JSONObject(files.getValue("eidolang-profile-v1.bin").toString(Charsets.UTF_8))
            require(profile.getString("nickname").isNotBlank())
            require(profile.getString("avatar_png_b64").length <= 4 * 1024 * 1024)
            files.filterKeys { it.startsWith("onion/") }.forEach { (path, bytes) ->
                val card = StrictJsonParser(bytes.toString(Charsets.UTF_8)).parse() as JValue.Obj
                val owner = IdentityParser.parsePublicBundleCanonical(card.fields["identity"]?.let(CanonicalReserialize::of)
                    ?: bytes.toString(Charsets.UTF_8))
                require(owner.user == identity.user)
                if (path.startsWith("onion/card/")) require(File(path).name == HexSha256.of(bytes) + ".json")
            }
            files["eidolang-archive-publisher-v1.bin"]?.let { require(it.size == 32) }
            return Validated(identity, history, secret, files, obj.getLong("created_at_ms"), pkg.entryCanonicalJson.size)
        } catch (t: Throwable) { files.values.forEach { it.fill(0) }; throw t }
    }

    fun restore(context: Context, encoded: ByteArray, password: CharArray): DevicePrivateCrypto = synchronized(lock) {
        val plaintext = AccountBackupCipher.decrypt(encoded, password)
        try {
            validate(plaintext).close()
            check(!pending(context).exists()) { "Сначала завершите начатое восстановление" }
            check(currentIdentity(context) == null && !AndroidKeystoreIdentityStore(context).hasSavedProfile() &&
                !context.filesDir.resolve("eidolang-secondary-device-bundle-v1.json").exists()) {
                "В этой установке уже есть профиль или сохранённые данные. Они не будут перезаписаны."
            }
            val sealed = AndroidLocalSecretBox(context).seal("account-restore", "pending", plaintext)
            atomicWrite(pending(context), sealed.toByteArray(Charsets.US_ASCII))
            resumePending(context) ?: error("Восстановление не завершено")
        } finally { plaintext.fill(0) }
    }

    /** Idempotent journal: a crash at any commit step resumes before opening the normal app. */
    fun resumePending(context: Context): DevicePrivateCrypto? = synchronized(lock) {
        val pending = pending(context)
        if (!pending.exists()) return@synchronized null
        val box = AndroidLocalSecretBox(context)
        val plain = box.open("account-restore", "pending", pending.readText(Charsets.US_ASCII))
        val data = try { validate(plain) } finally { plain.fill(0) }
        try {
            val root = context.noBackupFilesDir.resolve("account-restore-stage").apply { mkdirs() }
            val stageFiles = root.resolve("files").apply { mkdirs() }
            val stage = object : ContextWrapper(context) {
                override fun getFilesDir(): File = stageFiles
                override fun getApplicationContext(): Context = this
            }
            data.files.forEach { (path, bytes) ->
                val file = stageFiles.resolve(path).apply { parentFile!!.mkdirs() }
                val scope = scope(path)
                atomicWrite(file, if (scope == null) bytes else box.seal(scope.first, scope.second, bytes).toByteArray(Charsets.US_ASCII))
            }
            val descriptor = HistoryVaultDescriptorCodec.parseCanonical(
                HistoryVaultArchivePackageCodec.parseCanonical(data.history).vaultDescriptorCanonicalJson)
            AndroidHistoryVaultSecretStore(stage).save(descriptor.vaultId, data.secret)
            AndroidHistoryVaultSecretStore(stage).save("0".repeat(64), data.secret)
            AndroidHistoryVaultPackageStore(stage).saveActive(data.history)
            if (data.identity.rootPrivateB64 == null) {
                atomicWrite(stageFiles.resolve("eidolang-secondary-device-bundle-v1.json"), data.identity.bundleCanonical.toByteArray())
                val exporter = HistoryVaultArchivePackageCodec.parseCanonical(data.history).exporterIdentityBundleCanonicalJson
                atomicWrite(stageFiles.resolve("eidolang-enrollment-owner-v1.json"), exporter.toByteArray())
            }
            val keys = if (data.identity.rootPrivateB64 != null) mapOf(
                AndroidKeystoreIdentityStore.ROOT_ALIAS to data.identity.rootPair()!!,
                AndroidKeystoreIdentityStore.DEVICE_SIGN_ALIAS to data.identity.signingPair(),
                AndroidKeystoreIdentityStore.DEVICE_ENC_ALIAS to data.identity.encryptionPair())
            else mapOf(AndroidSecondaryDeviceIdentityStore.SIGN_ALIAS to data.identity.signingPair(),
                AndroidSecondaryDeviceIdentityStore.ENC_ALIAS to data.identity.encryptionPair())
            AndroidRecoverableKeyStore(context).install(keys)
            AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context), STAGE_DB).use { repo ->
                val recovered = HistoryVaultRecoveryCoordinator(repo, data.identity).restore(data.history, data.secret, data.createdAtMs)
                recovered.documentProvider.close()
            }
            // A closed SQLite helper checkpoints the stage. Only this explicitly staged database is moved.
            Files.move(context.getDatabasePath(STAGE_DB).toPath(), context.getDatabasePath(DB).toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(context.getDatabasePath(DB).parentFile!!)
            stageFiles.walkTopDown().filter { it.isFile }.forEach { staged ->
                val relative = staged.relativeTo(stageFiles)
                val target = context.filesDir.resolve(relative).apply { parentFile!!.mkdirs() }
                atomicWrite(target, staged.readBytes())
            }
            if (data.identity.rootPrivateB64 != null) {
                val body = IdentityCanonical.deviceBodyJson(data.identity.certificate.body)
                check(context.getSharedPreferences("eidolang-public-identity-v1", Context.MODE_PRIVATE).edit()
                    .putString("device_certificate_body_hash", HexSha256.ofUtf8(body))
                    .putString("device_certificate_root_signature", data.identity.certificate.rootSignatureB64).commit())
            }
            val restoredIdentity = currentIdentity(context) ?: error("Ключи профиля не установлены")
            check(restoredIdentity.user == data.identity.user && restoredIdentity.certificate == data.identity.certificate)
            check(pending.delete()) { "Не удалось завершить журнал восстановления" }
            syncDirectory(pending.parentFile!!)
            restoredIdentity
        } finally { data.close() }
    }

    private fun pending(context: Context) = context.noBackupFilesDir.resolve(PENDING)

    fun copyRepository(source: MessengerRepository, target: MessengerRepository) {
        source.contactDevices().forEach(target::putContact)
        source.ownDevices().forEach(target::putOwnDevice)
        source.deviceRosters().forEach(target::putDeviceRoster)
        source.keyGrants().forEach(target::putKeyGrant)
        source.conversations().forEach { c ->
            target.putConversation(c); source.messages(c.conversationId).forEach(target::insertMessage)
        }
    }

    private fun selectedFiles(root: File): List<File> = root.walkTopDown().filter { file ->
        file.isFile && allowed(file.relativeTo(root).invariantSeparatorsPath)
    }.sortedBy { it.path }.toList()

    private fun allowed(path: String) = path == "eidolang-profile-v1.bin" || path == "eidolang-archive-publisher-v1.bin" ||
        path in setOf("relay-host.txt", "directory-listed.flag", "directory-confirmed.txt") ||
        path.matches(Regex("eidogram-draft-[0-9a-f]{20}\\.bin")) ||
        path.matches(Regex("(eidogram-catalog|contact-avatars|contact-publishers|delivery-ledger)/[A-Za-z0-9._-]{1,160}\\.bin")) ||
        path.matches(Regex("onion/card/[0-9a-f]{64}\\.json")) || path == "onion/current-card.json" ||
        path.matches(Regex("contact-onion/[0-9a-f]{64}\\.txt"))

    private fun checkPath(path: String) {
        require(allowed(path) && path.split('/').none { it == "." || it == ".." }) { "Недопустимый файл в копии" }
    }

    private fun scope(path: String): Pair<String, String>? = when {
        path == "eidolang-profile-v1.bin" -> "profile" to "local-profile-v1"
        path == "eidolang-archive-publisher-v1.bin" -> "archive-publisher" to "bep44-ed25519-v1"
        path.startsWith("eidogram-draft-") -> "eidogram-draft" to path
        path.startsWith("eidogram-catalog/") -> "eidogram-catalog" to File(path).name.removeSuffix(".bin")
        path.startsWith("contact-avatars/") -> "contact-avatar" to File(path).name.removeSuffix(".bin")
        path.startsWith("contact-publishers/") -> "contact-publisher" to File(path).name.removeSuffix(".bin")
        path.startsWith("delivery-ledger/") -> "delivery-ledger" to File(path).name.removeSuffix(".bin")
        else -> null
    }

    internal fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output); syncDirectory(file.parentFile!!) }
        catch (t: Throwable) { atomic.failWrite(output); throw t }
    }

    private fun syncDirectory(directory: File) {
        val fd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
}
