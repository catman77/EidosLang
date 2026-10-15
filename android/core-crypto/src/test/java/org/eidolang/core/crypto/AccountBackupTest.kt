package org.eidolang.core.crypto

import org.junit.Assert.*
import org.junit.Test

class AccountBackupTest {
    @Test fun backupIsIndependentOfAnAndroidKeystoreAndRejectsWrongPasswordsOrEdits() {
        val plaintext = "Имя, контакты и переписка".toByteArray()
        val password = "long offline recovery password".toCharArray()
        val encoded = AccountBackupCipher.encrypt(plaintext, password)
        assertFalse(encoded.toString(Charsets.UTF_8).contains("переписка"))
        assertArrayEquals(plaintext, AccountBackupCipher.decrypt(encoded, password))
        assertTrue(runCatching { AccountBackupCipher.decrypt(encoded, "wrong password".toCharArray()) }.isFailure)
        val changed = encoded.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { AccountBackupCipher.decrypt(changed, password) }.isFailure)
        val changedHeader = encoded.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }
        assertTrue(runCatching { AccountBackupCipher.decrypt(changedHeader, password) }.isFailure)
        assertTrue(runCatching { AccountBackupCipher.decrypt(encoded.copyOf(25), password) }.isFailure)
    }

    @Test fun portableIdentityProvesAllPrivateKeysAndRejectsAnotherOwnersKey() {
        val identity = JvmPrivateIdentity.generate()
        val bundle = IdentityCanonical.publicBundleJson(PublicIdentityBundleV1(identity.user, identity.certificate))
        val material = PortableIdentity(bundle, B64Url.encode(identity.deviceSigning.private.encoded),
            B64Url.encode(identity.deviceEncryption.private.encoded), B64Url.encode(identity.rootSigning.private.encoded))
        val roundTrip = PortableIdentity.parse(material.json())
        assertEquals(identity.user, roundTrip.user)
        val foreign = JcaCrypto.p256()
        assertTrue(runCatching { PortableIdentity(bundle, B64Url.encode(foreign.private.encoded),
            material.encryptionPrivateB64, material.rootPrivateB64).validate() }.isFailure)
        val certificate = LocalKeyCertificate.create(identity.deviceEncryption.public, identity.deviceSigning)
        certificate.verify(identity.deviceSigning.public)
        assertArrayEquals(identity.deviceEncryption.public.encoded, certificate.publicKey.encoded)
    }
}
