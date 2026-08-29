plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "org.eidolang.feature.home"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    // Tor as Briar ships it: the same binary and the same wrapper that a shipping onion-routed
    // messenger uses, rather than a JNI build of our own.
    implementation("org.briarproject:onionwrapper-android:0.1.3")
    implementation("org.briarproject:tor-android:0.4.8.14")
    implementation("org.briarproject:dont-kill-me-lib:0.2.8")

    implementation(project(":core-model"))
    implementation(project(":core-canonical"))
    implementation(project(":core-crypto"))
    implementation(project(":core-message"))
    implementation(project(":core-multidevice"))
    implementation(project(":core-hardening"))
    // The onion service key is derived from the history-vault recovery secret, so that the address
    // survives a reinstall the same way the history does.
    implementation(project(":core-vault"))
    implementation(project(":core-repository"))
    implementation(project(":core-render"))
    implementation(project(":core-archive"))
    implementation(project(":core-transport"))
    implementation(project(":transport-libtorrent4j"))
    implementation(project(":feature-editor"))
    implementation(project(":feature-library"))
    implementation(project(":feature-viewer"))
    implementation(libs.zxing.core)
    implementation(libs.androidx.core.ktx)
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
