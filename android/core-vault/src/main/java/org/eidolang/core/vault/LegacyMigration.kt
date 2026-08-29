package org.eidolang.core.vault

import org.eidolang.core.archive.*
import org.eidolang.core.crypto.*
import org.eidolang.core.repository.*
import org.eidolang.core.recovery.*
import java.security.SecureRandom

data class LegacyVaultMigrationResult(
    val packageCanonicalJson:String,
    val recoverySecretExportCanonicalJson:String,
    val vaultId:String,
    val migratedMessageCount:Int,
)

object LegacyArchiveVaultMigrator {
    fun migrateR19SameDevice(
        r19PackageCanonical:String,
        localIdentity:DevicePrivateCrypto,
        recoverySecret:HistoryVaultRecoverySecret=HistoryVaultRecoverySecret.generate(),
        seed:ByteArray=JcaCrypto.randomBytes(16),
        storedAtMs:Long,
        currentOwnDeviceBundleCanonicalJson:Collection<String> = emptyList(),
        currentDeviceRosterCanonicalJson:Collection<String> = emptyList(),
        random:SecureRandom=SecureRandom(),
    ):LegacyVaultMigrationResult{
        val repo=InMemoryMessengerRepository()
        val archive=InMemoryArchiveStore()
        ArchiveCoordinator(repo,archive,localIdentity,random)
            .importAndRestorePackage(r19PackageCanonical,storedAtMs)
        applyContinuity(repo,localIdentity,currentOwnDeviceBundleCanonicalJson,currentDeviceRosterCanonicalJson,storedAtMs,random)
        return build(repo,localIdentity,recoverySecret,seed,random)
    }

    fun migrateR19WithR20Capsule(
        r19PackageCanonical:String,
        r20CapsuleCanonical:String,
        localIdentity:DevicePrivateCrypto,
        recoverySecret:HistoryVaultRecoverySecret=HistoryVaultRecoverySecret.generate(),
        seed:ByteArray=JcaCrypto.randomBytes(16),
        storedAtMs:Long,
        currentOwnDeviceBundleCanonicalJson:Collection<String> = emptyList(),
        currentDeviceRosterCanonicalJson:Collection<String> = emptyList(),
        random:SecureRandom=SecureRandom(),
    ):LegacyVaultMigrationResult{
        val repo=InMemoryMessengerRepository()
        val archive=InMemoryArchiveStore()
        MultiDeviceRecoveryCoordinator(repo,archive,localIdentity)
            .restore(r19PackageCanonical,r20CapsuleCanonical,storedAtMs)
        applyContinuity(repo,localIdentity,currentOwnDeviceBundleCanonicalJson,currentDeviceRosterCanonicalJson,storedAtMs,random)
        return build(repo,localIdentity,recoverySecret,seed,random)
    }

    private fun applyContinuity(
        repo:MessengerRepository,
        localIdentity:DevicePrivateCrypto,
        ownBundles:Collection<String>,
        rosters:Collection<String>,
        storedAtMs:Long,
        random:SecureRandom,
    ){
        val service=LocalMessengerService(repo,localIdentity,random)
        ownBundles.forEach{raw->
            val b=IdentityParser.parsePublicBundleCanonical(raw)
            if(b.user.userId==localIdentity.user.userId){
                service.importOwnDevice(raw,storedAtMs)
            }
        }
        rosters.map(org.eidolang.core.multidevice.DeviceRosterCanonical::parseCanonical)
            .sortedBy{it.body.userId}
            .forEach{service.importDeviceRosterSnapshot(org.eidolang.core.multidevice.DeviceRosterCanonical.json(it),storedAtMs)}
    }

    private fun build(
        repo:MessengerRepository,
        localIdentity:DevicePrivateCrypto,
        recoverySecret:HistoryVaultRecoverySecret,
        seed:ByteArray,
        random:SecureRandom,
    ):LegacyVaultMigrationResult{
        val messenger=LocalMessengerService(repo,localIdentity,random)
        val controller=HistoryVaultController.create(localIdentity,recoverySecret,seed,random)

        val ownerRoster=repo.deviceRosters().firstOrNull{it.userId==localIdentity.user.userId}
        if(ownerRoster!=null){
            controller.createEpochFromRoster(ownerRoster.rosterCanonicalJson)
        }else{
            controller.createEpoch(
                (repo.ownDevices().map{it.deviceId}+localIdentity.certificate.deviceId).distinct().sorted()
            )
        }
        val count=controller.archiveAll(repo,messenger)
        val packageRaw=controller.exportPackage(repo)
        return LegacyVaultMigrationResult(
            packageRaw,controller.recoverySecretExportCanonical(),controller.descriptor.vaultId,count
        )
    }
}
