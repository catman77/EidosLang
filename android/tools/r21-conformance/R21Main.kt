package org.eidolang.tools

import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.crypto.*
import org.eidolang.core.hardening.*
import org.eidolang.core.message.*
import org.eidolang.core.model.*
import org.eidolang.core.multidevice.*
import org.eidolang.core.repository.*
import org.eidolang.core.recovery.*
import org.eidolang.core.session.*
import org.eidolang.core.vault.*
import java.security.KeyPair
import java.security.SecureRandom

private fun doc(glyph:String,x:Int=500_000)=EidogramDocumentV1(
    listOf(EidogramAction.Add(0,"g000001",glyph,FixedTransform(x,500_000),0))
)

private fun mutateAscii(s:String,index:Int):String{
    val a=s.toCharArray()
    val c=a[index]
    a[index]=when(c){
        'A'->'B'
        'B'->'C'
        '0'->'1'
        '1'->'2'
        else->'A'
    }
    return String(a)
}

fun main(){
    val random=SecureRandom()
    val alice2Bundle=IdentityParser.parsePublicBundleCanonical(GoldenR20_2.ALICE2_BUNDLE)
    val alice2=JvmSecondaryDevice(
        KeyPair(
            PublicKeyCodec.ec(alice2Bundle.device.body.signingPublicKeyB64),
            PublicKeyCodec.privateEc(GoldenR20_2.ALICE2_SIGN_PRIVATE),
        ),
        KeyPair(
            PublicKeyCodec.rsa(alice2Bundle.device.body.encryptionPublicKeyB64),
            PublicKeyCodec.privateRsa(GoldenR20_2.ALICE2_ENC_PRIVATE),
        ),
        alice2Bundle.user,
        alice2Bundle.device,
        random,
    )
    val (_,secret)=RecoverySecretExportCodec.parseCanonical(GoldenR20_2.RECOVERY_SECRET_EXPORT)
    val oldPkg=HistoryVaultArchivePackageCodec.parseCanonical(GoldenR20_2.VAULT_PACKAGE)
    val descriptor=HistoryVaultDescriptorCodec.parseCanonical(oldPkg.vaultDescriptorCanonicalJson)

    // R21 recovery backup: encrypted file and separate high-entropy recovery code.
    val backupPair=RecoveryBackupCrypto.create(descriptor.vaultId,secret,random)
    val backupRaw=RecoveryBackupCodec.json(backupPair.backup)
    val rawSecretB64=RecoverySecretExportCodec.parseCanonical(GoldenR20_2.RECOVERY_SECRET_EXPORT)
        .first.secretB64
    check(!backupRaw.contains(rawSecretB64))
    val codeRaw=RecoveryBackupCodeCodec.encode(descriptor.vaultId,backupPair.recoveryCode)
    val (decodedVault,decodedCode)=RecoveryBackupCodeCodec.decode(codeRaw)
    check(decodedVault==descriptor.vaultId)
    val reopened=RecoveryBackupCrypto.open(RecoveryBackupCodec.parseCanonical(backupRaw),decodedCode)
    check(reopened.recoveryKeyId==secret.recoveryKeyId)
    println("PASS encrypted recovery backup contains no raw recovery secret and opens only with separate code")

    val wrongCode=RecoveryBackupCode.generate(random)
    check(runCatching{RecoveryBackupCrypto.open(backupPair.backup,wrongCode)}.isFailure)
    val tamperedBody=backupPair.backup.body.copy(
        ciphertextB64=backupPair.backup.body.ciphertextB64.dropLast(1)+
            if(backupPair.backup.body.ciphertextB64.last()=='A')"B" else "A"
    )
    val tamperedBackup=RecoveryBackupV1(
        tamperedBody,HexSha256.ofUtf8(RecoveryBackupCodec.bodyJson(tamperedBody))
    )
    check(runCatching{RecoveryBackupCrypto.open(tamperedBackup,backupPair.recoveryCode)}.isFailure)
    println("PASS wrong recovery code and authenticated-backup ciphertext tamper are fail-closed")

    // Fresh offline restore cannot cryptographically prove latestness: demonstrate the no-go.
    val freshOldRepo=InMemoryMessengerRepository()
    val freshOld=HistoryVaultRecoveryCoordinator(freshOldRepo,alice2,random)
        .restore(GoldenR20_2.VAULT_PACKAGE,secret,10_000)
    check(freshOldRepo.messages(freshOldRepo.conversations().single().conversationId).size==1)
    val bootstrap=VaultRollbackGuard.inspect(GoldenR20_2.VAULT_PACKAGE,null)
    check(bootstrap.status==VaultFreshnessStatus.BOOTSTRAP_FRESHNESS_UNPROVEN)
    println("PASS fresh offline restore exposes signed-archive freshness as unprovable rather than pretending old==latest")

    // Extend the old package by one genuine message.
    val repo=InMemoryMessengerRepository()
    val restored=HistoryVaultRecoveryCoordinator(repo,alice2,random)
        .restore(GoldenR20_2.VAULT_PACKAGE,secret,10_100)
    val service=LocalMessengerService(repo,alice2,random,restored.documentProvider)
    val conv=repo.conversations().single()
    val controller=HistoryVaultController.fromPackage(GoldenR20_2.VAULT_PACKAGE,secret,alice2,random)
    check(controller.archiveAll(repo,service)==0)
    val m2=service.send(conv.conversationId,doc("circle.blue.m",610_000),10_200)
    check(controller.archiveAll(repo,service)==1)
    val newerRaw=controller.exportPackage(repo)
    val newer=HistoryVaultArchivePackageCodec.parseCanonical(newerRaw)
    check(newer.entryCanonicalJson.size==2)

    val oldAnchor=bootstrap.nextAnchor
    val extension=VaultRollbackGuard.inspect(newerRaw,oldAnchor)
    check(extension.status==VaultFreshnessStatus.MONOTONIC_EXTENSION)
    check(runCatching{VaultRollbackGuard.inspect(GoldenR20_2.VAULT_PACKAGE,extension.nextAnchor)}.isFailure)
    println("PASS local rollback anchor accepts monotonic extension and rejects older valid signed package")

    // Two independently produced epoch-1 descendants form a real fork. A local anchor detects it.
    val forkA=HistoryVaultController.fromPackage(GoldenR20_2.VAULT_PACKAGE,secret,alice2,random)
    forkA.createEpoch(listOf(alice2.certificate.deviceId))
    val forkARaw=forkA.exportPackage(freshOldRepo)
    val forkB=HistoryVaultController.fromPackage(GoldenR20_2.VAULT_PACKAGE,secret,alice2,random)
    forkB.createEpoch(listOf(alice2.certificate.deviceId))
    val forkBRaw=forkB.exportPackage(freshOldRepo)
    val forkAAnchor=VaultRollbackGuard.inspect(forkARaw,null).nextAnchor
    check(runCatching{VaultRollbackGuard.inspect(forkBRaw,forkAAnchor)}.isFailure)
    println("PASS vault epoch fork is rejected once one branch is locally anchored")

    // Cross-vault and header substitutions are rejected below package-signature level as well.
    val firstEpoch=HistoryVaultEpochCodec.parseCanonical(oldPkg.epochCanonicalJson.single())
    val firstEntry=HistoryVaultEntryCodec.parseCanonical(oldPkg.entryCanonicalJson.single())
    val epochKey=HistoryVaultCrypto.openEpoch(descriptor,secret,firstEpoch)
    val wrongVaultBody=firstEntry.body.copy(vaultId="f".repeat(64))
    val wrongVaultEntry=HistoryVaultEntryV1(
        wrongVaultBody,HexSha256.ofUtf8(HistoryVaultEntryCodec.bodyJson(wrongVaultBody))
    )
    check(runCatching{HistoryVaultCrypto.openEntry(descriptor,epochKey,wrongVaultEntry)}.isFailure)

    val wrongHeaderBody=firstEntry.body.copy(messageId="e".repeat(64))
    val wrongHeaderEntry=HistoryVaultEntryV1(
        wrongHeaderBody,HexSha256.ofUtf8(HistoryVaultEntryCodec.bodyJson(wrongHeaderBody))
    )
    check(runCatching{HistoryVaultCrypto.openEntry(descriptor,epochKey,wrongHeaderEntry)}.isFailure)
    epochKey.fill(0)
    println("PASS cross-vault and entry-header substitution fail before historical plaintext release")

    // A malicious but correctly signed exporter package that omits a DAG parent must still fail.
    val newEntries=newer.entryCanonicalJson.map(HistoryVaultEntryCodec::parseCanonical)
    val childEntry=newEntries.single{it.body.messageId==m2.messageId}
    val malicious=HistoryVaultArchivePackageCodec.build(
        newer.ownerUserId,newer.vaultDescriptorCanonicalJson,newer.exporterIdentityBundleCanonicalJson,
        newer.ownDeviceBundleCanonicalJson,newer.deviceRosterCanonicalJson,newer.contacts,
        newer.conversations,newer.epochCanonicalJson,listOf(HistoryVaultEntryCodec.json(childEntry)),alice2
    )
    val maliciousRaw=HistoryVaultArchivePackageCodec.json(malicious)
    val emptyMissingParent=InMemoryMessengerRepository()
    check(runCatching{
        HistoryVaultRecoveryCoordinator(emptyMissingParent,alice2,random)
            .restore(maliciousRaw,secret,10_300)
    }.isFailure)
    check(emptyMissingParent.conversations().isEmpty())
    println("PASS signed-but-truncated history with missing DAG parent causes zero mutation")

    // R20.1 skipped-key DoS boundary: attacker-supplied large message number cannot allocate
    // unbounded keys and failed admission restores the exact prior ratchet state.
    val bobState=SessionStateCanonical.parseCanonical(GoldenR20_1.BOB_STATE_BEFORE_OOO)
    val bobSession=DoubleRatchetSession.restore(bobState,random,maxSkip=8)
    val beforeDoS=SessionStateCanonical.json(bobSession.exportSecretSnapshot())
    val basePacket=SessionCanonical.parseRatchetCanonical(GoldenR20_1.OOO_PACKETS[0])
    val hugeHeader=basePacket.header.copy(messageNumber=9)
    val huge0=basePacket.copy(header=hugeHeader,packetId="")
    val huge=huge0.copy(packetId=HexSha256.ofUtf8(SessionCanonical.ratchetBodyJson(huge0)))
    check(runCatching{bobSession.decrypt(huge)}.isFailure)
    check(SessionStateCanonical.json(bobSession.exportSecretSnapshot())==beforeDoS)
    println("PASS skipped-message DoS above configured bound is rejected with exact ratchet rollback")

    // R21 adds the missing analogous bound to outstanding one-time prekeys.
    val tempIdentity=JvmPrivateIdentity.generate(random)
    val boundedPrekeys=JvmPreKeyStore(tempIdentity,random,maxPendingOneTimePreKeys=4)
    repeat(4){boundedPrekeys.createBundle()}
    check(boundedPrekeys.pendingOneTimePreKeyCount()==4)
    check(runCatching{boundedPrekeys.createBundle()}.isFailure)
    println("PASS pending one-time prekey resource growth is explicitly bounded")

    // Build a legacy R19 representation from the verified recovered repository, then create a
    // cutover receipt only after exact history-digest equivalence with a fresh vault recovery.
    val legacyStore=InMemoryArchiveStore()
    val legacyCoordinator=ArchiveCoordinator(repo,legacyStore,alice2,random)
    legacyCoordinator.archiveAll(10_400)
    val legacyRaw=legacyCoordinator.exportPackageCanonical()
    val sourceDigest=HistoryEquivalence.compute(repo,service)

    val verifyRepo=InMemoryMessengerRepository()
    val verifyState=HistoryVaultRecoveryCoordinator(verifyRepo,alice2,random)
        .restore(newerRaw,secret,10_500)
    val verifyService=LocalMessengerService(verifyRepo,alice2,random,verifyState.documentProvider)
    val verifyDigest=HistoryEquivalence.compute(verifyRepo,verifyService)
    HistoryEquivalence.requireEqual(sourceDigest,verifyDigest)

    val receipt=LegacyCutover.issue(
        listOf("R19_PACKAGE" to legacyRaw.toByteArray(Charsets.UTF_8)),
        newerRaw,sourceDigest,verifyDigest,alice2
    )
    check(LegacyCutover.verify(receipt,alice2Bundle))
    check(runCatching{
        LegacyCutover.rejectCommittedLegacy(legacyRaw.toByteArray(Charsets.UTF_8),receipt)
    }.isFailure)
    println("PASS migrate→verify cutover receipt binds equivalent history and blocks exact retired legacy artifact")

    // Deterministic mutation corpus: every single-character mutation of selected authenticated
    // surfaces must either fail canonical parsing or fail cryptographic verification.
    var rejected=0
    val vaultText=newerRaw
    val vaultSteps=128
    repeat(vaultSteps){i->
        val pos=(i.toLong()*vaultText.length/vaultSteps).toInt().coerceAtMost(vaultText.lastIndex)
        val mutated=mutateAscii(vaultText,pos)
        if(runCatching{HistoryVaultArchivePackageCodec.parseCanonical(mutated)}.isFailure) rejected++
    }
    check(rejected==vaultSteps)

    rejected=0
    val backupText=backupRaw
    val backupSteps=minOf(96,backupText.length)
    repeat(backupSteps){i->
        val pos=(i.toLong()*backupText.length/backupSteps).toInt().coerceAtMost(backupText.lastIndex)
        val mutated=mutateAscii(backupText,pos)
        val ok=runCatching{
            val b=RecoveryBackupCodec.parseCanonical(mutated)
            RecoveryBackupCrypto.open(b,backupPair.recoveryCode)
        }.isSuccess
        if(!ok) rejected++
    }
    check(rejected==backupSteps)
    println("PASS deterministic mutation corpus rejects 128 vault-package and $backupSteps recovery-backup mutations")

    println("NEWER_PACKAGE_ID=${newer.packageId}")
    println("ROLLBACK_ANCHOR_ID=${extension.nextAnchor.anchorId}")
    println("CUTOVER_ID=${receipt.cutoverId}")
    println("RECOVERY_BACKUP_ID=${backupPair.backup.backupId}")
    println("ALL R21 SECURITY HARDENING CHECKS PASS")
}
