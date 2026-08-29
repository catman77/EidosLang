package org.eidolang.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.multidevice.AndroidSecondaryDeviceIdentityStore
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.LocalMessengerService

@Composable
fun EidoRootApp(
    onReady: @Composable (DevicePrivateCrypto) -> Unit,
) {
    val context=LocalContext.current
    val primaryStore=remember{AndroidKeystoreIdentityStore(context)}
    val secondaryStore=remember{AndroidSecondaryDeviceIdentityStore(context)}
    var identity by remember {
        mutableStateOf<DevicePrivateCrypto?>(
            primaryStore.loadIfPresent() ?: secondaryStore.load()
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
            onReady={identity=it},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirstRunOnboarding(
    primaryStore:AndroidKeystoreIdentityStore,
    secondaryStore:AndroidSecondaryDeviceIdentityStore,
    onReady:(DevicePrivateCrypto)->Unit,
) {
    val context=LocalContext.current
    var secondaryMode by remember{mutableStateOf(false)}
    var ownerBundleRaw by remember{mutableStateOf<String?>(null)}
    var requestRaw by remember{mutableStateOf<String?>(null)}
    var installedSecondary by remember{mutableStateOf<DevicePrivateCrypto?>(null)}
    var status by remember{mutableStateOf<String?>(null)}

    fun read(uri:android.net.Uri):String =
        context.contentResolver.openInputStream(uri)!!
            .bufferedReader(Charsets.UTF_8).use{it.readText()}

    val ownerImport=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            runCatching{
                val raw=read(uri)
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
                val repo=AndroidSqliteMessengerRepository(
                    context,AndroidRepositoryTextProtector(context)
                )
                val service=LocalMessengerService(repo,identity)
                service.importOwnDevice(ownerRaw,System.currentTimeMillis())
                service.importDeviceRosterSnapshot(read(uri),System.currentTimeMillis())
                identity
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
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ){
            Text(
                "Выберите роль этой установки. Выбор определяет, создаётся ли новый user root " +
                    "или устройство присоединяется к уже существующему user identity."
            )
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
                Button(onClick={ownerImport.launch(arrayOf("application/json"))}){
                    Text(if(ownerBundleRaw==null)"1. Импортировать identity владельца" else "1. Identity владельца импортирована")
                }
                Button(
                    enabled=requestRaw!=null,
                    onClick={requestExport.launch("eidolang-device-enrollment-request.json")}
                ){Text("2. Экспортировать enrollment request")}

                Text(
                    "На уже авторизованном основном устройстве импортируйте request, " +
                        "подпишите новое устройство и экспортируйте device bundle + roster."
                )

                Button(onClick={approvalImport.launch(arrayOf("application/json"))}){
                    Text(if(installedSecondary==null)"3. Импортировать authorized device bundle" else "3. Device bundle установлен")
                }
                Button(
                    enabled=installedSecondary!=null && ownerBundleRaw!=null,
                    onClick={rosterImport.launch(arrayOf("application/json"))}
                ){Text("4. Импортировать root-signed roster")}

                TextButton(onClick={secondaryMode=false}){Text("← Назад")}
            }
        }
    }
}
