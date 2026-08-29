pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "EidoLangAndroid"
include(
    ":app",
    ":core-model",
    ":core-canonical",
    ":core-render",
    ":core-crypto",
    ":core-message",
    ":core-multidevice",
    ":core-session",
    ":core-vault",
    ":core-hardening",
    ":core-admission",
    ":core-archive",
    ":core-transport",
    ":core-repository",
    ":core-recovery",
    ":feature-sync",
    ":native-torrent",
    ":transport-libtorrent4j",
    ":feature-home",
    ":feature-library",
    ":feature-editor",
    ":feature-viewer",
    ":feature-messenger",
    ":feature-archive",
    ":feature-onboarding",
)
