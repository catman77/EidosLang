package org.eidolang.app

import org.eidolang.feature.home.Relay
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Could not ask" must not be reported as "nobody by that name".
 *
 * Searching the nickname directory used to fold every failure — server down, connection refused,
 * TLS rejected, a 500 — into an empty list, and the dialog printed "Никого не нашлось". The app was
 * stating as fact that a person does not exist when all it actually knew was that it had not
 * managed to ask. That is the same class of defect as an avatar fetch that fails into silence, and
 * it is worse here because it produces a confident falsehood about someone.
 *
 * Aimed at an address that is certainly dead rather than at the real relay: a test that passes only
 * while the relay happens to be down proves nothing, and would start failing the moment it came
 * back — which is exactly when this distinction still needs to hold.
 */
class DirectoryUnreachableTest {

    @Test
    fun anUnreachableDirectoryIsNullAndNotAnEmptyResult() {
        // Port 1 on loopback: nothing listens there, and the refusal is immediate.
        assertNull(
            "недоступный каталог выдан за пустой результат",
            Relay.searchDirectory("CatmanJoe", base = "https://127.0.0.1:1"),
        )
        println("DIRECTORY PASS an unreachable directory is null, not 'nobody found'")
    }
}
