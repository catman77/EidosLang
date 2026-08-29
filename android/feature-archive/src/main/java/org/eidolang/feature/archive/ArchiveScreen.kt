package org.eidolang.feature.archive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.eidolang.core.crypto.DevicePrivateCrypto
import org.eidolang.core.hardening.*
import org.eidolang.core.recovery.*
import org.eidolang.core.repository.*
import org.eidolang.core.vault.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    archive: ArchiveCoordinator,
    messenger: LocalMessengerService,
    repository: MessengerRepository,
    identity: DevicePrivateCrypto,
    onSecureRecoveryInstalled: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val packageStore=remember{AndroidHistoryVaultPackageStore(context)}
    val secretStore=remember{AndroidHistoryVaultSecretStore(context)}
    val anchorStore=remember{AndroidVaultRollbackAnchorStore(context)}
    val cutoverStore=remember{AndroidLegacyCutoverReceiptStore(context)}

    var revision by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var recoveryCode by remember { mutableStateOf("") }
    var pendingBackupExport by remember { mutableStateOf<String?>(null) }
    var pendingBackupCode by remember { mutableStateOf<String?>(null) }
    var restoreVaultRaw by remember { mutableStateOf<String?>(null) }
    var restoreBackupRaw by remember { mutableStateOf<String?>(null) }
    var allowUnprovenFreshness by remember { mutableStateOf(false) }

    val summaries = remember(revision) { archive.summaries() }
    val titles = remember(revision) {
        messenger.conversationSummaries().associate { it.conversationId to it.title }
    }
    val activeVault = remember(revision) {
        packageStore.loadActiveCanonical()?.let {
            runCatching { HistoryVaultArchivePackageCodec.parseCanonical(it) }.getOrNull()
        }
    }

    fun refresh() { revision++ }

    fun read(uri:android.net.Uri):String =
        context.contentResolver.openInputStream(uri)!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun currentActiveDevices():List<String> =
        messenger.latestRoster(identity.user.userId)?.body?.activeDeviceIds
            ?.takeIf{it.isNotEmpty()}
            ?: listOf(identity.certificate.deviceId)

    fun loadOrCreateVault():Pair<HistoryVaultController,HistoryVaultRecoverySecret>{
        val activeRaw=packageStore.loadActiveCanonical()
        if(activeRaw!=null){
            val pkg=HistoryVaultArchivePackageCodec.parseCanonical(activeRaw)
            val descriptor=HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
            val secret=secretStore.load(descriptor.vaultId)
                ?: error("Local history-vault recovery secret is unavailable")
            val controller=HistoryVaultController.fromPackage(activeRaw,secret,identity)
            val active=currentActiveDevices().sorted()
            if(controller.currentEpoch().body.activeDeviceIds!=active){
                controller.createEpoch(active)
            }
            return controller to secret
        }

        // The bootstrap secret, not a fresh one. The onion address is derived from it, so generating
        // a new secret here would change the address at the moment a vault is created and quietly
        // break every contact card handed out before that.
        val secret=AndroidBootstrapRecoverySecret.ensure(context)
        val controller=HistoryVaultController.create(identity,secret)
        controller.createEpoch(currentActiveDevices())
        secretStore.save(controller.descriptor.vaultId,secret)
        return controller to secret
    }

    val legacyExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                archive.archiveAll(System.currentTimeMillis())
                val canonical = archive.exportPackageCanonical()
                context.contentResolver.openOutputStream(uri)!!.use { out ->
                    out.write(canonical.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess { status = "Legacy R19 архив экспортирован" }
                .onFailure { status = "Ошибка экспорта legacy архива: ${it.message}" }
        }
    }

    val legacyImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val canonical = read(uri)
                packageStore.activeVaultId()?.let(cutoverStore::load)?.let { receipt ->
                    LegacyCutover.rejectCommittedLegacy(
                        canonical.toByteArray(Charsets.UTF_8),receipt
                    )
                }
                archive.importAndRestorePackage(canonical, System.currentTimeMillis())
            }.onSuccess {
                status = "Legacy восстановление: ${it.uniqueMessageCount} сообщений, ${it.segmentCount} сегментов"
                refresh()
            }.onFailure { status = "Legacy архив отклонён: ${it.message}" }
        }
    }

    val secureExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ){uri->
        if(uri!=null){
            runCatching{
                val (controller,_) = loadOrCreateVault()
                controller.archiveAll(repository,messenger)
                val raw=controller.exportPackage(repository)
                val pkg=packageStore.saveActive(raw)
                val admission=VaultRollbackGuard.inspect(
                    raw,
                    anchorStore.load(controller.descriptor.vaultId)
                )
                anchorStore.save(admission.nextAnchor)
                context.contentResolver.openOutputStream(uri)!!.use{
                    it.write(raw.toByteArray(Charsets.UTF_8))
                }
                pkg
            }.onSuccess{
                status="R20.2 secure vault экспортирован: ${it.entryCanonicalJson.size} записей"
                refresh()
            }.onFailure{status="Secure vault export отклонён: ${it.message}"}
        }
    }

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ){uri->
        val raw=pendingBackupExport
        if(uri!=null && raw!=null){
            runCatching{
                context.contentResolver.openOutputStream(uri)!!.use{
                    it.write(raw.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess{status="Encrypted recovery backup экспортирован; код храните отдельно"}
             .onFailure{status="Ошибка экспорта recovery backup: ${it.message}"}
        }
    }

    val restoreVaultImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            runCatching{
                val raw=read(uri)
                HistoryVaultArchivePackageCodec.parseCanonical(raw)
                raw
            }.onSuccess{
                restoreVaultRaw=it
                allowUnprovenFreshness=false
                status="Vault package проверен. Выберите encrypted recovery backup."
            }.onFailure{status="Vault package отклонён: ${it.message}"}
        }
    }

    val restoreBackupImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            runCatching{
                val raw=read(uri)
                RecoveryBackupCodec.parseCanonical(raw)
                raw
            }.onSuccess{
                restoreBackupRaw=it
                status="Recovery backup проверен. Введите отдельный recovery code."
            }.onFailure{status="Recovery backup отклонён: ${it.message}"}
        }
    }

    val legacyMigrationImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            runCatching{
                val legacyRaw=read(uri)
                packageStore.activeVaultId()?.let(cutoverStore::load)?.let { receipt ->
                    LegacyCutover.rejectCommittedLegacy(
                        legacyRaw.toByteArray(Charsets.UTF_8),receipt
                    )
                }
                // First prove the old package is recoverable under the current legacy boundary.
                val sourceRepo=InMemoryMessengerRepository()
                val sourceArchive=InMemoryArchiveStore()
                ArchiveCoordinator(sourceRepo,sourceArchive,identity)
                    .importAndRestorePackage(legacyRaw,System.currentTimeMillis())
                val sourceService=LocalMessengerService(sourceRepo,identity)
                val sourceDigest=HistoryEquivalence.compute(sourceRepo,sourceService)

                val migrated=LegacyArchiveVaultMigrator.migrateR19SameDevice(
                    legacyRaw,identity,storedAtMs=System.currentTimeMillis()
                )
                val (_,secret)=RecoverySecretExportCodec.parseCanonical(
                    migrated.recoverySecretExportCanonicalJson
                )

                val verifyRepo=InMemoryMessengerRepository()
                val verifyState=HistoryVaultRecoveryCoordinator(verifyRepo,identity)
                    .restore(migrated.packageCanonicalJson,secret,System.currentTimeMillis())
                val verifyService=LocalMessengerService(
                    verifyRepo,identity,historicalDocumentProvider=verifyState.documentProvider
                )
                val verifyDigest=HistoryEquivalence.compute(verifyRepo,verifyService)

                val receipt=LegacyCutover.issue(
                    listOf("R19_PACKAGE" to legacyRaw.toByteArray(Charsets.UTF_8)),
                    migrated.packageCanonicalJson,sourceDigest,verifyDigest,identity
                )
                require(LegacyCutover.verify(
                    receipt,
                    org.eidolang.core.crypto.PublicIdentityBundleV1(identity.user,identity.certificate)
                ))

                packageStore.saveActive(migrated.packageCanonicalJson)
                secretStore.save(migrated.vaultId,secret)
                val admission=VaultRollbackGuard.inspect(migrated.packageCanonicalJson,null)
                anchorStore.save(admission.nextAnchor)
                cutoverStore.save(receipt)

                val backup=RecoveryBackupCrypto.create(migrated.vaultId,secret)
                pendingBackupExport=RecoveryBackupCodec.json(backup.backup)
                pendingBackupCode=RecoveryBackupCodeCodec.encode(migrated.vaultId,backup.recoveryCode)
                onSecureRecoveryInstalled()
                Triple(migrated.migratedMessageCount,migrated.vaultId,receipt.cutoverId)
            }.onSuccess{
                status="Legacy→R20.2 миграция проверена (${it.first} сообщений). " +
                    "Экспортируйте recovery backup и удалите исходный R19 файл вручную. Cutover ${it.third.take(12)}…"
                refresh()
            }.onFailure{status="Legacy migration отклонена: ${it.message}"}
        }
    }

    fun performSecureRestore(){
        val vaultRaw=restoreVaultRaw ?: run{
            status="Сначала импортируйте vault package";return
        }
        val backupRaw=restoreBackupRaw ?: run{
            status="Сначала импортируйте encrypted recovery backup";return
        }
        runCatching{
            val pkg=HistoryVaultArchivePackageCodec.parseCanonical(vaultRaw)
            val descriptor=HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
            val backup=RecoveryBackupCodec.parseCanonical(backupRaw)
            require(backup.body.vaultId==descriptor.vaultId){"Backup belongs to another vault"}
            val (codeVault,code)=RecoveryBackupCodeCodec.decode(recoveryCode.trim())
            require(codeVault==descriptor.vaultId){"Recovery code belongs to another vault"}
            val secret=RecoveryBackupCrypto.open(backup,code)

            val previous=anchorStore.load(descriptor.vaultId)
            val admission=VaultRollbackGuard.inspect(vaultRaw,previous)
            if(admission.status==VaultFreshnessStatus.BOOTSTRAP_FRESHNESS_UNPROVEN &&
                !allowUnprovenFreshness){
                error("FRESHNESS_UNPROVEN_CONFIRMATION_REQUIRED")
            }

            HistoryVaultRecoveryCoordinator(repository,identity)
                .restore(vaultRaw,secret,System.currentTimeMillis())
            packageStore.saveActive(vaultRaw)
            secretStore.save(descriptor.vaultId,secret)
            anchorStore.save(admission.nextAnchor)
            onSecureRecoveryInstalled()
            admission.status
        }.onSuccess{
            status=when(it){
                VaultFreshnessStatus.MONOTONIC_EXTENSION ->
                    "Secure history восстановлена как монотонное расширение локального checkpoint."
                VaultFreshnessStatus.BOOTSTRAP_FRESHNESS_UNPROVEN ->
                    "Secure history восстановлена. Свежесть архива не доказана внешним checkpoint."
            }
            refresh()
        }.onFailure{
            if(it.message=="FRESHNESS_UNPROVEN_CONFIRMATION_REQUIRED"){
                status="FRESHNESS UNPROVEN: подпись подтверждает подлинность, но fresh offline device " +
                    "не может доказать, что это последняя копия. Подтвердите восстановление явно."
            }else{
                status="Secure restore отклонён: ${it.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Архив и восстановление") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
        modifier = modifier,
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Forward-secure history vault",style=MaterialTheme.typography.titleMedium)
                Text(
                    if(activeVault==null)
                        "Secure vault ещё не создан."
                    else
                        "Активный vault: ${activeVault.packageId.take(16)}… · ${activeVault.entryCanonicalJson.size} записей",
                    style=MaterialTheme.typography.bodySmall
                )
            }

            status?.let {
                item {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick={
                        secureExportLauncher.launch("eidolang-history-vault.json")
                    }){Text("Secure export")}
                    OutlinedButton(onClick={
                        runCatching{
                            val raw=packageStore.loadActiveCanonical()
                                ?: error("Сначала создайте/export secure vault")
                            val pkg=HistoryVaultArchivePackageCodec.parseCanonical(raw)
                            val descriptor=HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
                            val secret=secretStore.load(descriptor.vaultId)
                                ?: error("Local recovery secret unavailable")
                            val pair=RecoveryBackupCrypto.create(descriptor.vaultId,secret)
                            pendingBackupExport=RecoveryBackupCodec.json(pair.backup)
                            pendingBackupCode=RecoveryBackupCodeCodec.encode(descriptor.vaultId,pair.recoveryCode)
                        }.onSuccess{
                            backupExportLauncher.launch("eidolang-recovery-backup.json")
                        }.onFailure{status="Recovery backup не создан: ${it.message}"}
                    }){Text("Recovery backup")}
                }
            }

            pendingBackupCode?.let{code->
                item{
                    ElevatedCard{
                        Column(Modifier.padding(12.dp)){
                            Text("Recovery code — секретный второй канал",style=MaterialTheme.typography.titleSmall)
                            Text(code,style=MaterialTheme.typography.bodySmall)
                            Text(
                                "Не храните этот код рядом с encrypted backup file.",
                                style=MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            item { HorizontalDivider() }
            item {
                Text("Secure restore",style=MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick={restoreVaultImport.launch(arrayOf("application/json"))}){
                        Text(if(restoreVaultRaw==null)"Vault package" else "Vault ✓")
                    }
                    OutlinedButton(onClick={restoreBackupImport.launch(arrayOf("application/json"))}){
                        Text(if(restoreBackupRaw==null)"Recovery backup" else "Backup ✓")
                    }
                }
            }
            item {
                OutlinedTextField(
                    value=recoveryCode,
                    onValueChange={recoveryCode=it},
                    label={Text("Recovery code")},
                    visualTransformation=PasswordVisualTransformation(),
                    modifier=Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(
                    enabled=restoreVaultRaw!=null && restoreBackupRaw!=null && recoveryCode.isNotBlank(),
                    onClick={performSecureRestore()}
                ){Text("Проверить и восстановить")}
            }
            if(status?.startsWith("FRESHNESS UNPROVEN")==true){
                item{
                    OutlinedButton(onClick={
                        allowUnprovenFreshness=true
                        performSecureRestore()
                    }){Text("Подтвердить restore без freshness proof")}
                }
            }

            item { HorizontalDivider() }
            item {
                Text("Переход со старого R19",style=MaterialTheme.typography.titleMedium)
                Text(
                    "Миграция проверяет эквивалентность истории и создаёт signed cutover receipt. " +
                        "Удаление внешней исходной копии остаётся отдельным действием.",
                    style=MaterialTheme.typography.bodySmall
                )
                Button(onClick={legacyMigrationImport.launch(arrayOf("application/json"))}){
                    Text("Мигрировать R19 → secure vault")
                }
            }

            item { HorizontalDivider() }
            item {
                Text("Legacy R16/R19 archive",style=MaterialTheme.typography.titleMedium)
                Text(
                    "Оставлен для совместимости/миграции. Он не является forward-secure history vault.",
                    style=MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    OutlinedButton(onClick = {
                        runCatching { archive.archiveAll(System.currentTimeMillis()) }
                            .onSuccess { created ->
                                status = if (created.isEmpty()) "Новых legacy сегментов нет"
                                else "Создано legacy сегментов: ${created.size}"
                                refresh()
                            }
                            .onFailure { status = "Ошибка legacy архивации: ${it.message}" }
                    }) { Text("Legacy archive") }
                    OutlinedButton(
                        onClick = { legacyExportLauncher.launch("eidolang-legacy-r19.json") },
                        enabled = summaries.isNotEmpty()
                    ) { Text("Legacy export") }
                    OutlinedButton(onClick = {
                        legacyImportLauncher.launch(arrayOf("application/json"))
                    }) { Text("Legacy import") }
                }
            }

            if(summaries.isNotEmpty()){
                items(summaries,key={it.segmentId}){s->
                    ListItem(
                        headlineContent={Text(titles[s.conversationId] ?: s.conversationId.take(12))},
                        supportingContent={
                            Text("${s.messageCount} сообщ. · ${s.segmentId.take(12)}… · ${s.byteLength} байт")
                        },
                        trailingContent={
                            FilterChip(
                                selected=s.pinned,
                                onClick={
                                    runCatching{archive.setPinned(s.segmentId,!s.pinned)}
                                        .onSuccess{refresh()}
                                        .onFailure{status="Ошибка pin: ${it.message}"}
                                },
                                label={Text(if(s.pinned)"Pinned" else "Pin")}
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
