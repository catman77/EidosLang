package org.eidolang.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.eidolang.core.crypto.*
import org.eidolang.core.multidevice.AndroidSecondaryDeviceIdentityStore

@Composable
fun EidoRootApp(
    allowPrimaryCreation: Boolean = true,
    prepareOwnerIdentity: (String) -> String = { it },
    onReady: @Composable (DevicePrivateCrypto) -> Unit,
) {
    val context=LocalContext.current
    val primaryStore=remember{AndroidKeystoreIdentityStore(context)}
    val secondaryStore=remember{AndroidSecondaryDeviceIdentityStore(context)}
    var identity by remember {
        mutableStateOf<DevicePrivateCrypto?>(
            primaryStore.loadIfPresent() ?: SecondaryEnrollment.readyIdentity(context)
        )
    }

    val primaryExists=remember{primaryStore.exists()}
    val secondaryExists=remember{secondaryStore.load()!=null}
    if(primaryExists && secondaryExists){
        Surface(Modifier.fillMaxSize()){
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.Center){
                Text("Конфликт локальной identity",style=MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    "На одном профиле приложения обнаружены и primary-, и secondary-identity. " +
                        "R22 не выбирает одну из них автоматически."
                )
            }
        }
        return
    }

    val ready=identity
    if(ready!=null){
        onReady(ready)
    }else{
        FirstRunOnboarding(
            primaryStore=primaryStore,
            secondaryStore=secondaryStore,
            allowPrimaryCreation=allowPrimaryCreation,
            prepareOwnerIdentity=prepareOwnerIdentity,
            onReady={identity=it},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirstRunOnboarding(
    primaryStore:AndroidKeystoreIdentityStore,
    secondaryStore:AndroidSecondaryDeviceIdentityStore,
    allowPrimaryCreation: Boolean,
    prepareOwnerIdentity: (String) -> String,
    onReady:(DevicePrivateCrypto)->Unit,
) {
    val context=LocalContext.current
    var ownerBundleRaw by remember{mutableStateOf(secondaryStore.enrollmentOwnerCanonical())}
    var secondaryMode by remember{mutableStateOf(!allowPrimaryCreation || ownerBundleRaw != null || secondaryStore.load() != null)}
    var requestRaw by remember{mutableStateOf(ownerBundleRaw?.let(secondaryStore::createEnrollmentRequest))}
    var installedSecondary by remember{mutableStateOf(secondaryStore.load())}
    var status by remember{mutableStateOf<String?>(null)}

    fun read(uri:android.net.Uri):String =
        context.contentResolver.openInputStream(uri)!!
            .bufferedReader(Charsets.UTF_8).use{it.readText()}

    val ownerImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            runCatching{
                val raw=prepareOwnerIdentity(read(uri))
                IdentityParser.parsePublicBundleCanonical(raw)
                ownerBundleRaw=raw
                requestRaw=secondaryStore.createEnrollmentRequest(raw)
            }.onSuccess{status="Enrollment request сформирован"}
             .onFailure{status="Owner identity отклонена: ${it.message}"}
        }
    }
    val requestExport=rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ){uri->
        val raw=requestRaw
        if(uri!=null && raw!=null){
            runCatching{
                context.contentResolver.openOutputStream(uri)!!.use{
                    it.write(raw.toByteArray(Charsets.UTF_8))
                }
            }.onSuccess{status="Enrollment request экспортирован"}
             .onFailure{status="Ошибка экспорта: ${it.message}"}
        }
    }
    val approvalImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            runCatching{
                require(ownerBundleRaw != null) { "Сначала выберите визитку основного профиля" }
                secondaryStore.installAuthorizedBundle(read(uri))
            }.onSuccess{
                installedSecondary=it
                status="Root-authorized device bundle установлен. Импортируйте актуальный roster."
            }.onFailure{status="Device bundle отклонён: ${it.message}"}
        }
    }
    val rosterImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        val identity=installedSecondary
        val ownerRaw=ownerBundleRaw
        if(uri!=null && identity!=null && ownerRaw!=null){
            runCatching{
                SecondaryEnrollment.complete(context, read(uri))
            }.onSuccess{
                status="Secondary device полностью авторизован"
                onReady(it)
            }.onFailure{status="Roster отклонён: ${it.message}"}
        }
    }

    Scaffold(
        topBar={TopAppBar(title={Text("Первый запуск")})}
    ){padding->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ){
            Text(
                if (!allowPrimaryCreation)
                    "Подключите эту установку к прежнему профилю. Сначала сохраните свою визитку " +
                        "из старого приложения и выберите её здесь. Старое приложение остаётся основным."
                else
                    "Выберите роль этой установки. Выбор определяет, создаётся ли новый user root " +
                        "или устройство присоединяется к уже существующему user identity."
            )
            if (!allowPrimaryCreation) {
                Text(
                    "Полная переписка остаётся в старом приложении. Его архив переносит эйдограммы, " +
                        "но не текстовые подписи к старым сообщениям.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            status?.let{Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant)}

            if(!secondaryMode){
                Button(onClick={
                    runCatching{primaryStore.ensure()}
                        .onSuccess(onReady)
                        .onFailure{status="Primary identity не создана: ${it.message}"}
                }){Text("Создать основной профиль")}

                OutlinedButton(onClick={secondaryMode=true}){
                    Text("Подключить как дополнительное устройство")
                }
            }else{
                Button(onClick={ownerImport.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))}){
                    Text(if(ownerBundleRaw==null)"1. Выбрать визитку своего профиля" else "1. Визитка профиля выбрана")
                }
                Button(
                    enabled=requestRaw!=null,
                    onClick={requestExport.launch("eidolang-device-enrollment-request.json")}
                ){Text("2. Сохранить запрос на подключение")}

                Text(
                    "В старом приложении откройте «Ещё» → «Устройства» и авторизуйте сохранённый запрос. " +
                        "Сохраните разрешение (device bundle) и список устройств (roster), затем выберите их здесь."
                )

                Button(onClick={approvalImport.launch(arrayOf("application/json"))}){
                    Text(if(installedSecondary==null)"3. Выбрать разрешение на подключение" else "3. Разрешение проверено")
                }
                Button(
                    enabled=installedSecondary!=null && ownerBundleRaw!=null,
                    onClick={rosterImport.launch(arrayOf("application/json"))}
                ){Text("4. Выбрать список устройств")}

                if (allowPrimaryCreation && installedSecondary == null) {
                    TextButton(onClick={secondaryMode=false}){Text("← Назад")}
                }
            }
        }
    }
}
