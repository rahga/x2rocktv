plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.rahga.x2rock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rahga.x2rock"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Signed with the local debug key, so a release build installs on this household's
            // own TVs — over a debug install, with no uninstall and no lost settings. Debug
            // builds run several times slower on a TV box: 4.7s to the room list on the
            // Streamer against 0.6-0.8s for this (2026-10-05). Publishing anywhere would need
            // a real upload key in place of this line.
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
