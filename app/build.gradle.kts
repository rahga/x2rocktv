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
 * The release key, used only when asked for: `./gradlew :app:assembleRelease :app:bundleRelease
 * -Px2rock.upload` — the testers' APK and Play's bundle. Its details live in `keystore.properties` at
 * the root, never committed (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); see
 * docs/play-release.md. Every other release build keeps the debug key, so it still installs over what
 * the house's TVs have: a TV refuses an update signed by a different key.
 */
val uploadKey: Properties? = rootProject.file("keystore.properties")
    .takeIf { providers.gradleProperty("x2rock.upload").isPresent }
    ?.let { file ->
        require(file.exists()) { "-Px2rock.upload needs keystore.properties at the project root" }
        Properties().apply { file.inputStream().use(::load) }
    }

/**
 * A developer's own Sonos Control integration (T-27), compiled in only when asked for:
 * `-Px2rock.sonosIntegration`, or that line in `~/.gradle/gradle.properties` for every build of one's
 * own. It reads `~/x2rocktv-sonos.api.key` and `~/x2rocktv-sonos.api.secret` — x2rocktv's own
 * integration, not the x2rock CLI's (Sonos Terms 1(c); T-30) — (or `x2rock.sonosKeyFile` /
 * `x2rock.sonosSecretFile`) and the integration's redirect URL (`x2rock.sonosRedirect`).
 *
 * **Never in the repo, and never in the Play bundle.** Anything compiled into an APK can be read out
 * of it, so a build carrying a secret is for the TVs of whoever owns the integration, and a bundle —
 * Play's — refuses to carry it. An APK may take the release key (2026-10-17), so the owner's
 * "hardcore" build installs over the test builds on the owner's TVs and back without losing settings; its
 * version name says `+hardcore`, and such an APK is never uploaded anywhere. Without it every field is empty and the feature does not exist. Anyone
 * who registers with Sonos and signs its agreement can build with their own.
 */
val sonosIntegration: Triple<String, String, String>? =
    if (!providers.gradleProperty("x2rock.sonosIntegration").isPresent) null else {
        require(gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }) {
            "a Play bundle must not carry a Sonos secret: drop -Px2rock.sonosIntegration"
        }
        val home = System.getProperty("user.home")
        fun read(property: String, default: String): String {
            val file = File(providers.gradleProperty(property).getOrElse(default))
            require(file.canRead()) { "-Px2rock.sonosIntegration needs $file" }
            return file.readText().trim().also { require(it.isNotEmpty()) { "$file is empty" } }
        }
        Triple(
            read("x2rock.sonosKeyFile", "$home/x2rocktv-sonos.api.key"),
            read("x2rock.sonosSecretFile", "$home/x2rocktv-sonos.api.secret"),
            providers.gradleProperty("x2rock.sonosRedirect").getOrElse("https://rahga.github.io/x2rocktv/callback.html"),
        )
    }

/**
 * The salt the household's stored music-service sign-ins are sealed with, as 32 hex digits from
 * `~/x2rock-sonos.speaker.salt` (or `-Px2rock.speakerSaltFile`). Not part of the GPL-licensed source
 * package. Any build from a machine with the file carries it; without it, the app reads no stored
 * sign-ins and says so.
 */
val speakerSalt: String = run {
    val home = System.getProperty("user.home")
    val file = File(providers.gradleProperty("x2rock.speakerSaltFile").getOrElse("$home/x2rock-sonos.speaker.salt"))
    if (!file.canRead()) return@run ""
    file.readText().trim().lowercase().also {
        require(Regex("[0-9a-f]{32}").matches(it)) { "$file must hold the salt as 32 hex digits" }
    }
}

/** A Kotlin/Java string literal for a BuildConfig field. */
fun literal(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.rahga.x2rock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rahga.x2rock"
        minSdk = 23
        targetSdk = 35
        // Must rise with every build that leaves this machine, to Play or to a TV: neither installs a
        // lower one over a higher. 3 is 0.1.0, the first closed test.
        versionCode = 5
        // The maintainer's private build says so wherever a version is shown, so it is never mistaken
        // for, or uploaded as, a test build.
        versionName = tagVersion + if (sonosIntegration != null || providers.gradleProperty("x2rock.cloudContent").isPresent) "+hardcore" else ""
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SPEAKER_SALT", literal(speakerSalt))
        buildConfigField("String", "SONOS_KEY", literal(sonosIntegration?.first.orEmpty()))
        buildConfigField("String", "SONOS_SECRET", literal(sonosIntegration?.second.orEmpty()))
        buildConfigField("String", "SONOS_REDIRECT", literal(sonosIntegration?.third.orEmpty()))
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
