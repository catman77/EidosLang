plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "org.eidolang.core.admission"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":core-crypto"))
    implementation(project(":core-hardening"))
    implementation(project(":core-repository"))
    implementation(project(":core-vault"))
}
