plugins { alias(libs.plugins.android.library) }
android {
    namespace = "org.eidolang.transport.libtorrent4j"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":core-transport"))
    implementation(project(":core-archive"))
    implementation(project(":core-crypto"))
    implementation(project(":core-hardening"))
    // Prebuilt libtorrent 2.1.0. R17_DEPENDENCY_PIN.md pins 2.1.1 and describes an NDK build of
    // the project's own JNI; R22.1 ships this binding instead. See R22_1_METHODOLOGY.md.
    api("org.libtorrent4j:libtorrent4j:2.1.0-35")
    runtimeOnly("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-35")
    runtimeOnly("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-35")
    runtimeOnly("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-35")
    runtimeOnly("org.libtorrent4j:libtorrent4j-android-x86:2.1.0-35")
}
