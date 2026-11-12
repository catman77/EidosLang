package org.eidolang.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.eidolang.core.message.GroupStateCodec
import org.eidolang.core.repository.ContactSummary
import org.eidolang.core.repository.GroupConversationRecord

@Composable
fun GroupEditorDialog(
    contacts: List<ContactSummary>, selfUserId: String,
    initial: GroupConversationRecord? = null,
    onDismiss: () -> Unit,
    onConfirm: (String, Set<String>) -> Unit,
) {
    var title by remember(initial?.groupId) { mutableStateOf(initial?.state?.title.orEmpty()) }
    var selected by remember(initial?.groupId) {
        mutableStateOf(initial?.state?.members?.map { it.userId }?.filter { it != selfUserId }?.toSet().orEmpty())
    }
    val choices = (contacts + initial?.state?.members.orEmpty().map { ContactSummary(it.userId, it.displayName, 1) })
        .distinctBy { it.userId }.filter { it.userId != selfUserId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Создать группу" else "Изменить группу") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(120) },
                    label = { Text("Название группы") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Участники: ${selected.size + 1}")
                if (choices.isEmpty()) Text("Сначала добавьте хотя бы один контакт.")
                else LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(choices, key = { it.userId }) { contact ->
                        val checked = contact.userId in selected
                        val toggle = {
                            selected = if (checked) selected - contact.userId
                            else if (selected.size < GroupStateCodec.MAX_MEMBERS - 1) selected + contact.userId else selected
                        }
                        Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = { toggle() })
                            Text(contact.alias, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Text("Новые участники будут получать сообщения с момента вступления.", style = MaterialTheme.typography.bodySmall)
                if (initial == null) Text("Участникам нужен EidoLang R22.6 или новее; создатель должен быть у них в контактах.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && selected.isNotEmpty(), onClick = { onConfirm(title.trim(), selected) }) {
                Text(if (initial == null) "Создать" else "Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun GroupInfoDialog(
    record: GroupConversationRecord, selfUserId: String,
    onDismiss: () -> Unit, onEdit: () -> Unit, onLeave: () -> Unit,
) {
    val state = record.state
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Участники: ${state.members.size}")
                groupReadOnlyReason(record, selfUserId)?.let { Text(it) }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(state.members, key = { it.userId }) { member ->
                        Text(member.displayName + (if (member.userId == selfUserId) " (вы)" else "") +
                            (if (member.userId == state.ownerUserId) " · создатель" else ""),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                }
                if (record.canWrite(selfUserId)) {
                    if (state.ownerUserId == selfUserId) TextButton(onClick = onEdit) { Text("Изменить название и участников") }
                    TextButton(onClick = onLeave) {
                        Text(if (state.ownerUserId == selfUserId) "Закрыть группу для всех" else "Выйти из группы")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

fun groupReadOnlyReason(record: GroupConversationRecord, selfUserId: String): String? = when {
    record.state.closed -> "Группа закрыта. Полученная история сохранена."
    record.leftAtRevision != null -> "Вы вышли из группы. Полученная история сохранена."
    record.state.members.none { it.userId == selfUserId } -> "Вы больше не участник этой группы. Полученная история сохранена."
    else -> null
}
