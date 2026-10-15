package org.eidolang.feature.recovery

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.*
import java.text.DateFormat
import java.util.Date

@Composable
fun AccountBackupScreen(identity: DevicePrivateCrypto, required: Boolean = false, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var configured by remember { mutableStateOf(AccountBackups.isConfigured(context)) }
    var changed by remember { mutableStateOf(false) }
    var fullRoot by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(identity.certificate.deviceId) {
        fullRoot = withContext(Dispatchers.IO) { runCatching {
            AndroidAccountRecovery.isFullPrimaryRecoverable(context, identity)
        }.getOrDefault(false) }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val chars = password.toCharArray()
                    try {
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            AccountBackups.configure(context, uri, chars)
                        }
                    } finally { chars.fill('\u0000') }
                }
                busy = false
                result.onSuccess {
                    password = ""; repeated = ""; configured = true; changed = true
                    status = "Копия записана и проверена. Теперь профиль можно восстановить после полного удаления приложения."
                }.onFailure { status = "Копия не сохранена: ${it.message}" }
            }
        }
    }
    if (!required) BackHandler(enabled = !busy, onBack = onDone)
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Резервная копия профиля", style = MaterialTheme.typography.headlineSmall)
        Text("Имя, фото, контакты, переписка и ваши эйдограммы сохраняются в зашифрованных файлах в выбранной папке. " +
            "Эта папка остаётся после удаления приложения.")
        if (fullRoot == false) Text("Профиль старой версии восстановится с тем же идентификатором и перепиской. " +
            "Управление другими устройствами останется доступно только на сохранившемся основном устройстве.",
            style = MaterialTheme.typography.bodySmall)
        if (configured) {
            BackupStatusText()
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { AccountBackups.backupNow(context) } }
                    busy = false
                    status = result.fold({ "Копия обновлена и проверена" }, { "Копия не обновлена: ${it.message}" })
                }
            }) { Text("Обновить копию сейчас") }
            HorizontalDivider()
            Text("Изменить папку или пароль", style = MaterialTheme.typography.titleMedium)
        }
        Text("Запомните пароль или сохраните его отдельно. Без пароля зашифрованная копия не откроется.")
        OutlinedTextField(password, { password = it }, enabled = !busy, label = { Text("Пароль копии — не менее 12 символов") },
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(repeated, { repeated = it }, enabled = !busy, label = { Text("Повторите пароль") },
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(enabled = !busy, onClick = { reveal = !reveal }) { Text(if (reveal) "Скрыть пароль" else "Показать пароль") }
            TextButton(enabled = !busy, onClick = { password = B64Url.encode(JcaCrypto.randomBytes(18)); repeated = password; reveal = true }) {
                Text("Создать пароль")
            }
        }
        Button(enabled = !busy && password.length >= 12 && password == repeated, onClick = { folder.launch(null) }) {
            Text(if (busy) "Сохраняю…" else "Выбрать папку и сохранить")
        }
        Text("Копия автоматически обновляется после изменений. Сохраняются три проверенные версии; " +
            "при ошибке записи предыдущие копии остаются в папке.", style = MaterialTheme.typography.bodySmall)
        status?.let { Text(it) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!busy && configured) Button(onClick = onDone) { Text(if (required || changed) "Продолжить" else "Готово") }
        else if (!required && !busy) TextButton(onClick = onDone) { Text("Назад") }
    }
}

@Composable
fun AccountRestoreScreen(onRestored: (DevicePrivateCrypto) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<android.net.Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { file = uri; status = "Копия выбрана" }
    }
    BackHandler(enabled = !busy, onBack = onBack)
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Восстановить профиль", style = MaterialTheme.typography.headlineSmall)
        Text("Выберите файл .eidobackup из папки с копиями и введите его пароль. " +
            "Перед восстановлением приложение проверит ключи, подписи и всю переписку.")
        OutlinedButton(enabled = !busy, onClick = { choose.launch(arrayOf("application/octet-stream", "*/*")) }) {
            Text(if (file == null) "Выбрать копию профиля" else "Выбрать другую копию")
        }
        OutlinedTextField(password, { password = it }, enabled = !busy, label = { Text("Пароль копии") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && file != null && password.isNotEmpty(), onClick = {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val chars = password.toCharArray()
                    try { runCatching { AndroidAccountRecovery.restore(context, AccountBackups.readDocument(context, file!!), chars) } }
                    finally { chars.fill('\u0000') }
                }
                busy = false
                result.onSuccess { password = ""; onRestored(it) }
                    .onFailure { status = "Профиль не восстановлен. Проверьте пароль, целостность копии и свободное место." }
            }
        }) { Text(if (busy) "Восстанавливаю…" else "Восстановить из копии") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.let { Text(it) }
        TextButton(enabled = !busy, onClick = onBack) { Text("Назад") }
    }
}

@Composable
fun BackupStatusText() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val unused = tick
    val error = AccountBackups.error(context)
    val saved = AccountBackups.savedAt(context)
    val pending = AccountBackups.isPending(context)
    Text(when {
        error != null -> error
        pending -> "Резервная копия обновляется…"
        saved > 0 -> "Копия сохранена: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(saved))
        else -> "Резервная копия ещё не сохранена"
    }, style = MaterialTheme.typography.bodySmall,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
