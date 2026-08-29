package org.eidolang.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.eidolang.core.repository.TimelineItem
import org.eidolang.feature.viewer.EidogramViewer

/**
 * One row of the chat list: everything shown without re-opening the conversation.
 *
 * Assembled in the app rather than taken from `ConversationSummary`, which knows how many messages a
 * conversation holds but not what the last one says — captions live inside the encrypted payload and
 * only come out through `timeline()`.
 */
data class ChatRow(
    val conversationId: String,
    val peerUserId: String,
    val title: String,
    val lastCaption: String,
    val lastAtMs: Long,
    val lastOutgoing: Boolean,
    /** For an outgoing last message: has the peer collected it. See [DeliveryLedger]. */
    val lastDelivered: Boolean,
    val messageCount: Int,
)

/**
 * The conversations, newest first — the screen this app was missing.
 *
 * What stood here before was an inbox: every incoming eidogram from everyone, in one flat list, with
 * nothing sent alongside it. You could see that something had arrived but never what a conversation
 * with one person actually looked like, and your own half of it was not on screen at all.
 */
@Composable
fun ChatsScreen(
    rows: List<ChatRow>,
    avatarOf: (String) -> ByteArray?,
    onOpen: (ChatRow) -> Unit,
) {
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Переписки пока нет. Добавьте контакт и отправьте эйдограмму.")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.conversationId }) { row ->
            ListItem(
                leadingContent = { ChatAvatar(row.title, avatarOf(row.peerUserId)) },
                headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        (if (row.lastOutgoing) (if (row.lastDelivered) "✓✓ " else "✓ ") else "") +
                            row.lastCaption.ifBlank { "Без слов" },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = { Text(shortTime(row.lastAtMs), style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.clickable { onOpen(row) },
            )
        }
    }
}

/**
 * One conversation, oldest at the top, the way a person reads it.
 *
 * `timeline()` returns a topological order, not a total one — the message DAG deliberately has no
 * total order, because two devices can write without having seen each other. A reader still needs
 * one line to follow, so the arrival order is used as presented and never re-sorted by timestamp:
 * a clock from another device is not evidence about what came before what.
 */
@Composable
fun ChatScreen(
    title: String,
    items: List<TimelineItem>,
    deliveredThroughMs: Long,
    onExport: () -> Unit = {},
) {
    val state = rememberLazyListState()
    // A column, so the export row sits above the messages and the list takes the rest.
    // Open at the newest message, as every messenger does; the top of a long history is not where
    // anybody wants to start.
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) state.scrollToItem(items.lastIndex)
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Здесь пока пусто.")
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
        androidx.compose.material3.TextButton(onClick = onExport) { Text("Выгрузить в Word") }
    }
    LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), state = state) {
        items(items, key = { it.messageId }) { item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = if (item.outgoing) Arrangement.End else Arrangement.Start,
            ) {
                Column(
                    Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(12.dp))
                        .background(
                            if (item.outgoing) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .padding(8.dp),
                ) {
                    EidogramViewer(item.document, Modifier.size(140.dp))
                    if (item.caption.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(item.caption, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(shortTime(item.createdAtMs), style = MaterialTheme.typography.labelSmall)
                        if (item.outgoing) {
                            Spacer(Modifier.width(6.dp))
                            // The same two ticks as in the catalogue, and the same narrow meaning:
                            // the segment carrying this message was collected from this device.
                            Text(
                                if (deliveredThroughMs >= item.createdAtMs) "✓✓" else "✓",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun ChatAvatar(title: String, png: ByteArray?) {
    val bitmap = png?.let { decodeAvatar(it) }
    Box(
        Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Text(title.take(1).uppercase())
        }
    }
}

/** `14:05` for today, `3 фев` otherwise — enough to place a message without a full date. */
private fun shortTime(ms: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "d MMM"
    return java.text.SimpleDateFormat(pattern, java.util.Locale("ru")).format(java.util.Date(ms))
}
