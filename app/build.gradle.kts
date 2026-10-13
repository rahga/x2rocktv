import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * The version is the tag (T-12): built at `v0.1.0` it is 0.1.0; past it, `git describe` says how far
 * (0.1.0-3-gabc1234), and `-dirty` marks uncommitted changes. No tag at all, or no git, is 0.0.0-dev.
 */
val tagVersion: String = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v[0-9]*", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().removePrefix("v").ifEmpty { "0.0.0-dev" }

/**
 * Play's upload key, used only when asked for: `./gradlew :app:bundleRelease -Px2rock.upload`. Its
 * details live in `keystore.properties` at the root, never committed (`storeFile`, `storePassword`,
 * `keyAlias`, `keyPassword`). Every other release build keeps the debug key, so it still installs
 * over what the house's TVs have: a TV refuses an update signed by a different key.
 */
val uploadKey: Properties? = rootProject.file("keystore.properties")
    .takeIf { providers.gradleProperty("x2rock.upload").isPresent }
    ?.let { file ->
        require(file.exists()) { "-Px2rock.upload needs keystore.properties at the project root" }
        Properties().apply { file.inputStream().use(::load) }
    }

android {
    namespace = "com.rahga.x2rock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rahga.x2rock"
        minSdk = 23
        targetSdk = 35
        // Must rise with every build that leaves this machine, to Play or to a TV: neither installs a
        // lower one over a higher. 3 is 0.1.0, the first closed test.
        versionCode = 3
        versionName = tagVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (uploadKey != null) create("upload") {
            storeFile = rootProject.file(uploadKey.getProperty("storeFile"))
            storePassword = uploadKey.getProperty("storePassword")
            keyAlias = uploadKey.getProperty("keyAlias")
            keyPassword = uploadKey.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // Signed with the local debug key unless the upload key is asked for (above), so a
            // release build installs on this household's own TVs — over a debug install, with no
            // uninstall and no lost settings. Debug builds run several times slower on a TV box:
            // 4.7s to the room list on the Streamer against 0.6-0.8s for this (2026-10-05).
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
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
        buildConfig = true
    }

}

dependencies {
    // Sonos API, models, repositories — pure JVM, shared with desktop frontends
    implementation(project(":core"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Compose TV
    implementation(libs.androidx.tv.material)

    // Leanback (provides Theme.Leanback for the manifest theme)
    implementation(libs.androidx.leanback)

    // TV Provider (home screen channels)
    implementation(libs.androidx.tvprovider)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Network: OkHttp comes through :core, which is where the player client is built.

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)
    implementation(libs.androidx.palette)

    // Security

    // Hilt
    implementation(libs.hilt.android)
    // For the ratings cache (com.rahga.x2rock.smapi.PrefsRatingsStore) — `:core` keeps this
    // as `implementation`, so it isn't on `:app`'s classpath by default.
    implementation(libs.gson)
    ksp(libs.hilt.compiler)

    // Test
    testImplementation(libs.junit)
    // The same FakePlayer :core tests against, so view models are exercised over a real
    // socket and real captured payloads rather than a hand-stubbed repository.
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)

    // On-device focus tests (T-10), against the same fake household the unit tests use.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(testFixtures(project(":core")))
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
