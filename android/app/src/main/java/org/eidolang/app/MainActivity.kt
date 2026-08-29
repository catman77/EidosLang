package org.eidolang.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.eidolang.feature.home.EidoHomeApp
import org.eidolang.feature.messenger.EidoMessengerApp
import org.eidolang.feature.onboarding.EidoRootApp

class MainActivity : ComponentActivity() {

    /**
     * An invite link the activity was opened with, or handed while already running.
     *
     * `singleTask` means a second link arrives through [onNewIntent] rather than a new activity, so
     * the state lives here and is cleared once the app has acted on it. Without clearing it, coming
     * back to the app later would re-offer an introduction that was already accepted or declined.
     */
    private var pendingInvite by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingInvite = inviteOf(intent)
    }

    private fun inviteOf(intent: Intent?): String? = intent
        ?.takeIf { it.action == Intent.ACTION_VIEW }
        ?.data?.toString()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingInvite = inviteOf(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.safeDrawingPadding()) {
                    EidoRootApp { identity ->
                        // The everyday app is the home shell; the R22 protocol screens stay one
                        // tap away for device management, archives and diagnostics.
                        var advanced by remember { mutableStateOf(false) }
                        if (advanced) {
                            // The protocol screens have no navigation of their own, so without this
                            // the system back button left the app instead of returning to it — the
                            // second half of the dead end that "Ещё" used to lead into.
                            BackHandler { advanced = false }
                            EidoMessengerApp(identity = identity)
                        } else {
                            EidoHomeApp(
                                identity = identity,
                                onOpenAdvanced = { advanced = true },
                                inviteLink = pendingInvite,
                                onInviteHandled = { pendingInvite = null },
                            )
                        }
                    }
                }
            }
        }
    }
}
