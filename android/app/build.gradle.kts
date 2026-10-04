plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "org.eidolang.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.eidolang.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1-R22.1"
        // Two-device enrollment is driven by tools/two-device-enrollment.sh, not by this suite.
        testInstrumentationRunnerArguments["notAnnotation"] =
            "org.eidolang.app.TwoDeviceOnly,org.eidolang.app.NativeSessionHeavy"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":feature-editor"))
    implementation(project(":core-model"))
    implementation(project(":core-crypto"))
    implementation(project(":core-message"))
    implementation(project(":core-multidevice"))
    implementation(project(":core-session"))
    implementation(project(":core-vault"))
    implementation(project(":core-hardening"))
    implementation(project(":core-archive"))
    implementation(project(":core-transport"))
    implementation(project(":feature-sync"))
    implementation(project(":feature-home"))
    implementation(project(":feature-messenger"))
    implementation(project(":feature-onboarding"))
    implementation(project(":feature-viewer"))
    implementation(project(":core-repository"))
    implementation(project(":feature-archive"))
    implementation(project(":core-recovery"))
    implementation(project(":transport-libtorrent4j"))
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(project(":core-admission"))
    // Only so the invite test can report the QR module count it asserts on. The app itself gets
    // zxing through feature-home.
    androidTestImplementation(libs.zxing.core)
    androidTestImplementation(project(":core-canonical"))
    // Prebuilt libtorrent 2.1.0 Android binding, test-only: it backs the R17 on-device transport
    // admission and is deliberately NOT a dependency of the shipped app.
    androidTestImplementation("org.libtorrent4j:libtorrent4j:2.1.0-35")
    androidTestImplementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-35")
    androidTestImplementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-35")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Tor ships as `<abi>/libtor.so` inside a plain jar, but onionwrapper looks for it in the app's
// native library directory and execs it as a file. So the binaries are unpacked into jniLibs at
// build time — vendoring them into the source tree would put a 9 MB binary under version control
// with no provenance, and this way the version in the dependency block stays the single source.
val torBinaries: Configuration by configurations.creating

dependencies {
    torBinaries("org.briarproject:tor-android:0.4.8.14")
    // onionwrapper installs the pluggable-transport binary unconditionally, even when no bridge is
    // configured, so it has to be present or start() fails before Tor ever runs.
    torBinaries("org.briarproject:lyrebird-android:0.5.0-3")
}

// A plain path, not a Provider: AGP rejects Providers here because it cannot tell generated
// directories from checked-in ones.
val torJniDir = layout.buildDirectory.dir("torJniLibs").get().asFile

val extractTorBinaries by tasks.registering(Copy::class) {
    from({ torBinaries.map { zipTree(it) } }) { include("*/lib*.so") }
    into(torJniDir)
}

android {
    sourceSets["main"].jniLibs.srcDir(torJniDir)
    // Without this the .so stays compressed inside the APK and never exists as a file on disk,
    // which is exactly the FileNotFoundException the wrapper reports.
    packaging { jniLibs { useLegacyPackaging = true } }
}

tasks.named("preBuild") { dependsOn(extractTorBinaries) }
