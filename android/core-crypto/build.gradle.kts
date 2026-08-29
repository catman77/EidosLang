plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "org.eidolang.core.crypto"
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
    testImplementation(libs.junit)
}
