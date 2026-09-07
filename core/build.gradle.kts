// Pure JVM: everything that talks to Sonos and nothing that talks to Android.
// Being Android-free is what lets the LAN transport be exercised against real speakers
// from a plain JVM test, which is how it was developed.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // `api`: these types appear in the public surface (flows, and the OkHttpClient that
    // SonosHousehold takes so the image loader can share it).
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
}
