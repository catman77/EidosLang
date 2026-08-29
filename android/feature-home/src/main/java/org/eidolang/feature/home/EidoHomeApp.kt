package org.eidolang.feature.home

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.eidolang.core.crypto.DevicePrivateCrypto
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.ContactSummary
import org.eidolang.core.repository.LocalMessengerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eidolang.core.message.ConversationCanonical
import org.eidolang.feature.editor.EidoEditorScreen
import org.eidolang.feature.viewer.EidogramViewer
import java.security.SecureRandom

/**
 * The messenger as a person uses it: who you are, what you have made, and who you send it to.
 *
 * The R22 shell was organised around protocol artefacts — conversations, devices, diagnostics,
 * import and export of JSON. This one is organised around the three things the product is for.
 * The protocol screens still exist and are reachable from the overflow menu.
 */
@Composable
fun EidoHomeApp(
    identity: DevicePrivateCrypto,
    onOpenAdvanced: () -> Unit,
    /** An `eidolang://invite/...` link the user opened, if the app was started by one. */
    inviteLink: String? = null,
    onInviteHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val profileStore = remember { EidoProfileStore(context) }
    val catalog = remember { EidogramCatalogStore(context) }

    var profile by remember { mutableStateOf(profileStore.load()) }
    var editingProfile by remember { mutableStateOf(false) }
    if (profile == null) {
        ProfileSetupScreen(onDone = { chosen ->
            profileStore.save(chosen)
            profile = chosen
        })
        return
    }
    if (editingProfile) {
        ProfileSetupScreen(
            initial = profile,
            onCancel = { editingProfile = false },
            onDone = { chosen ->
                profileStore.save(chosen)
                // The card and the served avatar both derive from this, so both are rebuilt — see
                // the `LaunchedEffect(profile)` below. Codes already handed out keep working: their
                // card is still served, under its own hash.
                profile = chosen
                editingProfile = false
            },
        )
        return
    }

    val service = remember {
        LocalMessengerService(
            AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context)),
            identity, SecureRandom(),
        )
    }

    var tab by remember { mutableStateOf(0) }
    var composing by remember { mutableStateOf<ComposeTarget?>(null) }
    var sending by remember { mutableStateOf<SendProgress?>(null) }
    var entries by remember { mutableStateOf(catalog.list()) }
    var contacts by remember { mutableStateOf(runCatching { service.contactSummaries() }.getOrDefault(emptyList())) }
    var sendFor by remember { mutableStateOf<EidogramCatalogStore.Entry?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var chats by remember { mutableStateOf<List<ChatRow>>(emptyList()) }
    /** The conversation being read, or null for the list. */
    var openChat by remember { mutableStateOf<ChatRow?>(null) }
    var openChatItems by remember {
        mutableStateOf<List<org.eidolang.core.repository.TimelineItem>>(emptyList())
    }
    var showService by remember { mutableStateOf(false) }
    /** The contact a composed eidogram should go straight to, when writing from inside a chat. */
    var replyTo by remember { mutableStateOf<String?>(null) }
    /** Per contact, the newest publication instant they have collected. See [DeliveryLedger]. */
    var deliveredThrough by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    /** This device's own invite link. Null until Tor has published the onion service. */
    var myLink by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reloadChats() {
        val delivered = deliveredThrough
        chats = runCatching {
            service.conversationSummaries().mapNotNull { summary ->
                val items = runCatching { service.timeline(summary.conversationId) }.getOrNull()
                    ?: return@mapNotNull null
                val last = items.lastOrNull() ?: return@mapNotNull null
                // The other participant, which is what a person thinks of the conversation as. The
                // descriptor lists both, so "not me" identifies them without a lookup.
                val peer = summary.participantUserIds.firstOrNull { it != identity.user.userId }
                    ?: identity.user.userId
                ChatRow(
                    conversationId = summary.conversationId,
                    peerUserId = peer,
                    title = contacts.firstOrNull { it.userId == peer }?.alias
                        ?: summary.title.ifBlank { peer.take(12) + "…" },
                    lastCaption = last.caption,
                    lastAtMs = last.createdAtMs,
                    lastOutgoing = last.outgoing,
                    lastDelivered = (delivered[peer] ?: 0L) >= last.createdAtMs,
                    messageCount = items.size,
                )
            }.sortedByDescending { it.lastAtMs }
        }.getOrDefault(emptyList())
        // Keep an open conversation in step with what was just reloaded.
        openChat?.let { row ->
            openChatItems = runCatching { service.timeline(row.conversationId) }.getOrDefault(emptyList())
        }
    }

    fun refresh() {
        entries = catalog.list()
        contacts = runCatching { service.contactSummaries() }.getOrDefault(emptyList())
        deliveredThrough = runCatching { DeliveryLedger(context).deliveredThroughByContact() }
            .getOrDefault(emptyMap())
        reloadChats()
    }

    // `refresh`, not `reloadInbox`: the ticks are stale the moment the app was last closed, because
    // segments keep being collected while it is in the background.
    LaunchedEffect(Unit) { refresh() }
    // Bring this device's own address up as soon as the app opens. Tor takes a minute or two even
    // when nothing is wrong, and waiting until the first send would mean the first card exported
    // after a fresh install carried no address at all.
    LaunchedEffect(Unit) { EidoOnionNode.ensureStarted(context) }
    // Address and pinned certificate, before anything reaches for the relay.
    LaunchedEffect(Unit) { EidoRelaySettings.apply(context) }

    // Once there is an address, publish the card it should answer with and build the invite link.
    //
    // Polled rather than pushed: Tor comes up on its own thread minutes after launch, and the two
    // artifacts have to be produced together — the link contains the hash of these exact card bytes,
    // so rebuilding the card later would invalidate every link already handed out. Writing it once,
    // here, is what keeps a printed or forwarded link working.
    LaunchedEffect(profile) {
        myLink = null
        while (myLink == null) {
            val onion = EidoOnionNode.address
            if (onion != null) {
                myLink = withContext(Dispatchers.IO) {
                    runCatching {
                        val me = EidoProfileStore(context).load()
                        // Without the avatar: it is fetched from `/avatar` after the introduction,
                        // and inlining it would push the card past what the store will hold.
                        val card = ContactCard.write(
                            identity, EidoTransport.of(context, identity).publisher.certificate,
                            nickname = me?.nickname.orEmpty(),
                            avatarPng = null,
                            onion = onion,
                        )
                        // Filed under its own hash and kept alongside earlier versions, so a
                        // renamed profile does not silently break codes already given out.
                        OnionStore(context).putCard(card.toByteArray(Charsets.UTF_8))
                        me?.avatarPng?.let { OnionStore(context).putAvatar(it) }
                        EidoInvite.link(onion, card, me?.nickname.orEmpty())
                    }.getOrNull()
                }
            }
            if (myLink == null) kotlinx.coroutines.delay(2_000)
        }
    }

    // Make the directory agree with what the owner asked for.
    //
    // Runs once the address is up, so the published card carries one, and only when what the relay
    // holds differs from what it should. A publish that fails leaves the fingerprint unset and is
    // simply retried on the next launch — which is the whole point: the switch used to record the
    // wish locally, fire one request, and never look again.
    LaunchedEffect(myLink, profile) {
        // Deliberately *not* gated on the address being up. Gating it there was my own version of
        // the same mistake: on a network where Tor cannot get a circuit — and there is one, this
        // tablet — the owner would have stayed invisible forever while the switch said otherwise.
        // A card with no address is still worth publishing: it carries the identity and publisher
        // keys, so the DHT route still works, and the moment an address does appear the card
        // changes, its fingerprint changes with it, and this republishes on its own.
        withContext(Dispatchers.IO) {
            runCatching {
                val want = EidoDirectoryPreference.listed(context)
                val confirmed = EidoDirectoryPreference.confirmed(context)
                val me = EidoProfileStore(context).load()
                val card = ContactCard.write(
                    identity, EidoTransport.of(context, identity).publisher.certificate,
                    nickname = me?.nickname.orEmpty(),
                    avatarPng = me?.avatarPng,
                    onion = EidoOnionNode.address.orEmpty(),
                )
                val fingerprint = org.eidolang.core.crypto.HexSha256.ofUtf8(card)
                when {
                    want && confirmed == fingerprint -> Unit
                    want -> if (Relay.claimDirectory(card, visible = true) == 200) {
                        EidoDirectoryPreference.setConfirmed(context, fingerprint)
                    }
                    // Hiding has to be reconciled too, or the reverse lie appears: the switch is
                    // off and the entry is still there for everyone to find.
                    confirmed.isNotEmpty() -> if (Relay.claimDirectory(card, visible = false) == 200) {
                        EidoDirectoryPreference.setConfirmed(context, "")
                    }
                }
            }
        }
    }

    // An invite that was opened from outside the app.
    var invite by remember { mutableStateOf<EidoInvite.Parsed?>(null) }
    var inviteBusy by remember { mutableStateOf(false) }
    var inviteError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(inviteLink) {
        inviteLink ?: return@LaunchedEffect
        val parsed = EidoInvite.parse(inviteLink)
        if (parsed == null) {
            status = "Ссылка не похожа на приглашение"
            onInviteHandled()
        } else {
            inviteError = null
            invite = parsed
        }
    }
    invite?.let { pending ->
        InviteImportDialog(
            invite = pending, busy = inviteBusy, error = inviteError,
            onDismiss = { invite = null; inviteBusy = false; onInviteHandled() },
            onConfirm = {
                inviteBusy = true
                inviteError = null
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching {
                            val json = EidoInvite.fetchCard(pending)
                                ?: error(
                                    "Не удалось забрать визитку: " +
                                        (EidoOnionClient.lastError ?: "устройство не отвечает")
                                )
                            importCard(context, service, ContactCard.read(json)).alias
                        }
                    }
                    inviteBusy = false
                    outcome.onSuccess {
                        invite = null
                        status = "Добавлен: $it"
                        onInviteHandled()
                        refresh()
                    }.onFailure { inviteError = it.message }
                }
            },
        )
    }

    /**
     * Publish one eidogram to one contact.
     *
     * Lifted out of the picker dialog so a reply written inside a chat takes exactly the same path.
     * Two copies of this would have been two chances for the chat to send without recording who it
     * was sent to, which is what the ticks read.
     */
    fun sendEntry(contact: ContactSummary, entry: EidogramCatalogStore.Entry) {
        // Publishing takes tens of seconds. Closing the dialog and showing a one-line chip read as
        // "nothing happened", so the send is a stage of its own on screen until it finishes.
        sending = SendProgress(contact.alias, "Готовлю сообщение…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val conversationId = conversationWith(service, identity.user.userId, contact)
                    service.send(conversationId, entry.document, System.currentTimeMillis(), entry.caption)
                    catalog.markSent(entry, contact.userId, System.currentTimeMillis())
                    val bundle = service.contactBundleCanonical(contact.userId)
                    withContext(Dispatchers.Main) {
                        sending = SendProgress(contact.alias, "Публикую в сеть…")
                    }
                    val outcome = publishConversation(
                        context, service, EidoTransport.of(context, identity),
                        identity, conversationId, bundle,
                    )
                    "${contact.alias}: $outcome"
                }.getOrElse { "Не отправлено: ${it.message}" }
            }
            sending = null
            status = result
            refresh()
        }
    }

    composing?.let { target ->
        ComposeEidogramScreen(
            target = target,
            onSave = { document, caption ->
                // Editing keeps the entry's identity; copying deliberately does not, so the
                // original stays as it was sent.
                val base = target.base.takeIf { !target.copy && target.editable }
                val saved = EidogramCatalogStore.Entry(
                    id = base?.id ?: ("eid" + System.currentTimeMillis()),
                    createdAtMs = base?.createdAtMs ?: System.currentTimeMillis(),
                    caption = caption,
                    document = document,
                    sentTo = base?.sentTo ?: emptyList(),
                    sentAtMs = base?.sentAtMs ?: emptyMap(),
                )
                catalog.save(saved)
                composing = null
                refresh()
                // Written from inside a chat: the recipient is already known, so asking who to send
                // it to would be a dialog with exactly one answer. It is still saved to the
                // catalogue first — the catalogue is the author's own record, not an outbox.
                val to = replyTo?.let { peer -> contacts.firstOrNull { it.userId == peer } }
                replyTo = null
                if (to != null) {
                    sendEntry(to, saved)
                } else {
                    status = if (base != null) "Эйдограмма изменена" else "Эйдограмма сохранена"
                }
            },
            onCancel = { composing = null },
        )
        return
    }

    sending?.let { p ->
        AlertDialog(
            onDismissRequest = {},   // the work continues regardless; dismissing would only lie
            confirmButton = {},
            title = { Text("Отправка: ${p.alias}") },
            text = {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text(p.stage)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Публикация в сеть занимает до минуты.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
    }

    sendFor?.let { entry ->
        SendToContactDialog(
            entry = entry,
            contacts = contacts,
            onDismiss = { sendFor = null },
            onSend = { contact -> sendFor = null; sendEntry(contact, entry) },
        )
    }

    if (showService) {
        ServiceScreen(
            identity = identity,
            profile = profile!!,
            myLink = myLink,
            portable = EidoOnionNode.portable,
            onEditProfile = { showService = false; editingProfile = true },
            onOpenAdvanced = onOpenAdvanced,
            onBack = { showService = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBarWithProfile(
                profile!!,
                onRefresh = {
                    status = "Проверяю…"
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            runCatching {
                                var total = 0
                                val problems = mutableListOf<String>()
                                contacts.forEach { c ->
                                    // A contact with no publisher certificate can never deliver, so
                                    // there is nothing to look up and nothing to poll — and doing
                                    // both anyway spent a network round trip per phantom contact.
                                    if (ContactPublisherStore(context).get(c.userId) == null) {
                                        problems += "${c.alias}: нет ключа публикации — добавьте заново"
                                        return@forEach
                                    }
                                    // Before asking, make sure we still know where to ask. A
                                    // contact introduced before their address existed has none.
                                    runCatching { refreshContactTransport(context, c.userId, c.alias) }
                                    val r = receiveFrom(
                                        context, service, EidoTransport.of(context, identity),
                                        identity, c.userId, service.contactBundleCanonical(c.userId),
                                    )
                                    total += r.admitted
                                    r.problem?.let { problems += "${c.alias}: $it" }
                                }
                                // Tidy the locker: anything already collected has no reason to
                                // sit on a server.
                                runCatching {
                                    dropCollectedFromLocker(context, EidoTransport.of(context, identity))
                                }
                                when {
                                    total > 0 -> "Получено: $total"
                                    // Say which contact and why. "Ничего нового" over a contact that
                                    // can never deliver is the app reassuring you about a dead end.
                                    problems.isNotEmpty() -> problems.first()
                                    else -> "Ничего нового"
                                }
                            }.getOrElse { "Не получилось: ${it.message}" }
                        }
                        refresh()
                    }
                },
                onOpenService = { showService = true },
                onEditProfile = { editingProfile = true },
                back = openChat?.let { { openChat = null } },
                titleOverride = openChat?.title,
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = {}, label = { Text("Эйдограммы") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1; openChat = null },
                    icon = {}, label = { Text("Чаты") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = {}, label = { Text("Контакты") },
                )
            }
        },
        floatingActionButton = {
            when {
                tab == 0 -> ExtendedFloatingActionButton(
                    onClick = { composing = ComposeTarget(null, copy = false) },
                    text = { Text("Создать") },
                    icon = {},
                )
                // A conversation you can only read is half a conversation.
                tab == 1 && openChat != null -> ExtendedFloatingActionButton(
                    onClick = {
                        replyTo = openChat?.peerUserId
                        composing = ComposeTarget(null, copy = false)
                    },
                    text = { Text("Написать") },
                    icon = {},
                )
                else -> Unit
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            status?.let {
                AssistChip(onClick = { status = null }, label = { Text(it) }, modifier = Modifier.padding(8.dp))
            }
            when (tab) {
                0 -> CatalogScreen(
                    entries = entries,
                    deliveredThrough = deliveredThrough,
                    canSend = contacts.isNotEmpty(),
                    onSend = { sendFor = it },
                    onDelete = { catalog.delete(it.id); refresh() },
                    onEdit = { composing = ComposeTarget(it, copy = false) },
                    onCopy = { composing = ComposeTarget(it, copy = true) },
                )
                1 -> openChat.let { row ->
                    if (row == null) {
                        ChatsScreen(
                            rows = chats,
                            avatarOf = { ContactAvatarStore(context).get(it) },
                            onOpen = {
                                openChat = it
                                openChatItems = runCatching { service.timeline(it.conversationId) }
                                    .getOrDefault(emptyList())
                            },
                        )
                    } else {
                        ChatScreen(
                            title = row.title,
                            items = openChatItems,
                            deliveredThroughMs = deliveredThrough[row.peerUserId] ?: 0L,
                            onExport = {
                                scope.launch {
                                    status = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val file = ChatDocx.write(
                                                context, row.title, openChatItems,
                                                { item -> renderEidogram(item.document) },
                                                "чат-${row.title.replace(Regex("[^\\p{L}\\d]"), "-")}.docx",
                                            )
                                            withContext(Dispatchers.Main) {
                                                Sharing.file(context, file, ChatDocx.MIME, "Отправить переписку")
                                            }
                                            "Переписка выгружена: ${file.name}"
                                        }.getOrElse { "Не выгрузилось: ${it.message}" }
                                    }
                                }
                            },
                        )
                    }
                }
                else -> ContactsTab(
                    service = service, identity = identity,
                    contacts = contacts, myLink = myLink, onChanged = { refresh() },
                )
            }
        }
    }
}

/**
 * The conversation for this pair, derived rather than negotiated.
 *
 * Both sides compute the same id from their user ids, so the recipient can work out the DHT address
 * of a message without ever having been told the conversation exists.
 */
private fun conversationWith(
    service: LocalMessengerService,
    selfUserId: String,
    contact: ContactSummary,
): String {
    val descriptor = ConversationConvention.oneToOne(selfUserId, contact.userId)
    runCatching {
        service.importConversation(
            ConversationCanonical.descriptorJson(descriptor), contact.alias, System.currentTimeMillis(),
        )
    }
    return descriptor.conversationId
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBarWithProfile(
    profile: EidoProfileStore.Profile,
    onRefresh: () -> Unit,
    onOpenService: () -> Unit,
    onEditProfile: () -> Unit,
    /** Non-null when the screen below has somewhere to go back to. */
    back: (() -> Unit)?,
    titleOverride: String?,
) {
    TopAppBar(
        title = { Text(titleOverride ?: profile.nickname) },
        navigationIcon = {
            if (back != null) {
                TextButton(onClick = back) { Text("Назад") }
            } else {
                // Tapping your own face is where everyone looks first to change it. It was not
                // clickable, and the only route was Ещё → the name row — two levels down, with the
                // words "Изменить имя и фото" visible only after you had already got there.
                Box(Modifier.clickable(onClick = onEditProfile)) { Avatar(profile, size = 36) }
            }
        },
        actions = {
            TextButton(onClick = onRefresh) { Text("Обновить") }
            // Was "Ещё", and it opened the protocol screens with no way back — the only exit was
            // killing the app. It now opens a screen that says what it is for and has a Назад.
            TextButton(onClick = onOpenService) { Text("Ещё") }
        },
    )
}

@Composable
private fun Avatar(profile: EidoProfileStore.Profile, size: Int) {
    val bitmap = remember(profile) { profile.avatarPng?.let { decodeAvatar(it) } }
    Box(
        Modifier.padding(8.dp).size(size.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(profile.nickname.take(1).uppercase())
        }
    }
}

@Composable
private fun ProfileSetupScreen(
    /** Null on first run; an existing profile when the same screen is used to edit it. */
    initial: EidoProfileStore.Profile? = null,
    onCancel: (() -> Unit)? = null,
    onDone: (EidoProfileStore.Profile) -> Unit,
) {
    val context = LocalContext.current
    var nickname by remember { mutableStateOf(initial?.nickname.orEmpty()) }
    var avatar by remember { mutableStateOf(initial?.avatarPng) }
    var pickError by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Assigned only on success. It used to be `avatar = runCatching { ... }.getOrNull()`, which
        // did two wrong things at once on a photo that would not decode: it said nothing, and it
        // wiped the picture the person already had. "Nothing happened" and "your avatar is gone"
        // are the two ways this screen can fail, and that line produced both.
        runCatching {
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            } ?: error("не удалось прочитать изображение")
        }.mapCatching { compressAvatar(it) }
            .onSuccess { avatar = it; pickError = null }
            .onFailure { pickError = "Не удалось взять это фото: ${it.message}" }
    }

    Column(
        // Scrollable. Editing shows more than first-run setup does — a Cancel button and two
        // explanations — and with the keyboard up the Save button was simply off the bottom of the
        // screen on a phone. A fixed-height centred Column has no way to say that it overflowed.
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (initial == null) "Как вас зовут?" else "Ваш профиль",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(24.dp))

        Box(
            Modifier.size(96.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable { pick.launch("image/*") },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = remember(avatar) { avatar?.let { decodeAvatar(it) } }
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text("Фото")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (avatar == null) "Нажмите, чтобы выбрать фото" else "Нажмите, чтобы заменить фото",
            style = MaterialTheme.typography.labelSmall,
        )
        pickError?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = { if (it.length <= 40) nickname = it },
            label = { Text("Ник") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            // No availability check and no reservation: the suffix makes the name unique without a
            // registry, so this screen works with no network at all — as every other screen does.
            "К нику добавится метка из вашего ключа, например «${nickname.trim().ifBlank { "Ник" }}#7f3a». " +
                "Тёзки возможны, но перепутать вас нельзя.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { onDone(EidoProfileStore.Profile(nickname.trim(), avatar)) },
            enabled = nickname.trim().length >= 2 &&
                (initial == null || nickname.trim() != initial.nickname || avatar !== initial.avatarPng),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (initial == null) "Продолжить" else "Сохранить") }
        onCancel?.let {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
        }

        Spacer(Modifier.height(12.dp))
        // This used to say the nickname and photo never leave the device. That stopped being true
        // when introductions moved onto the contact card: the name is on it, and the photo is served
        // from this device to anyone holding your address. Saying otherwise in the one place a
        // person chooses them would be the worst possible place to be wrong.
        Text(
            "Имя видят те, кому вы дали код или ссылку — оно записано в вашей визитке. Фото они " +
                "забирают с этого телефона. Больше никуда ни то, ни другое не отправляется.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (initial != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Сменить фото можно свободно. Смена имени делает новую визитку, но коды и ссылки, " +
                    "которые вы уже раздали, продолжат работать со старым именем.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * What the editor was opened for: a new eidogram, an edit of an existing one, or a copy.
 *
 * A sent eidogram is not editable. Its `message_id` is already in a recipient's DAG and immutable
 * across recovery and migration by design, so rewriting the local copy would mean the sender and
 * the receiver hold different documents under one identity. Copying is the way to keep working
 * from something already sent — the copy is a new entry with its own id and an empty `sentTo`.
 */
internal data class ComposeTarget(
    val base: EidogramCatalogStore.Entry?,
    val copy: Boolean,
) {
    val editable: Boolean get() = base != null && base.sentTo.isEmpty()
}

@Composable
private fun ComposeEidogramScreen(
    target: ComposeTarget,
    onSave: (org.eidolang.core.model.EidogramDocumentV1, String) -> Unit,
    onCancel: () -> Unit,
) {
    var caption by remember(target) { mutableStateOf(target.base?.caption.orEmpty()) }
    EidoEditorScreen(
        modifier = Modifier.fillMaxSize(),
        // Per entry and per mode, so an unfinished edit of one eidogram cannot surface inside
        // another, and a copy never writes over the original's draft.
        draftKey = when {
            target.base == null -> "home-compose"
            target.copy -> "home-copy-${target.base.id}"
            else -> "home-edit-${target.base.id}"
        },
        initialDocument = target.base?.document,
        onSend = { document -> onSave(document, caption.trim()); true },
        onClose = onCancel,
        sendLabel = "Сохранить",
        title = when {
            target.base == null -> "Новая"
            target.copy -> "Копия"
            else -> "Правка"
        },
        showFileActions = false,
        // The caption is the point of the eidogram in this product: the drawing travels with the
        // words that say what it means, and an entry with no words is not worth keeping.
        sendEnabled = caption.isNotBlank(),
        sendDisabledReason = "Опишите словами, что это значит — иначе сохранить нельзя",
        header = {
            OutlinedTextField(
                value = caption,
                onValueChange = { if (it.length <= 2000) caption = it },
                label = { Text("Что это значит") },
                placeholder = { Text("Своими словами") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                minLines = 2,
                maxLines = 4,
            )
        },
    )
}

/**
 * Everything that is not everyday use: the profile, the invite, and the protocol screens.
 *
 * This replaces a button labelled "Ещё" that dropped straight into `EidoMessengerApp` with no way
 * out — the app had to be killed to get back. A screen reachable only one way is worse than no
 * screen: it makes the whole product feel broken at the exact moment somebody is exploring it.
 *
 * The protocol screens stay reachable, because device enrollment, archives and diagnostics live
 * there and there is nowhere else for them yet. They are labelled as what they are rather than as
 * "more".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceScreen(
    identity: DevicePrivateCrypto,
    profile: EidoProfileStore.Profile,
    myLink: String?,
    portable: Boolean,
    onEditProfile: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onBack: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showInvite by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // The file export is the route that needs no network at all, and the only one older versions
    // understand. It carries the avatar, unlike the card served over the address.
    val exportSelf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        status = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use {
                it.write(
                    ContactCard.write(
                        identity, EidoTransport.of(context, identity).publisher.certificate,
                        nickname = profile.nickname,
                        avatarPng = profile.avatarPng,
                        onion = EidoOnionNode.address.orEmpty(),
                    ).toByteArray(Charsets.UTF_8)
                )
            }
            "Визитка сохранена"
        }.getOrElse { "Не сохранено: ${it.message}" }
    }
    if (showInvite) {
        InviteDialog(
            link = myLink,
            nickname = profile.nickname,
            onExportFile = { showInvite = false; exportSelf.launch("моя-визитка.json") },
            onDismiss = { showInvite = false },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Служебное") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            ListItem(
                leadingContent = { Avatar(profile, size = 40) },
                headlineContent = { Text(profile.nickname) },
                supportingContent = { Text("Изменить имя и фото") },
                modifier = Modifier.clickable(onClick = onEditProfile),
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Как вас добавить") },
                supportingContent = {
                    Text(if (myLink == null) "Адрес ещё поднимается" else "QR-код, ссылка или файл")
                },
                modifier = Modifier.clickable { showInvite = true },
            )
            HorizontalDivider()
            var relayHost by remember { mutableStateOf(EidoRelaySettings.host(context)) }
            var relayNote by remember { mutableStateOf<String?>(null) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Адрес релея", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = relayHost,
                    onValueChange = { relayHost = it; relayNote = null },
                    singleLine = true,
                    label = { Text("IP или имя хоста") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    Button(onClick = {
                        if (!EidoRelaySettings.setHost(context, relayHost)) {
                            relayNote = "Не похоже на адрес"
                        } else {
                            relayNote = "Сохранено, проверяю…"
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { Relay.reachable() }
                                relayNote = if (ok) "Релей отвечает" else "Релей не отвечает по этому адресу"
                            }
                        }
                    }) { Text("Сохранить") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        relayHost = Relay.DEFAULT_HOST
                        EidoRelaySettings.setHost(context, Relay.DEFAULT_HOST)
                        relayNote = "Вернул адрес по умолчанию"
                    }) { Text("По умолчанию") }
                }
                relayNote?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Соединение привязано к сертификату, а не к адресу: другой релей заработает, " +
                        "только если предъявит тот же сертификат. Открытый трафик запрещён.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Устройства, архивы, диагностика") },
                supportingContent = { Text("Протокольные экраны R22") },
                modifier = Modifier.clickable(onClick = onOpenAdvanced),
            )
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                if (portable) {
                    "Ваш адрес в сети выведен из секрета восстановления — он переживёт переустановку."
                } else {
                    "Внимание: адрес не выводится из секрета восстановления и будет потерян при " +
                        "переустановке. Контакты перестанут вас находить."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * Has [contactUserId] collected a segment that already contained this eidogram?
 *
 * A segment carries the whole conversation as of the moment it was built, so one comparison of
 * instants answers it: anything published after this was sent, and since collected, brought it
 * along. That is also why re-sending is never needed to "push" an older message through.
 */
private fun deliveredTo(
    entry: EidogramCatalogStore.Entry,
    contactUserId: String,
    deliveredThrough: Map<String, Long>,
): Boolean {
    val sentAt = entry.sentAtMs[contactUserId] ?: entry.createdAtMs
    val through = deliveredThrough[contactUserId] ?: return false
    return through >= sentAt
}

@Composable
private fun CatalogScreen(
    entries: List<EidogramCatalogStore.Entry>,
    deliveredThrough: Map<String, Long>,
    canSend: Boolean,
    onSend: (EidogramCatalogStore.Entry) -> Unit,
    onDelete: (EidogramCatalogStore.Entry) -> Unit,
    onEdit: (EidogramCatalogStore.Entry) -> Unit,
    onCopy: (EidogramCatalogStore.Entry) -> Unit,
) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Пока ничего нет. Нажмите «Создать».")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.id }) { entry ->
            ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    EidogramViewer(entry.document, Modifier.size(72.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.caption.ifBlank { "Без слов" },
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (entry.sentTo.isNotEmpty()) {
                            // One tick: published here and still on offer. Two: every recipient has
                            // pulled a segment that contains it. Nothing is ever lost in between —
                            // the bytes stay on this device and are handed out on request — so the
                            // difference is about waiting, not about failure.
                            val collected = entry.sentTo.count { deliveredTo(entry, it, deliveredThrough) }
                            Text(
                                when {
                                    collected == entry.sentTo.size -> "✓✓ доставлено"
                                    collected > 0 -> "✓ забрали $collected из ${entry.sentTo.size}"
                                    else -> "✓ ждёт получателя"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                // Four actions do not fit beside a 72dp preview, so they get their own row.
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { onEdit(entry) },
                        enabled = entry.sentTo.isEmpty(),
                    ) { Text("Изменить") }
                    TextButton(onClick = { onCopy(entry) }) { Text("Копия") }
                    TextButton(onClick = { onSend(entry) }, enabled = canSend) { Text("Отправить") }
                    TextButton(onClick = { onDelete(entry) }) { Text("Удалить") }
                }
            }
        }
    }
}

@Composable
private fun SendToContactDialog(
    entry: EidogramCatalogStore.Entry,
    contacts: List<ContactSummary>,
    onDismiss: () -> Unit,
    onSend: (ContactSummary) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        title = { Text("Кому отправить") },
        text = {
            if (contacts.isEmpty()) {
                Text("Сначала добавьте контакт.")
            } else {
                LazyColumn {
                    items(contacts, key = { it.userId }) { c ->
                        ListItem(
                            leadingContent = { ContactAvatar(c, 40.dp) },
                            headlineContent = { Text(c.alias) },
                            supportingContent = { Text("${c.deviceCount} устройств") },
                            modifier = Modifier.clickable { onSend(c) },
                        )
                    }
                }
            }
        },
    )
}


@Composable
private fun ContactsTab(
    service: LocalMessengerService,
    identity: DevicePrivateCrypto,
    contacts: List<ContactSummary>,
    /** This device's invite link, or null while its onion address is still coming up. */
    myLink: String?,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }

    var finding by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val importContact = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        // Off the main thread, because importing reaches the person's address for their avatar.
        // It used to run here directly: the socket threw `NetworkOnMainThreadException` straight
        // into a `runCatching`, so the picture silently never arrived and the import still reported
        // success. Nothing in the suite noticed — the avatar fetch was proven over a real circuit,
        // just never from the thread the app actually calls it on.
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching {
                    val card = ContactCard.read(requireNotNull(raw) { "не удалось прочитать файл" })
                    val summary = importCard(context, service, card)
                    if (card.publisher == null) {
                        "Добавлен: ${summary.alias} (старая визитка, приём невозможен)"
                    } else "Добавлен: ${summary.alias}"
                }.getOrElse { "Не добавлен: ${it.message}" }
            }
            onChanged()
        }
    }

    val exportSelf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        status = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use {
                val me = EidoProfileStore(context).load()
                val card = ContactCard.write(
                    identity, EidoTransport.of(context, identity).publisher.certificate,
                    nickname = me?.nickname.orEmpty(),
                    avatarPng = me?.avatarPng,
                    onion = EidoOnionNode.address.orEmpty(),
                )
                it.write(card.toByteArray(Charsets.UTF_8))
            }
            "Ваша визитка сохранена"
        }.getOrElse { "Не сохранено: ${it.message}" }
    }

    if (finding) {
        FindPeopleDialog(
            onDismiss = { finding = false },
            onAdd = { hit ->
                finding = false
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        runCatching {
                            "Добавлен: " +
                                importCard(context, service, ContactCard.read(hit.cardJson)).alias
                        }.getOrElse { "Не добавлен: ${it.message}" }
                    }
                    onChanged()
                }
            },
        )
    }

    if (showInvite) {
        InviteDialog(
            link = myLink,
            nickname = EidoProfileStore(context).load()?.nickname.orEmpty(),
            onExportFile = { showInvite = false; exportSelf.launch("моя-визитка.json") },
            onDismiss = { showInvite = false },
        )
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // Being findable is opt-in and reversible: publishing sends the same card you would hand
        // over as a file, withdrawing sends it with an empty nickname and the relay drops the row.
        var listed by remember { mutableStateOf<Boolean>(EidoDirectoryPreference.listed(context)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = listed,
                onCheckedChange = { want ->
                    listed = want
                    EidoDirectoryPreference.setListed(context, want)
                    scope.launch {
                        status = withContext(Dispatchers.IO) {
                            val me = EidoProfileStore(context).load()
                            // The name stays claimed either way; hiding must not release it.
                            val card = ContactCard.write(
                                identity, EidoTransport.of(context, identity).publisher.certificate,
                                nickname = me?.nickname.orEmpty(),
                                avatarPng = me?.avatarPng,
                                onion = EidoOnionNode.address.orEmpty(),
                            )
                            when (Relay.claimDirectory(card, visible = want)) {
                                200 -> {
                                    EidoDirectoryPreference.setConfirmed(
                                        context,
                                        if (want) org.eidolang.core.crypto.HexSha256.ofUtf8(card) else "",
                                    )
                                    if (want) "Вас можно найти по нику" else "Вы скрыты из поиска"
                                }
                                409 -> "Ник уже занят другим человеком"
                                // Not "Каталог недоступен" full stop: the wish is kept and retried
                                // on the next launch, and saying so is the difference between a
                                // setting that failed and one that is on its way.
                                else -> if (want) "Каталог не ответил — опубликую, когда он появится"
                                else "Каталог не ответил — скрою, когда он появится"
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Text("Меня можно найти по нику", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(onClick = { finding = true }) { Text("Найти людей") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { importContact.launch(arrayOf("application/json", "*/*")) }) {
                Text("Из файла")
            }
            Spacer(Modifier.width(8.dp))
            // The primary way in: a code to show, or a link to send. The file export is still
            // here, one tap deeper — it is what older versions understand, and the only route that
            // works with no network at all.
            Button(onClick = { showInvite = true }) { Text("Меня добавить") }
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        if (contacts.isEmpty()) {
            Text("Контактов пока нет. Покажите свой код или пришлите ссылку — этого достаточно.")
        } else {
            LazyColumn {
                items(contacts, key = { it.userId }) { c ->
                    var confirming by remember(c.userId) { mutableStateOf(false) }
                    ListItem(
                        leadingContent = { ContactAvatar(c, 40.dp) },
                        headlineContent = { Text(c.alias) },
                        supportingContent = {
                            Text(
                                if (ContactPublisherStore(context).get(c.userId) == null)
                                    "нет ключа публикации — принимать от него нельзя"
                                else c.userId.take(16) + "…"
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { confirming = true }) { Text("Удалить") }
                        },
                    )
                    if (confirming) {
                        AlertDialog(
                            onDismissRequest = { confirming = false },
                            title = { Text("Удалить контакт?") },
                            text = {
                                Text(
                                    "${c.alias} исчезнет из адресной книги. Присланные им " +
                                        "эйдограммы останутся — они подписаны и принадлежат " +
                                        "переписке, а не строке в списке."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirming = false
                                    status = runCatching {
                                        service.deleteContact(c.userId)
                                        onChanged()
                                        "Удалён: ${c.alias}"
                                    }.getOrElse { "Не удалён: ${it.message}" }
                                }) { Text("Удалить") }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirming = false }) { Text("Отмена") }
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** What the send dialog is showing while the transport works. */
internal data class SendProgress(val alias: String, val stage: String)

/**
 * Take in a contact card, whatever brought it: a file, the directory, a QR code or a link.
 *
 * Blocking, and must be called off the main thread — it reaches the person's own address for the
 * avatar their card no longer carries. Three copies of this used to live in the UI and had already
 * drifted apart in indentation and in what they stored.
 */
private fun importCard(
    context: Context,
    service: LocalMessengerService,
    card: ContactCard.Parsed,
): ContactSummary {
    val summary = importWithHandle(service, card)
    card.publisher?.let { ContactPublisherStore(context).put(summary.userId, it) }
    ContactOnionStore(context).put(summary.userId, card.onion)
    card.avatarPng?.let { ContactAvatarStore(context).put(summary.userId, it) }
    // No avatar on the card — an introduction by QR or link carries only what fits through one.
    // Fetch it from the person's own address instead, and never let a missing picture look like a
    // failed introduction.
    if (card.avatarPng == null && card.onion.isNotEmpty()) {
        runCatching { OnionDelivery.fetchAvatar(context, summary.userId, card.onion) }
    }
    return summary
}

/** A contact's face if their card carried one, otherwise the first letter of their name. */
@Composable
internal fun ContactAvatar(contact: ContactSummary, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val png = remember(contact.userId) { ContactAvatarStore(context).get(contact.userId) }
    val bitmap = remember(png) { png?.let { decodeAvatar(it) } }
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(contact.alias.take(1).uppercase(), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Whether this device asked to be listed. Kept locally so the switch survives a restart. */
internal object EidoDirectoryPreference {
    private const val FILE = "directory-listed.flag"
    private const val CONFIRMED = "directory-confirmed.txt"

    /** What the owner asked for. Says nothing about what the directory actually holds. */
    fun listed(context: android.content.Context) = java.io.File(context.filesDir, FILE).exists()

    fun setListed(context: android.content.Context, listed: Boolean) {
        val f = java.io.File(context.filesDir, FILE)
        if (listed) f.writeText("1") else f.delete()
    }

    /**
     * Fingerprint of the card the relay is believed to be holding; empty when it holds nothing.
     *
     * The intent flag alone was a lie waiting to happen: it was written the instant the switch
     * moved, before the network call, and never revisited. Flip the switch while the relay is down
     * — which is exactly what happened on 2026-08-29 at 01:12, four days into an outage — and the
     * app shows you as findable forever while the directory has never heard of you. Nothing
     * retried, because nothing recorded that anything had failed.
     *
     * Keeping the fingerprint rather than a bare boolean also covers the quieter cases: the card
     * changes when the onion address finally comes up (a switch flipped in the first seconds after
     * install publishes an entry with no address at all) and when the nickname changes, and both
     * need republishing.
     */
    fun confirmed(context: android.content.Context): String =
        java.io.File(context.filesDir, CONFIRMED).takeIf { it.isFile }?.readText()?.trim().orEmpty()

    fun setConfirmed(context: android.content.Context, fingerprint: String) {
        val f = java.io.File(context.filesDir, CONFIRMED)
        if (fingerprint.isEmpty()) f.delete() else f.writeText(fingerprint)
    }
}

@Composable
private fun FindPeopleDialog(
    onDismiss: () -> Unit,
    onAdd: (Relay.DirectoryHit) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Relay.DirectoryHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    /** The directory could not be asked at all — a different fact from "nobody by that name". */
    var unreachable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Найти людей") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Ник") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        searching = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { Relay.searchDirectory(query.trim()) }
                            hits = result.orEmpty()
                            unreachable = result == null
                            searching = false
                            searched = true
                        }
                    },
                    enabled = query.trim().length >= 2 && !searching,
                ) { Text(if (searching) "Ищу…" else "Искать") }
                Spacer(Modifier.height(8.dp))
                if (searched && hits.isEmpty() && !searching) {
                    Text(
                        if (unreachable) {
                            // Saying "нашлось ничего" here was the app asserting something it had
                            // no way to know.
                            "Каталог недоступен: он единственное, что ещё работает через сервер, " +
                                "и сервер сейчас не отвечает. Обменяйтесь QR-кодом или ссылкой — " +
                                "этот способ никакого сервера не требует."
                        } else {
                            // The trap this dialog set: a nickname in your profile registers you
                            // nowhere. Publication is a separate, opt-in act on the other person's
                            // own device, and nothing said so.
                            "Никого не нашлось. В каталог человек попадает, только если сам включил " +
                                "«Меня можно найти по нику» у себя — одного ника в профиле мало."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(hits, key = { it.userId }) { hit ->
                        val bmp = remember(hit.userId) { hit.avatarPng?.let { decodeAvatar(it) } }
                        ListItem(
                            leadingContent = {
                                Box(
                                    Modifier.size(40.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (bmp != null) {
                                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    } else Text(hit.nickname.take(1).uppercase())
                                }
                            },
                            headlineContent = { Text(EidoHandle.of(hit.nickname, hit.userId)) },
                            trailingContent = { TextButton(onClick = { onAdd(hit) }) { Text("Добавить") } },
                        )
                    }
                }
                if (hits.isNotEmpty()) {
                    Text(
                        "Ник выбирает владелец. Различает людей метка после «#» — она выведена из ключа.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
    )
}

/**
 * Import a card and label the contact with its self-certifying handle.
 *
 * The alias has to be set in a second call: the handle needs the userId, and the userId only exists
 * once the bundle has been parsed and imported.
 */
private fun importWithHandle(
    service: LocalMessengerService,
    card: ContactCard.Parsed,
): ContactSummary {
    val summary = service.importContact(card.identityCanonical, null, System.currentTimeMillis())
    val handle = EidoHandle.of(card.nickname, summary.userId)
    return runCatching {
        service.importContact(card.identityCanonical, handle, System.currentTimeMillis())
    }.getOrDefault(summary)
}
