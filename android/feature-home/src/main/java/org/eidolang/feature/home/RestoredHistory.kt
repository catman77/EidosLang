package org.eidolang.feature.home

import android.content.Context
import org.eidolang.core.vault.AndroidHistoryVaultPackageStore
import org.eidolang.core.vault.AndroidHistoryVaultSecretStore
import org.eidolang.core.vault.HistoryVaultArchivePackageCodec
import org.eidolang.core.vault.HistoryVaultDescriptorCodec
import org.eidolang.core.vault.HistoryVaultDocumentProvider

object RestoredHistory {
    fun open(context: Context): HistoryVaultDocumentProvider? {
        val raw = AndroidHistoryVaultPackageStore(context).loadActiveCanonical() ?: return null
        val pkg = HistoryVaultArchivePackageCodec.parseCanonical(raw)
        val descriptor = HistoryVaultDescriptorCodec.parseCanonical(pkg.vaultDescriptorCanonicalJson)
        val secret = AndroidHistoryVaultSecretStore(context).load(descriptor.vaultId)
            ?: error("Не найден ключ восстановленной истории. Повторите восстановление архива.")
        return HistoryVaultDocumentProvider(raw, secret)
    }
}
