plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "org.eidolang.core.hardening"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-canonical"))
    implementation(project(":core-crypto"))
    implementation(project(":core-message"))
    implementation(project(":core-multidevice"))
    implementation(project(":core-repository"))
    implementation(project(":core-recovery"))
    implementation(project(":core-session"))
    implementation(project(":core-vault"))
    testImplementation(libs.junit)
}
