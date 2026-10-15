package org.eidolang.feature.onboarding

import android.content.Context
import org.eidolang.core.crypto.DevicePrivateCrypto
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.multidevice.AndroidSecondaryDeviceIdentityStore
import org.eidolang.core.multidevice.DeviceRosterCanonical
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.LocalMessengerService

/** A certificate alone is not a completed enrollment: its owner-signed roster is required too. */
object SecondaryEnrollment {
    fun readyIdentity(context: Context): DevicePrivateCrypto? {
        val identity = AndroidSecondaryDeviceIdentityStore(context).load() ?: return null
        return AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context)).use { repo ->
            val roster = LocalMessengerService(repo, identity).latestRoster(identity.user.userId)
            identity.takeIf { roster?.body?.activeDeviceIds?.contains(identity.certificate.deviceId) == true }
        }
    }

    fun complete(context: Context, rosterCanonical: String): DevicePrivateCrypto {
        val store = AndroidSecondaryDeviceIdentityStore(context)
        val identity = store.load() ?: error("Сначала импортируйте разрешение основного приложения")
        val owner = store.enrollmentOwnerCanonical() ?: error("Выберите визитку основного профиля ещё раз")
        val roster = DeviceRosterCanonical.parseCanonical(rosterCanonical)
        require(roster.body.userId == identity.user.userId) { "Список устройств принадлежит другому профилю" }
        require(identity.certificate.deviceId in roster.body.activeDeviceIds) { "Эта установка не авторизована" }
        AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context)).use { repo ->
            val service = LocalMessengerService(repo, identity)
            service.importOwnDevice(owner, System.currentTimeMillis())
            if (service.latestRoster(identity.user.userId)?.rosterId != roster.rosterId) {
                service.importDeviceRosterSnapshot(rosterCanonical, System.currentTimeMillis())
            }
        }
        return identity
    }
}
