// Linux command-line frontend. `./gradlew :cli:installDist` produces cli/build/install/x2rock/bin/x2rock.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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

application {
    applicationName = "x2rock"
    mainClass.set("com.rahga.x2rock.cli.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.clikt)
    implementation(libs.mordant)

    testImplementation(libs.junit)
}

// Bake the install location into the launcher so `x2rock install-handler` can write an absolute
// Exec= line without the user having to know where installDist put things.
application.applicationDefaultJvmArgs = listOf("-Dx2rock.launcher=APP_HOME_PLACEHOLDER/bin/x2rock")
tasks.startScripts {
    doLast {
        unixScript.writeText(unixScript.readText().replace("APP_HOME_PLACEHOLDER", "'\"\$APP_HOME\"'"))
        windowsScript.writeText(windowsScript.readText().replace("APP_HOME_PLACEHOLDER", "%APP_HOME%"))
    }
}
