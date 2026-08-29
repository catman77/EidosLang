package org.eidolang.feature.home

import android.content.Context
import java.io.File

/**
 * Which relay this installation talks to.
 *
 * Stored in the clear: it is an address, not a secret, and the app's own traffic to it is visible
 * to the network anyway. What is *not* negotiable is the certificate — see `Relay.trust` — so a
 * changed address still only works against something holding the pinned key.
 */
object EidoRelaySettings {
    private const val FILE = "relay-host.txt"

    /** Refused rather than sanitised: this string is dialled and put into a URL. */
    private val ALLOWED = Regex("[A-Za-z0-9.:\\[\\]-]{3,80}")

    fun host(context: Context): String = File(context.filesDir, FILE)
        .takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.matches(ALLOWED) }
        ?: Relay.DEFAULT_HOST

    fun setHost(context: Context, host: String): Boolean {
        val v = host.trim()
        if (!v.matches(ALLOWED)) return false
        File(context.filesDir, FILE).writeText(v)
        Relay.host = v
        return true
    }

    /** Call once at start-up, before anything reaches for the relay. */
    fun apply(context: Context) {
        Relay.trust(context)
        Relay.host = host(context)
    }
}
