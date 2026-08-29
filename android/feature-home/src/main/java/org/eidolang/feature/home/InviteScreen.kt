package org.eidolang.feature.home

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Render a short string as a QR code.
 *
 * Deliberately not a general-purpose renderer: it exists for [EidoInvite] links, which are ~155
 * characters of ASCII and fit comfortably. Error correction is set to Q rather than the usual L —
 * these codes get photographed off a screen at an angle, and the redundancy costs a few modules that
 * a link this short can spare.
 */
fun qrBitmap(text: String, sizePx: Int = 640): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, sizePx, sizePx,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            pixels[row + x] = if (matrix.get(x, y)) BLACK else WHITE
        }
    }
    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
}.getOrNull()

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()

/**
 * "Меня можно добавить" — the code to show across a table, and the link to send.
 *
 * The QR is the point. Anything else in this dialog is a fallback: the link for when the two people
 * are not in the same room, and the file for the pre-invite exports that still exist in the wild.
 *
 * Both need the onion address, which takes a minute or two to come up after a cold start. That is
 * why the waiting state is a state of its own and not an error — the first thing a new user would
 * otherwise see is their introduction failing for no visible reason.
 */
@Composable
fun InviteDialog(
    link: String?,
    nickname: String,
    onExportFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Как вас добавить") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (link == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Поднимаю ваш адрес в сети — это занимает минуту-две.")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Пока адреса нет, ни ссылка, ни код не сработают: по ним забирают вашу " +
                            "визитку прямо с этого телефона.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    return@Column
                }
                val bitmap = remember(link) { qrBitmap(link) }
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = "QR-код для добавления",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        // Nearest-neighbour: smoothing a QR blurs the module edges and is the usual
                        // reason a code on screen scans worse than the same code on paper.
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
                        contentScale = ContentScale.Fit,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Пусть отсканируют этот код обычной камерой — приложение откроется само.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))

                // The link itself, visible and selectable. A QR code is worth nothing to somebody
                // who is not in the room, and a share sheet is worth nothing when the person wants
                // to paste the link somewhere the sheet does not offer.
                Text("Ваша ссылка", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        link,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            )
                            .padding(8.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                val shareText = if (nickname.isBlank()) link else "$nickname: $link"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Button(onClick = {
                        val bmp = bitmap
                        if (bmp != null) {
                            Sharing.imageWithText(
                                context, bmp, shareText, "eidolang-invite.png", "Поделиться приглашением",
                            )
                        } else {
                            Sharing.text(context, shareText, "Поделиться приглашением")
                        }
                    }) { Text("Поделиться") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        Sharing.copyToClipboard(context, "Ссылка-приглашение", link)
                        copied = true
                    }) { Text(if (copied) "Скопировано" else "Копировать") }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = onExportFile) { Text("Сохранить визитку файлом") }
                }
            }
        },
    )
}

/**
 * Somebody's link has been opened. Fetch what it points at and ask before adding.
 *
 * A link is an instruction to go and talk to a stranger's device, so it is never acted on silently.
 * The nickname shown while fetching is the one written into the link and is not yet proof of
 * anything — [EidoInvite] says why — so it is labelled as coming from the link until the card lands.
 */
@Composable
fun InviteImportDialog(
    invite: EidoInvite.Parsed,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(if (error == null) "Добавить" else "Ещё раз")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
        title = { Text("Приглашение") },
        text = {
            Column {
                Text(
                    if (invite.nickname.isBlank()) "Кто-то приглашает вас в контакты."
                    else "В ссылке указано имя: ${invite.nickname}",
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    invite.onion.take(16) + "…",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Забираю визитку с их устройства…")
                    }
                    error != null -> Text(error, style = MaterialTheme.typography.bodySmall)
                    else -> Text(
                        "Визитка будет взята с устройства этого человека через Tor. Оно должно " +
                            "быть сейчас в сети.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}
