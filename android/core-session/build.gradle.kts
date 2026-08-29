plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "org.eidolang.core.session"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":core-canonical"))
    implementation(project(":core-crypto"))
    implementation(project(":core-message"))
    implementation(project(":core-multidevice"))
    implementation(project(":core-repository"))
    testImplementation(libs.junit)
}
