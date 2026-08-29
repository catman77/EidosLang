package org.eidolang.core.vault

import android.content.Context

/**
 * The recovery secret, created at first run rather than when the owner first asks for a backup.
 *
 * It exists this early because the onion address is derived from it. An address that only becomes
 * portable once a history vault is created would change at that moment, and every contact card handed
 * out before it would stop working — silently, since nothing in the app can tell the difference
 * between "this person moved" and "this person is offline".
 *
 * The cost is stated rather than hidden: a recovery secret now exists for people who never asked for
 * one. It never leaves the device on its own — it is sealed under an AndroidKeyStore key like every
 * other local secret, and it only becomes portable when the owner deliberately exports the recovery
 * backup and code.
 *
 * The bootstrap slot is a sentinel vault id, not a real one. When a vault is finally created it must
 * [adopt] this secret instead of generating a fresh one, which is the whole point: the address the
 * owner has been handing out has to keep working.
 */
object AndroidBootstrapRecoverySecret {

    /**
     * Not a possible real vault id: those are SHA-256 outputs over live material. Sixty-four zeroes
     * satisfies the store's `[0-9a-f]{64}` check while being recognisable as a slot rather than a
     * vault.
     */
    private const val SLOT = "0000000000000000000000000000000000000000000000000000000000000000"

    /** The device's recovery secret, generating and sealing it on first call. */
    fun ensure(context: Context): HistoryVaultRecoverySecret {
        val store = AndroidHistoryVaultSecretStore(context)
        runCatching { store.load(SLOT) }.getOrNull()?.let { return it }
        val fresh = HistoryVaultRecoverySecret.generate()
        store.save(SLOT, fresh)
        return fresh
    }

    /**
     * The secret a newly created vault must use, and the copy of it filed under that vault's own id.
     *
     * Callers that create a vault should take the secret from here rather than
     * `HistoryVaultRecoverySecret.generate()`; the bootstrap slot stays, so the address survives even
     * if the vault is later discarded and rebuilt.
     */
    fun adopt(context: Context, vaultId: String): HistoryVaultRecoverySecret {
        val secret = ensure(context)
        AndroidHistoryVaultSecretStore(context).save(vaultId, secret)
        return secret
    }

    /**
     * The secret the onion address should be derived from: the active vault's if there is one, the
     * bootstrap slot otherwise.
     *
     * The active vault wins because an installation created before the bootstrap slot existed has a
     * vault secret the owner can already restore from, and that is the one their address must follow.
     */
    fun forOnion(context: Context): HistoryVaultRecoverySecret {
        AndroidHistoryVaultPackageStore(context).activeVaultId()?.let { id ->
            runCatching { AndroidHistoryVaultSecretStore(context).load(id) }.getOrNull()?.let { return it }
        }
        return ensure(context)
    }
}
