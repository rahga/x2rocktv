// Pure JVM: everything that talks to Sonos and nothing that talks to Android.
// Being Android-free is what lets the LAN transport be exercised against real speakers
// from a plain JVM test, which is how it was developed.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // FakePlayer and the captured fixtures are published as test fixtures rather than kept
    // private to this module's tests: :app's view models need the same fake household, and
    // a shared testing artifact is what this is.
    `java-test-fixtures`
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

// The live suite reaches real speakers and is opt-in; these carry the opt-in through to the
// test JVM, which does not inherit the Gradle daemon's system properties.
tasks.test {
    listOf("x2rock.live", "x2rock.live.room").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    // A live run must not be served from a previous run's results.
    outputs.upToDateWhen { System.getProperty("x2rock.live") == null }
}

dependencies {
    // `api`: these types appear in the public surface (flows, and the OkHttpClient that
    // SonosHousehold takes so the image loader can share it).
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.javax.inject)

    testFixturesImplementation(libs.mockwebserver)
    testFixturesImplementation(libs.okhttp.tls)
    // `api`, not `implementation`: FakePlayer hands back JsonObject, so Gson is part of the
    // fixtures' surface and consumers need it on their compile classpath.
    testFixturesApi(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
}
