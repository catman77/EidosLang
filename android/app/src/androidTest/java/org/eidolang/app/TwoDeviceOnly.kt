package org.eidolang.app

/**
 * Marks a test that is only meaningful when driven across two independent installs by
 * `tools/two-device-enrollment.sh`. Excluded from the ordinary single-device
 * `connectedDebugAndroidTest` suite via `notAnnotation`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class TwoDeviceOnly
