package org.eidolang.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.eidolang.core.crypto.AndroidKeystoreIdentityStore
import org.eidolang.core.crypto.B64Url
import org.eidolang.core.crypto.HexSha256
import org.eidolang.core.crypto.IdentityCanonical
import org.eidolang.core.crypto.IdentityParser
import org.eidolang.core.crypto.IdentityVerifier
import org.eidolang.core.crypto.JcaCrypto
import org.eidolang.core.crypto.JvmPrivateIdentity
import org.eidolang.core.crypto.PublicIdentityBundleV1
import org.eidolang.core.crypto.PublicKeyCodec
import org.eidolang.core.multidevice.AndroidRootAuthority
import org.eidolang.core.multidevice.AndroidSecondaryDeviceIdentityStore
import org.eidolang.core.multidevice.DeviceEnrollmentAuthority
import org.eidolang.core.multidevice.DeviceRosterAuthority
import org.eidolang.core.multidevice.DeviceRosterCanonical
import org.eidolang.core.multidevice.DeviceRosterVerifier
import org.eidolang.core.canonical.EidogramCanonical
import org.eidolang.core.hardening.AndroidRepositoryTextProtector
import org.eidolang.core.hardening.RecoveryBackupCodeCodec
import org.eidolang.core.hardening.RecoveryBackupCodec
import org.eidolang.core.hardening.RecoveryBackupCrypto
import org.eidolang.core.model.EidogramAction
import org.eidolang.core.model.EidogramDocumentV1
import org.eidolang.core.model.FixedTransform
import org.eidolang.core.multidevice.JvmRootAuthority
import org.eidolang.core.vault.HistoryVaultController
import org.eidolang.core.vault.HistoryVaultRecoveryCoordinator
import org.eidolang.core.vault.HistoryVaultRecoverySecret
import org.eidolang.core.multidevice.EnrollmentCanonical
import org.eidolang.core.repository.AndroidSqliteMessengerRepository
import org.eidolang.core.repository.LocalMessengerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.SecureRandom

/**
 * R20/R22 secondary-device enrollment across two genuinely independent Android installs.
 *
 * Each step is a separate test method so the host driver can alternate devices and carry the
 * exported files between them, exactly as `R22_PRODUCT_WORKFLOW.md` describes ("all flows above
 * are executable through local files"). Nothing here may be run as one suite on one device — that
 * would prove nothing about two installs.
 *
 * Run via `tools/two-device-enrollment.sh`.
 */
@TwoDeviceOnly
class TwoDeviceEnrollmentTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Sneakernet between the two devices. Kept in app-private storage and moved by the host with
     * `run-as`, because `/sdcard/Android/data/<pkg>` is not reliably reachable over adb on
     * Android 11+ (Samsung in particular).
     */
    private fun exchange(): File =
        File(context.filesDir, "exchange").apply { mkdirs() }

    private fun ex(name: String) = File(exchange(), name)

    /** Device-private state that must survive between steps on the primary. */
    private fun state(name: String) = File(context.filesDir, name)

    private fun File.write(text: String) = writeText(text, Charsets.UTF_8)
    private fun File.read(): String {
        check(exists()) { "missing exchange artifact: $absolutePath" }
        return readText(Charsets.UTF_8)
    }

    // ---------------------------------------------------------------- primary

    @Test
    fun step1PrimaryCreatesRootAndExportsOwnerIdentity() {
        val identity = AndroidKeystoreIdentityStore(context).ensure()
        val authority = AndroidRootAuthority()

        val roster0 = DeviceRosterAuthority.issue(
            authority, null, listOf(identity.certificate.deviceId), emptyList(),
        )
        state("roster.json").write(DeviceRosterCanonical.json(roster0))

        // The product imports its epoch-0 roster at first run; the repository roster chain has to
        // start at epoch 0 or DeviceRosterVerifier.advance rejects every later epoch.
        AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context)).use { repo ->
            LocalMessengerService(repo, identity, SecureRandom())
                .importDeviceRoster(DeviceRosterCanonical.json(roster0), 100)
        }

        ex("owner-identity.json").write(
            IdentityCanonical.publicBundleJson(
                PublicIdentityBundleV1(identity.user, identity.certificate)
            )
        )

        println("ENROLL step1 primary user=${identity.user.userId}")
        println("ENROLL step1 primary device=${identity.certificate.deviceId}")
        println("ENROLL step1 roster epoch=${roster0.body.epoch} active=${roster0.body.activeDeviceIds.size}")
        assertEquals(0, roster0.body.epoch)
    }

    @Test
    fun step3PrimaryAuthorizesSecondaryAndIssuesRoster() {
        val random = SecureRandom()
        val authority = AndroidRootAuthority()
        val primary = AndroidKeystoreIdentityStore(context).ensure()

        val request = EnrollmentCanonical.parseCanonical(ex("enrollment-request.json").read())
        assertTrue(
            "enrollment request must verify against this root",
            DeviceEnrollmentAuthority.verifyRequest(request, authority.user),
        )

        val cert = DeviceEnrollmentAuthority.authorize(authority, request)
        assertNotEquals(
            "secondary must be a distinct device",
            primary.certificate.deviceId, cert.deviceId,
        )

        ex("authorized-bundle.json").write(
            IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(authority.user, cert))
        )

        val previous = DeviceRosterCanonical.parseCanonical(state("roster.json").read())
        val roster1 = DeviceRosterAuthority.issue(
            authority, previous,
            listOf(primary.certificate.deviceId, cert.deviceId),
            emptyList(),
        )
        state("roster.json").write(DeviceRosterCanonical.json(roster1))
        ex("roster.json").write(DeviceRosterCanonical.json(roster1))

        // advance() also requires every referenced device to be a known bundle, so the authorized
        // certificate must be registered before the successor roster is imported.
        AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context)).use { repo ->
            val service = LocalMessengerService(repo, primary, random)
            service.importOwnDevice(
                IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(authority.user, cert)), 2_100,
            )
            service.importDeviceRoster(DeviceRosterCanonical.json(roster1), 2_110)
        }

        // Cross-device proof: wrap a secret to the secondary's freshly authorized encryption key.
        // Only that device's non-exportable AndroidKeyStore private key can open it.
        val secret = JcaCrypto.randomBytes(32, random)
        try {
            val wrapped = JcaCrypto.wrapKey(
                PublicKeyCodec.rsa(cert.body.encryptionPublicKeyB64), secret, random,
            )
            ex("wrapped-key.b64").write(B64Url.encode(wrapped))
            ex("expected-secret.sha256").write(HexSha256.of(secret))
        } finally {
            secret.fill(0)
        }

        println("ENROLL step3 authorized device=${cert.deviceId}")
        println("ENROLL step3 roster epoch=${roster1.body.epoch} active=${roster1.body.activeDeviceIds}")
        assertEquals(1, roster1.body.epoch)
        assertEquals(previous.rosterId, roster1.body.previousRosterId)
    }

    // -------------------------------------------------------------- secondary

    @Test
    fun step2SecondaryCreatesEnrollmentRequest() {
        val ownerCanonical = ex("owner-identity.json").read()

        // A secondary install must never hold a user root of its own.
        assertTrue(
            "secondary device must not already own a primary root identity",
            !AndroidKeystoreIdentityStore(context).exists(),
        )

        val request = AndroidSecondaryDeviceIdentityStore(context)
            .createEnrollmentRequest(ownerCanonical)
        ex("enrollment-request.json").write(request)

        val parsed = EnrollmentCanonical.parseCanonical(request)
        println("ENROLL step2 secondary request=${parsed.requestId}")
        println("ENROLL step2 secondary claims user=${parsed.body.userId}")
        assertEquals(
            IdentityParser.parsePublicBundleCanonical(ownerCanonical).user.userId,
            parsed.body.userId,
        )
    }

    @Test
    fun step4SecondaryInstallsAuthorizationAndProvesPossession() {
        val owner = IdentityParser.parsePublicBundleCanonical(ex("owner-identity.json").read())
        val store = AndroidSecondaryDeviceIdentityStore(context)

        val identity = store.installAuthorizedBundle(ex("authorized-bundle.json").read())
        val roster = DeviceRosterCanonical.parseCanonical(ex("roster.json").read())

        assertEquals("secondary must join the same user identity", owner.user.userId, identity.user.userId)
        assertTrue(
            "device certificate must verify against the owner root",
            IdentityVerifier.verifyDevice(owner.user, identity.certificate),
        )
        assertTrue(
            "roster must carry a valid root signature",
            DeviceRosterVerifier.verifySignature(roster, owner.user),
        )
        assertTrue(
            "secondary must be listed active in the roster",
            identity.certificate.deviceId in roster.body.activeDeviceIds,
        )
        assertTrue(
            "primary must still be listed active",
            owner.device.deviceId in roster.body.activeDeviceIds,
        )
        assertEquals(2, roster.body.activeDeviceIds.size)
        assertNotEquals(owner.device.deviceId, identity.certificate.deviceId)

        // Steps 5-6 of the documented secondary flow (R22_METHODOLOGY §1): register the owner's
        // device and import the root-signed roster into the local repository. Skipping this leaves
        // the install half-onboarded — EidoRootApp routes to the messenger because the bundle
        // exists, but the repository has no user or roster and the home screen does not render.
        val repo = AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context))
        val service = LocalMessengerService(repo, identity)
        service.importOwnDevice(ex("owner-identity.json").read(), 100)
        val importedRoster = service.importDeviceRosterSnapshot(ex("roster.json").read(), 100)
        assertEquals(roster.rosterId, importedRoster.rosterId)
        repo.close()

        // The decisive check: open the secret the primary wrapped to this device on the other host.
        val opened = identity.unwrapMessageKey(B64Url.decode(ex("wrapped-key.b64").read()))
        try {
            assertEquals(
                "secondary failed to open the key wrapped by the primary",
                ex("expected-secret.sha256").read(), HexSha256.of(opened),
            )
        } finally {
            opened.fill(0)
        }

        println("ENROLL step4 secondary device=${identity.certificate.deviceId}")
        println("ENROLL step4 joined user=${identity.user.userId} epoch=${roster.body.epoch}")
        println("ENROLL step4 cross-device key unwrap OK")
    }

    // ------------------------------------------- message across the two devices

    /**
     * Both devices belong to the same user, and `createConversation` requires a remote
     * participant, so the conversation partner is generated here. The point of the step is not
     * the partner but the history vault: an eidogram authored on the primary must reappear on the
     * secondary with an identical MessageId and document hash.
     */
    @Test
    fun step5PrimaryAuthorsEidogramAndExportsSecureVault() {
        val random = SecureRandom()
        val primary = AndroidKeystoreIdentityStore(context).ensure()
        val repo = AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context))
        val service = LocalMessengerService(repo, primary, random)

        val roster = DeviceRosterCanonical.parseCanonical(ex("roster.json").read())

        val peer = JvmPrivateIdentity.generate(random)
        val peerRoster = DeviceRosterAuthority.issue(
            JvmRootAuthority.fromPrimary(peer, random), null,
            listOf(peer.certificate.deviceId), emptyList(),
        )
        service.importContact(
            IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(peer.user, peer.certificate)),
            "Bob", 2_100,
        )
        service.importDeviceRoster(DeviceRosterCanonical.json(peerRoster), 2_111)

        val conversation = service.createConversation(
            listOf(peer.user.userId), title = "Two-device R22", createdAtMs = 2_200,
        )
        val document = EidogramDocumentV1(
            listOf(EidogramAction.Add(0, "g000001", "circle.red.m", FixedTransform(390_000, 500_000), 0))
        )
        val message = service.send(conversation.conversationId, document, 2_300)

        val secret = HistoryVaultRecoverySecret.generate(random)
        val vault = HistoryVaultController.create(primary, secret, JcaCrypto.randomBytes(16, random), random)
        vault.createEpoch(roster.body.activeDeviceIds)
        assertEquals("exactly one message must be archived", 1, vault.archiveAll(repo, service))

        val pair = RecoveryBackupCrypto.create(vault.descriptor.vaultId, secret, random)
        ex("vault-package.json").write(vault.exportPackage(repo))
        ex("recovery-backup.json").write(RecoveryBackupCodec.json(pair.backup))
        ex("recovery-code.txt").write(
            RecoveryBackupCodeCodec.encode(vault.descriptor.vaultId, pair.recoveryCode)
        )
        ex("conversation-id.txt").write(conversation.conversationId)
        ex("expected-message-id.txt").write(message.messageId)
        ex("expected-document.sha256").write(EidogramCanonical.contentHash(document))
        repo.close()

        println("ENROLL step5 conversation=${conversation.conversationId}")
        println("ENROLL step5 message=${message.messageId}")
        println("ENROLL step5 vault=${vault.descriptor.vaultId}")
    }

    @Test
    fun step6SecondaryRestoresVaultAndReadsTheSameEidogram() {
        val random = SecureRandom()
        val identity = AndroidSecondaryDeviceIdentityStore(context).load()
            ?: error("secondary is not enrolled; run step4 first")
        val repo = AndroidSqliteMessengerRepository(context, AndroidRepositoryTextProtector(context))

        val (_, code) = RecoveryBackupCodeCodec.decode(ex("recovery-code.txt").read())
        val secret = RecoveryBackupCrypto.open(
            RecoveryBackupCodec.parseCanonical(ex("recovery-backup.json").read()), code,
        )

        val state = HistoryVaultRecoveryCoordinator(repo, identity, random)
            .restore(ex("vault-package.json").read(), secret, 3_000)
        assertTrue(
            "restore must not depend on historical message-key grants",
            repo.keyGrants().isEmpty(),
        )

        val service = LocalMessengerService(
            repo, identity, random, historicalDocumentProvider = state.documentProvider,
        )
        val timeline = service.timeline(ex("conversation-id.txt").read())
        assertEquals("restored timeline must hold exactly the archived message", 1, timeline.size)
        assertEquals(
            "MessageId must survive the move to another device unchanged",
            ex("expected-message-id.txt").read(), timeline.single().messageId,
        )
        assertEquals(
            "decrypted eidogram must be byte-identical to the authored document",
            ex("expected-document.sha256").read(),
            EidogramCanonical.contentHash(timeline.single().document),
        )
        repo.close()

        println("ENROLL step6 restored message=${timeline.single().messageId}")
        println("ENROLL step6 document hash matches; key grants=${repo.keyGrants().size}")
    }
}
