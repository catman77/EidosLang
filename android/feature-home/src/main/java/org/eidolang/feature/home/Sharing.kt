package org.eidolang.feature.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handing something to another application, through Android's own chooser.
 *
 * Everything shared is written into one directory inside the app's cache and served through a
 * `FileProvider`. That directory is the only thing the manifest exposes: a share URI can never
 * reach `filesDir`, where the sealed identity, the contact stores and the onion service key live.
 * The receiving app gets a read grant for one file and nothing more.
 */
object Sharing {

    private fun shared(context: Context): File =
        File(context.cacheDir, "shared").apply { mkdirs() }

    private fun uriFor(context: Context, file: File) = FileProvider.getUriForFile(
        context, "${context.packageName}.shared", file
    )

    /** Plain text — an invite link on its own, for pasting into a chat. */
    fun text(context: Context, text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * A picture with the link alongside it.
     *
     * Both, deliberately: a QR code is useless in a chat window where nobody can point a camera at
     * it, and a bare link is useless across a table. Apps that take only one take the one they
     * understand.
     */
    fun imageWithText(context: Context, bitmap: Bitmap, text: String, name: String, title: String) {
        val file = File(shared(context), name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** A document, by its own MIME type, so mail and messengers offer themselves. */
    fun file(context: Context, file: File, mime: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    }

    /** Where an export should be written so it can be shared afterwards. */
    fun exportFile(context: Context, name: String): File = File(shared(context), name)
}
