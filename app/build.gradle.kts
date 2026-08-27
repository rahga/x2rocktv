import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.rahga.x2rock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rahga.x2rock"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SONOS_CLIENT_ID",
            "\"${localProps.getProperty("SONOS_CLIENT_ID", "")}\"")
        buildConfigField("String", "SONOS_CLIENT_SECRET",
            "\"${localProps.getProperty("SONOS_CLIENT_SECRET", "")}\"")
    }

    buildTypes {
        release {
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

    kotlinOptions {
        jvmTarget = "17"
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

    // Network (retrofit/okhttp come through :core; AppModule still builds the clients here)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)

    // Image loading
    implementation(libs.coil.compose)

    // Security
    implementation(libs.androidx.security.crypto)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Test
    testImplementation(libs.junit)
}
