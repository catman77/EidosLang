plugins { alias(libs.plugins.android.library) }
android { namespace="org.eidolang.feature.sync"; compileSdk=37; defaultConfig { minSdk=26 }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
}
dependencies { implementation(project(":core-transport")); implementation(project(":core-archive")); implementation(libs.androidx.work.runtime) }
