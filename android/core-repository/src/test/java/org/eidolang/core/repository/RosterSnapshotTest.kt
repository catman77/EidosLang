package org.eidolang.core.repository

import org.eidolang.core.crypto.*
import org.eidolang.core.multidevice.*
import org.junit.Assert.*
import org.junit.Test

class RosterSnapshotTest {
    @Test fun offlineSnapshotKeepsEpochMonotoneWhileNormalImportsKeepTheChain() {
        val owner = JvmPrivateIdentity.generate()
        val peer = JvmPrivateIdentity.generate()
        val authority = object : RootAuthority {
            override val user = owner.user
            override fun signRoot(bytes: ByteArray) = JcaCrypto.sign(owner.rootSigning.private, bytes)
        }
        val service = LocalMessengerService(InMemoryMessengerRepository(), peer)
        val raw = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(owner.user, owner.certificate))
        service.importContact(raw, "Owner", 0)
        val first = DeviceRosterAuthority.issue(authority, null, listOf(owner.certificate.deviceId), emptyList())
        val second = DeviceRosterAuthority.issue(authority, first, first.body.activeDeviceIds, emptyList())
        val third = DeviceRosterAuthority.issue(authority, second, first.body.activeDeviceIds, emptyList())
        service.importDeviceRoster(DeviceRosterCanonical.json(first), 0)
        assertTrue(runCatching { service.importDeviceRoster(DeviceRosterCanonical.json(third), 1) }.isFailure)
        service.importDeviceRosterSnapshot(DeviceRosterCanonical.json(third), 1)
        assertEquals(third.rosterId, service.latestRoster(owner.user.userId)!!.rosterId)
        assertTrue(runCatching { service.importDeviceRosterSnapshot(DeviceRosterCanonical.json(second), 2) }.isFailure)
        assertTrue(runCatching { service.importDeviceRosterSnapshot(DeviceRosterCanonical.json(third.copy(rootSignatureB64 = "AA")), 2) }.isFailure)
        assertEquals(third.rosterId, service.latestRoster(owner.user.userId)!!.rosterId)
    }

    @Test fun skippedEpochsCannotReactivateARevokedDeviceOrForgetItsRevocation() {
        val owner = JvmPrivateIdentity.generate()
        val authority = object : RootAuthority {
            override val user = owner.user
            override fun signRoot(bytes: ByteArray) = JcaCrypto.sign(owner.rootSigning.private, bytes)
        }
        val second = IdentityFactory.certificate(IdentityFactory.deviceBody(owner.user,
            JcaCrypto.p256().public, JcaCrypto.rsa2048().public), owner.rootSigning.private)
        val service = LocalMessengerService(InMemoryMessengerRepository(), JvmPrivateIdentity.generate())
        listOf(owner.certificate, second).forEach {
            service.importContact(IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(owner.user, it)), "Owner", 0)
        }
        val active = listOf(owner.certificate.deviceId)
        val revoked = listOf(second.deviceId)
        val first = DeviceRosterAuthority.issue(authority, null, active + revoked, emptyList())
        val cut = DeviceRosterAuthority.issue(authority, first, active, revoked)
        val skipped = DeviceRosterAuthority.issue(authority, cut, active, revoked)
        service.importDeviceRosterSnapshot(DeviceRosterCanonical.json(cut), 1)
        val reactivated = DeviceRosterAuthority.issue(authority, skipped, active + revoked, emptyList())
        val forgotten = DeviceRosterAuthority.issue(authority, skipped, active, emptyList())
        for (invalid in listOf(reactivated, forgotten)) {
            assertTrue(runCatching { service.importDeviceRosterSnapshot(DeviceRosterCanonical.json(invalid), 2) }.isFailure)
            assertEquals(cut.rosterId, service.latestRoster(owner.user.userId)!!.rosterId)
            assertFalse(service.isDeviceActive(owner.user.userId, second.deviceId))
        }
        val accepted = DeviceRosterAuthority.issue(authority, skipped, active, revoked)
        service.importDeviceRosterSnapshot(DeviceRosterCanonical.json(accepted), 3)
        assertEquals(accepted.rosterId, service.latestRoster(owner.user.userId)!!.rosterId)
        assertFalse(service.isDeviceActive(owner.user.userId, second.deviceId))
    }
}
