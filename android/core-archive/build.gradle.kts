plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "org.eidolang.core.archive"
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
    // Ed25519 for BEP44 heads. Android exposes no general-purpose Ed25519 KeyFactory or
    // KeyPairGenerator below API 35 -- on API 34 the only service is
    // AndroidKeyStoreBCWorkaround's Signature, which rejects non-keystore keys. Bouncy Castle's
    // low-level signer needs no provider registration and works from minSdk 26.
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testImplementation(libs.junit)
}
