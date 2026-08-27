// Pure JVM: everything that talks to Sonos and nothing that talks to Android.
// Shared by the TV app and any desktop or CLI frontend.
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
    // `api`: these types appear in the public surface (OkHttpClient params, SonosApiService, Result<T>).
    api(libs.kotlinx.coroutines.core)
    api(libs.retrofit)
    api(libs.okhttp)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
}
