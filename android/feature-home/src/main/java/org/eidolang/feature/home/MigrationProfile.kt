package org.eidolang.feature.home

import android.content.Context
import org.eidolang.core.crypto.IdentityParser
import org.eidolang.core.multidevice.AndroidSecondaryDeviceIdentityStore

/** Read the public card exported by the original app; its private keys stay in that installation. */
object MigrationProfile {
    fun importOwnerCard(context: Context, raw: String): String {
        val card = ContactCard.read(raw)
        val owner = IdentityParser.parsePublicBundleCanonical(card.identityCanonical).user
        AndroidSecondaryDeviceIdentityStore(context).load()?.let {
            require(it.user == owner) { "Эта установка уже подключена к другому профилю" }
        }
        if (card.nickname.isNotBlank() || card.avatarPng != null) {
            EidoProfileStore(context).save(EidoProfileStore.Profile(card.nickname, card.avatarPng))
        }
        return card.identityCanonical
    }
}
