package org.eidolang.app

/**
 * Marks a test that starts libtorrent sessions.
 *
 * `SessionManager.stop()` does not release native threads promptly enough for a Compose UI test to
 * run afterwards in the same instrumentation process: run in one suite, the libtorrent tests sort
 * before `MessengerSmokeTest` and the process dies partway with no assertion message. Each class
 * passes in isolation. They are therefore excluded from the default run and executed as a second
 * invocation — see `R22_1_VALIDATION.md`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class NativeSessionHeavy
