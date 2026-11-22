// R4A: template APK for "Export APK". A tiny Java-only app that loads the
// user's game from lib/arm64-v8a/libgame.so. R4A ships this APK unsigned in
// its assets, adds the game, renames the package and signs it on the phone.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "r4a.player"
    compileSdk = 35

    defaultConfig {
        // placeholder: replaced on export with the package the user chooses
        applicationId = "r4a.player.template"
        minSdk = 24
        // exported games are normal apps: a current targetSdk (Play Store ok)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // left unsigned on purpose: R4A signs each exported game
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
