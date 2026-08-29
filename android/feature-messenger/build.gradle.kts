plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "org.eidolang.feature.messenger"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
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
    implementation(project(":core-session"))
    implementation(project(":core-vault"))
    implementation(project(":core-hardening"))
    implementation(project(":core-admission"))
    implementation(project(":core-repository"))
    implementation(project(":feature-archive"))
    implementation(project(":core-recovery"))
    implementation(project(":feature-editor"))
    implementation(project(":feature-viewer"))
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
