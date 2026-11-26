import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// R4A: the release key lives outside git. Either android/keystore.properties
// (gitignored; see keystore.properties.example) or the environment variables
// R4A_KEYSTORE, R4A_KEYSTORE_PASSWORD, R4A_KEY_ALIAS, R4A_KEY_PASSWORD.
// Without either, release builds fall back to the debug key (CI, contributors).
val releaseKey: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties()
    if (file.isFile) file.inputStream().use { props.load(it) }
    fun pick(prop: String, env: String) =
        props.getProperty(prop)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }
    val values = mapOf(
        "storeFile" to pick("storeFile", "R4A_KEYSTORE"),
        "storePassword" to pick("storePassword", "R4A_KEYSTORE_PASSWORD"),
        "keyAlias" to pick("keyAlias", "R4A_KEY_ALIAS"),
        "keyPassword" to pick("keyPassword", "R4A_KEY_PASSWORD"),
    )
    if (values.values.all { it != null }) values.mapValues { it.value!! } else null
}

android {
    namespace = "dev.rustdroid.ide"
    compileSdk = 35

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey.getValue("storeFile"))
                storePassword = releaseKey.getValue("storePassword")
                keyAlias = releaseKey.getValue("keyAlias")
                keyPassword = releaseKey.getValue("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "dev.rustdroid.ide"
        // Pinned to 28: executing binaries from app data (/data/data/<pkg>/files)
        // is blocked by SELinux for targetSdk >= 29. This is the same strategy
        // Termux uses; binaries run fine on Android 5-15 at targetSdk 28.
        // F-Droid distribution only — not Play-eligible at this target.
        // R4A (auditoría B1): 26 = Android 8. The app uses java.nio.file and
        // Process.isAlive/waitFor(timeout)/destroyForcibly (API 26) to save
        // files and run cargo; on Android 7 those crash.
        minSdk = 26
        targetSdk = 28
        versionCode = 12
        versionName = "0.3.0"

        // arm64-v8a only, everywhere (Phase 4 plan §4.1): the vendored
        // terminal modules build arm64-v8a only, and this filters the
        // packaged AAR-native libs (e.g. androidx.graphics.path, which
        // degrades gracefully to its Java fallback when its .so is absent)
        // so the APK ships exactly one ABI.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            // R4A: the real release key when configured (see top of file),
            // otherwise the debug key so CI and contributors can still build.
            signingConfig = if (releaseKey != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("R4A: no release key configured; release APK is signed with the DEBUG key")
                signingConfigs.getByName("debug")
            }
            // R4A (auditoría M10): R8 achica la APK de ~24 MB a ~7 MB (sobre
            // todo el SDK de Anthropic). Reglas en proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    buildFeatures {
        compose = true
    }

    packaging {
        // META-INF duplicate licenses from commons-compress et al.
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/LICENSE*"
        resources.excludes += "/META-INF/NOTICE*"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // opt-in real-bundle integration test: -Drd.bundle=/path/to/bundle.zip
        unitTests.all { test ->
            test.systemProperty("rd.bundle", System.getProperty("rd.bundle") ?: "")
        }
    }

    lint {
        // targetSdk 28 is deliberate and load-bearing (exec from app data —
        // see the defaultConfig comment and README "Why targetSdk 28").
        // ExpiredTargetSdkVersion is a Google Play *policy* check; this app
        // is F-Droid-only by design, so the policy does not apply. Killing
        // this check un-blocks assembleRelease, which lint-vital gates.
        disable += "ExpiredTargetSdkVersion"
    }
}

// R4A (auditoría, código obsoleto): kotlinOptions está deprecado desde
// Kotlin 2.x; ahora compilerOptions
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// R4A: "Export APK" needs the game template (gameplayer module) inside R4A.
// Build it and copy its unsigned APK into generated assets as gameplayer.apk.
val gamePlayerAssets = layout.buildDirectory.dir("generated/r4a-gameplayer")
val copyGamePlayer = tasks.register<Copy>("copyGamePlayerTemplate") {
    dependsOn(":gameplayer:assembleRelease")
    from(project(":gameplayer").layout.buildDirectory.file("outputs/apk/release/gameplayer-release-unsigned.apk"))
    into(gamePlayerAssets.map { it.dir("export") })
    rename { "gameplayer.apk" }
}
android.sourceSets.getByName("main").assets.srcDir(gamePlayerAssets)
tasks.named("preBuild") { dependsOn(copyGamePlayer) }

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coroutines + serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Networking (toolchain download, crates.io search)
    implementation(libs.okhttp)
    // R4A: Chat IA con Claude (Anthropic)
    implementation(libs.anthropic.java)
    // R4A (auditoría A3): el SDK de Anthropic trae Jackson 2.19.4 y
    // opentelemetry-api 1.62.0, y commons-compress trae commons-lang3 3.16.0,
    // todos con avisos de seguridad en OSV. Se suben; el BOM alinea todos los
    // módulos de Jackson a la misma versión.
    implementation(platform(libs.jackson.bom))
    constraints {
        implementation(libs.commons.lang3) { because("GHSA-j288-q9x7-2f5v") }
        implementation(libs.opentelemetry.api) { because("GHSA-ccrj-gw8r-854m") }
    }

    // Code editor (LGPL-2.1 — F-Droid compatible)
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)

    // Archive handling for the toolchain bundle (zip + tar.xz with unix modes)
    implementation(libs.commons.compress)
    implementation(libs.xz)

    // Cargo.toml parsing
    implementation(libs.tomlj)
    // R4A: sign exported game APKs
    implementation(libs.apksig)

    // Phase 4 terminal: vendored Termux modules (Apache-2.0, @ 3b66f87 —
    // see THIRD_PARTY.md). terminal-view exposes terminal-emulator via api,
    // but we depend on both explicitly since the app uses the emulator
    // session classes directly as well as the view.
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    // Unit tests (pure JVM)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.android)
}
