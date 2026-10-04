package org.eidolang.feature.messenger

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.eidolang.core.crypto.*
import org.eidolang.core.admission.*
import org.eidolang.core.hardening.AndroidMessengerRepositoryProvider
import org.eidolang.core.multidevice.*
import org.eidolang.core.vault.*
import org.eidolang.core.repository.*
import org.eidolang.core.recovery.AndroidFileArchiveStore
import org.eidolang.core.recovery.ArchiveCoordinator
import org.eidolang.feature.archive.ArchiveScreen
import org.eidolang.feature.editor.EidoEditorScreen
import org.eidolang.feature.viewer.EidogramViewer

private sealed interface Screen {
    data object Conversations : Screen
    data object Contacts : Screen
    data object Archive : Screen
    data object Devices : Screen
    data object Diagnostics : Screen
    data class Conversation(val id: String) : Screen
    data class Compose(val id: String) : Screen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EidoMessengerApp(
    identity: DevicePrivateCrypto,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember(identity.certificate.deviceId) {
        AndroidMessengerRepositoryProvider.get(context)
    }
    var recoveryRevision by remember { mutableIntStateOf(0) }
    val historicalProvider = remember(identity.certificate.deviceId, recoveryRevision) {
        runCatching {
            val packageRaw=AndroidHistoryVaultPackageStore(context).loadActiveCanonical()
                ?: return@runCatching null
            val pkg=HistoryVaultArchivePackageCodec.parseCanonical(packageRaw)
            val descriptor=HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
            val secret=AndroidHistoryVaultSecretStore(context).load(descriptor.vaultId)
                ?: return@runCatching null
            HistoryVaultDocumentProvider(packageRaw,secret)
        }.getOrNull()
    }
    val service = remember(identity.certificate.deviceId, recoveryRevision) {
        LocalMessengerService(repo, identity, historicalDocumentProvider=historicalProvider)
    }

    // Primary installs begin with an explicit epoch-0 roster rather than relying indefinitely
    // on the legacy "no roster means active" compatibility fallback.
    LaunchedEffect(identity.certificate.deviceId) {
        val primaryStore=AndroidKeystoreIdentityStore(context)
        if(primaryStore.exists() && service.latestRoster(identity.user.userId)==null){
            runCatching{
                val root=AndroidRootAuthority()
                val roster=DeviceRosterAuthority.issue(
                    root,null,listOf(identity.certificate.deviceId),emptyList()
                )
                service.importDeviceRoster(DeviceRosterCanonical.json(roster),System.currentTimeMillis())
            }
        }
    }
    val archiveStore = remember { AndroidFileArchiveStore(context) }
    val archive = remember { ArchiveCoordinator(repo, archiveStore, identity) }

    var screen: Screen by remember { mutableStateOf(Screen.Conversations) }
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    fun touch() { refresh++ }

    when (val s = screen) {
        Screen.Conversations -> ConversationListScreen(
            service = service,
            refresh = refresh,
            status = status,
            onContacts = { screen = Screen.Contacts },
            onArchive = { screen = Screen.Archive },
            onDevices = { screen = Screen.Devices },
            onDiagnostics = { screen = Screen.Diagnostics },
            onOpen = { screen = Screen.Conversation(it) },
            onChanged = { touch() },
            onStatus = { status = it },
        )

        Screen.Archive -> ArchiveScreen(
            archive = archive,
            messenger = service,
            repository = repo,
            identity = identity,
            onSecureRecoveryInstalled = {
                recoveryRevision++
                touch()
            },
            onBack = { screen = Screen.Conversations },
            modifier = modifier,
        )

        Screen.Devices -> DeviceManagementScreen(
            service=service,
            refresh=refresh,
            onBack={screen=Screen.Conversations},
            onChanged={touch()},
            onStatus={status=it},
        )

        Screen.Diagnostics -> DiagnosticsScreen(
            identity=identity,
            repository=repo,
            onBack={screen=Screen.Conversations},
        )

        Screen.Contacts -> ContactsScreen(
            service = service,
            refresh = refresh,
            onBack = { screen = Screen.Conversations },
            onChanged = { touch() },
            onOpenConversation = { screen = Screen.Conversation(it) },
            onStatus = { status = it },
        )

        is Screen.Conversation -> ConversationScreen(
            service = service,
            conversationId = s.id,
            refresh = refresh,
            onBack = { screen = Screen.Conversations },
            onCompose = { screen = Screen.Compose(s.id) },
            onChanged = { touch() },
            onStatus = { status = it },
        )

        is Screen.Compose -> EidoEditorScreen(
            modifier = modifier,
            draftKey = "conversation:${s.id}",
            onSend = { doc ->
                service.send(s.id, doc, System.currentTimeMillis())
                touch()
                true
            },
            onClose = { screen = Screen.Conversation(s.id) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationListScreen(
    service: LocalMessengerService,
    refresh: Int,
    status: String?,
    onContacts: () -> Unit,
    onArchive: () -> Unit,
    onDevices: () -> Unit,
    onDiagnostics: () -> Unit,
    onOpen: (String) -> Unit,
    onChanged: () -> Unit,
    onStatus: (String?) -> Unit,
) {
    val context = LocalContext.current
    val summaries = remember(refresh) { service.conversationSummaries() }
    val importConversation = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)!!
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .let { service.importConversation(it, createdAtMs = System.currentTimeMillis()) }
            }.onSuccess {
                onChanged()
                onStatus("Диалог импортирован")
            }.onFailure { onStatus("Импорт диалога отклонён: ${it.message}") }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Эйдограммы") },
                actions = {
                    TextButton(onClick = onArchive) { Text("Архив") }
                    TextButton(onClick = onDevices) { Text("Устройства") }
                    TextButton(onClick = onDiagnostics) { Text("Диагностика") }
                    TextButton(onClick = { importConversation.launch(arrayOf("application/json")) }) { Text("Импорт диалога") }
                    TextButton(onClick = onContacts) { Text("Контакты") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            status?.let {
                Text(
                    it,
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summaries.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("Диалогов пока нет.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onContacts) { Text("Добавить контакт") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(summaries, key = { it.conversationId }) { c ->
                        ListItem(
                            headlineContent = { Text(c.title) },
                            supportingContent = {
                                Text(
                                    when {
                                        c.messageCount == 0 -> "Сообщений нет"
                                        c.headMessageIds.size > 1 -> "${c.messageCount} сообщений · ${c.headMessageIds.size} ветви"
                                        else -> "${c.messageCount} сообщений"
                                    }
                                )
                            },
                            modifier = Modifier.clickable { onOpen(c.conversationId) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactsScreen(
    service: LocalMessengerService,
    refresh: Int,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onStatus: (String?) -> Unit,
) {
    val context = LocalContext.current
    val contacts = remember(refresh) { service.contactSummaries() }

    val importContact = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)!!
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .let { service.importContact(it, importedAtMs = System.currentTimeMillis()) }
            }.onSuccess {
                onChanged()
                onStatus("Контакт ${it.alias} импортирован")
            }.onFailure { onStatus("Импорт контакта отклонён: ${it.message}") }
        }
    }

    val exportMe = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use {
                    it.write(service.exportLocalIdentityCanonical().toByteArray(Charsets.UTF_8))
                }
            }.onSuccess { onStatus("Публичный контакт экспортирован") }
             .onFailure { onStatus("Ошибка экспорта: ${it.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Контакты") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = {
                    TextButton(onClick = {
                        exportMe.launch("eidolang-contact-${service.localIdentity.user.userId.take(12)}.json")
                    }) { Text("Мой контакт") }
                    TextButton(onClick = { importContact.launch(arrayOf("application/json")) }) { Text("Импорт") }
                },
            )
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Импортируйте публичный identity bundle другого пользователя.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { importContact.launch(arrayOf("application/json")) }) { Text("Импорт контакта") }
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(contacts, key = { it.userId }) { c ->
                    ListItem(
                        headlineContent = { Text(c.alias) },
                        supportingContent = { Text("${c.deviceCount} устройство · ${c.userId.take(16)}…") },
                        trailingContent = {
                            TextButton(onClick = {
                                runCatching {
                                    service.createConversation(
                                        remoteUserIds = listOf(c.userId),
                                        createdAtMs = System.currentTimeMillis(),
                                    )
                                }.onSuccess {
                                    onChanged()
                                    onOpenConversation(it.conversationId)
                                }.onFailure { onStatus("Не удалось создать диалог: ${it.message}") }
                            }) { Text("Диалог") }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(
    service: LocalMessengerService,
    conversationId: String,
    refresh: Int,
    onBack: () -> Unit,
    onCompose: () -> Unit,
    onChanged: () -> Unit,
    onStatus: (String?) -> Unit,
) {
    val context = LocalContext.current
    val summary = remember(refresh, conversationId) {
        service.conversationSummaries().first { it.conversationId == conversationId }
    }
    val timeline = remember(refresh, conversationId) { service.timeline(conversationId) }

    val exportConversation = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use {
                    it.write(service.conversationDescriptorCanonical(conversationId).toByteArray(Charsets.UTF_8))
                }
            }.onSuccess { onStatus("Описание диалога экспортировано") }
             .onFailure { onStatus("Ошибка экспорта диалога: ${it.message}") }
        }
    }

    val importMessage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)!!
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .let { service.admitIncoming(it, System.currentTimeMillis(), conversationId) }
            }.onSuccess {
                onChanged()
                onStatus(if (it == InsertMessageResult.Duplicate) "Сообщение уже было получено" else "Сообщение принято")
            }.onFailure { onStatus("Сообщение отклонено: ${it.message}") }
        }
    }

    val exportMessage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val raw = service.latestOutgoingCanonical(conversationId)
            if (raw == null) {
                onStatus("Нет исходящего сообщения для экспорта")
            } else {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use {
                        it.write(raw.toByteArray(Charsets.UTF_8))
                    }
                }.onSuccess { onStatus("Исходящее сообщение экспортировано") }
                 .onFailure { onStatus("Ошибка экспорта сообщения: ${it.message}") }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(summary.title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = {
                    TextButton(onClick = {
                        exportConversation.launch("conversation-${conversationId.take(12)}.json")
                    }) { Text("Диалог") }
                    TextButton(onClick = { importMessage.launch(arrayOf("application/json")) }) { Text("Импорт") }
                    TextButton(onClick = {
                        exportMessage.launch("eidolang-message-${System.currentTimeMillis()}.json")
                    }) { Text("Экспорт") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCompose) { Text("+") }
        },
    ) { padding ->
        if (timeline.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("В этом диалоге пока нет эйдограмм.")
                Spacer(Modifier.height(12.dp))
                Button(onClick = onCompose) { Text("Создать эйдограмму") }
            }
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(timeline, key = { it.messageId }) { item ->
                    MessageCard(item)
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }
}

@Composable
private fun MessageCard(item: TimelineItem) {
    val alignment = if (item.outgoing) Arrangement.End else Arrangement.Start
    Row(Modifier.fillMaxWidth(), horizontalArrangement = alignment) {
        ElevatedCard(Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(8.dp)) {
                EidogramViewer(
                    document = item.document,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (item.outgoing) "Вы · #${item.senderSeq}" else "${item.senderUserId.take(10)}… · #${item.senderSeq}",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (item.parentMessageIds.size > 1) {
                    Text("${item.parentMessageIds.size} родительские ветви", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceManagementScreen(
    service: LocalMessengerService,
    refresh: Int,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onStatus: (String?) -> Unit,
) {
    val context=LocalContext.current
    val primary=remember{AndroidKeystoreIdentityStore(context).exists()}
    val ownBundles=remember(refresh){
        service.ownDeviceBundlesCanonical().map(IdentityParser::parsePublicBundleCanonical)
    }
    val ownRoster=remember(refresh){service.latestRoster(service.localIdentity.user.userId)}
    var pendingBundle by remember{mutableStateOf<String?>(null)}
    var pendingRoster by remember{mutableStateOf<String?>(null)}

    fun read(uri:android.net.Uri):String =
        context.contentResolver.openInputStream(uri)!!
            .bufferedReader(Charsets.UTF_8).use{it.readText()}

    val enrollmentImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            runCatching{
                require(primary){"Only the primary root-authority device may authorize enrollment"}
                val request=EnrollmentCanonical.parseCanonical(read(uri))
                val root=AndroidRootAuthority()
                val cert=DeviceEnrollmentAuthority.authorize(root,request)
                val bundle=PublicIdentityBundleV1(root.user,cert)
                val bundleRaw=IdentityCanonical.publicBundleJson(bundle)
                service.importOwnDevice(bundleRaw,System.currentTimeMillis())

                val previous=service.latestRoster(root.user.userId)
                val revoked=previous?.body?.revokedDeviceIds.orEmpty()
                require(cert.deviceId !in revoked){"Requested device id was already revoked"}
                val active=(previous?.body?.activeDeviceIds.orEmpty()+
                    service.localIdentity.certificate.deviceId+cert.deviceId)
                    .filterNot{it in revoked}.distinct().sorted()
                val roster=DeviceRosterAuthority.issue(root,previous,active,revoked)
                val rosterRaw=DeviceRosterCanonical.json(roster)
                if(previous==null) service.importDeviceRoster(rosterRaw,System.currentTimeMillis())
                else service.importDeviceRoster(rosterRaw,System.currentTimeMillis())

                pendingBundle=bundleRaw
                pendingRoster=rosterRaw
                onChanged()
                cert.deviceId
            }.onSuccess{
                onStatus("Устройство ${it.take(16)}… авторизовано. Экспортируйте bundle и roster.")
            }.onFailure{onStatus("Enrollment request отклонён: ${it.message}")}
        }
    }

    val bundleExport=rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ){uri->
        val raw=pendingBundle
        if(uri!=null && raw!=null){
            runCatching{
                context.contentResolver.openOutputStream(uri)!!.use{
                    it.write(raw.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess{onStatus("Authorized device bundle экспортирован")}
             .onFailure{onStatus("Ошибка экспорта bundle: ${it.message}")}
        }
    }

    val rosterExport=rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ){uri->
        val raw=pendingRoster ?: service.latestRoster(service.localIdentity.user.userId)
            ?.let(DeviceRosterCanonical::json)
        if(uri!=null && raw!=null){
            runCatching{
                context.contentResolver.openOutputStream(uri)!!.use{
                    it.write(raw.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess{onStatus("Root-signed roster экспортирован")}
             .onFailure{onStatus("Ошибка экспорта roster: ${it.message}")}
        }
    }

    val rosterImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            runCatching{
                val raw=read(uri)
                val parsed=DeviceRosterCanonical.parseCanonical(raw)
                if(service.latestRoster(parsed.body.userId)==null)
                    service.importDeviceRosterSnapshot(raw,System.currentTimeMillis())
                else
                    service.importDeviceRoster(raw,System.currentTimeMillis())
                parsed
            }.onSuccess{
                onChanged()
                onStatus("Roster ${it.body.userId.take(12)}… epoch ${it.body.epoch} принят")
            }.onFailure{onStatus("Roster отклонён: ${it.message}")}
        }
    }

    Scaffold(
        topBar={
            TopAppBar(
                title={Text("Устройства")},
                navigationIcon={TextButton(onClick=onBack){Text("←")}},
                actions={
                    TextButton(onClick={rosterImport.launch(arrayOf("application/json"))}){
                        Text("Импорт roster")
                    }
                    TextButton(
                        enabled=ownRoster!=null || pendingRoster!=null,
                        onClick={rosterExport.launch("eidolang-device-roster.json")}
                    ){Text("Экспорт roster")}
                }
            )
        }
    ){padding->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)
        ){
            item{
                Text(
                    if(primary)
                        "Эта установка содержит user root authority."
                    else
                        "Secondary device: user root private key на этой установке отсутствует.",
                    style=MaterialTheme.typography.bodyMedium
                )
            }
            if(primary){
                item{
                    Button(onClick={enrollmentImport.launch(arrayOf("application/json"))}){
                        Text("Авторизовать enrollment request")
                    }
                }
                if(pendingBundle!=null){
                    item{
                        Button(onClick={bundleExport.launch("eidolang-authorized-device-bundle.json")}){
                            Text("Экспортировать device bundle")
                        }
                    }
                    item{
                        Button(onClick={rosterExport.launch("eidolang-device-roster.json")}){
                            Text("Экспортировать новый roster")
                        }
                    }
                }
            }

            items(ownBundles,key={it.device.deviceId}){b->
                val active=ownRoster?.body?.activeDeviceIds?.contains(b.device.deviceId) ?: true
                val revoked=ownRoster?.body?.revokedDeviceIds?.contains(b.device.deviceId) ?: false
                ListItem(
                    headlineContent={
                        Text(if(b.device.deviceId==service.localIdentity.certificate.deviceId)"Это устройство" else b.device.deviceId.take(20)+"…")
                    },
                    supportingContent={
                        Text(
                            when{
                                revoked->"revoked"
                                active->"active"
                                else->"not active"
                            }+" · "+b.device.body.signingKeyId.take(20)+"…"
                        )
                    },
                    trailingContent={
                        if(primary && active &&
                            b.device.deviceId!=service.localIdentity.certificate.deviceId){
                            TextButton(onClick={
                                runCatching{
                                    val root=AndroidRootAuthority()
                                    val previous=service.latestRoster(root.user.userId)
                                        ?: error("No current roster")
                                    val target=b.device.deviceId
                                    val next=DeviceRosterAuthority.issue(
                                        root,previous,
                                        previous.body.activeDeviceIds.filterNot{it==target},
                                        (previous.body.revokedDeviceIds+target).distinct().sorted()
                                    )
                                    val raw=DeviceRosterCanonical.json(next)
                                    service.importDeviceRoster(raw,System.currentTimeMillis())
                                    pendingRoster=raw
                                    onChanged()
                                }.onSuccess{onStatus("Устройство отозвано; экспортируйте новый roster")}
                                 .onFailure{onStatus("Revocation отклонён: ${it.message}")}
                            }){Text("Отозвать")}
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(
    identity:DevicePrivateCrypto,
    repository:AndroidSqliteMessengerRepository,
    onBack:()->Unit,
) {
    val context=LocalContext.current
    var report by remember{mutableStateOf<AndroidAdmissionReport?>(null)}
    var running by remember{mutableStateOf(false)}

    Scaffold(
        topBar={
            TopAppBar(
                title={Text("Android admission")},
                navigationIcon={TextButton(onClick=onBack){Text("←")}},
            )
        }
    ){padding->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)
        ){
            item{
                Text(
                    "Проверка выполняется на реальном Android runtime и не заменяется " +
                        "pure-Kotlin conformance suite.",
                    style=MaterialTheme.typography.bodyMedium
                )
            }
            item{
                Button(
                    enabled=!running,
                    onClick={
                        running=true
                        report=AndroidAdmissionProbe(context).run(identity,repository)
                        running=false
                    }
                ){Text(if(running)"Проверка…" else "Запустить admission probe")}
            }
            report?.let{r->
                item{
                    Text(
                        if(r.passed)"ADMISSION PASS" else "ADMISSION FAIL",
                        style=MaterialTheme.typography.titleMedium,
                        color=if(r.passed)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Text("SDK ${r.sdkInt} · ${r.deviceId.take(20)}…")
                }
                items(r.checks,key={it.id}){c->
                    ListItem(
                        headlineContent={Text((if(c.passed)"PASS " else "FAIL ")+c.id)},
                        supportingContent={Text(c.detail)}
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
