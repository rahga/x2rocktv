// Linux command-line frontend. `./gradlew :cli:installDist` produces cli/build/install/x2rocktv/bin/x2rocktv.
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
    applicationName = "x2rocktv"
    mainClass.set("com.rahga.x2rock.cli.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)
    implementation(libs.clikt)
    implementation(libs.mordant)

    // MPRIS over the session bus (daemon). Pure-Java transport on JDK 16+ unix sockets, no JNI.
    implementation(libs.dbus.java.core)
    runtimeOnly(libs.dbus.java.transport)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit)
}

// Bake the install location into the launcher so `x2rocktv install-handler` can write an absolute
// Exec= line without the user having to know where installDist put things.
application.applicationDefaultJvmArgs = listOf("-Dx2rocktv.launcher=APP_HOME_PLACEHOLDER/bin/x2rocktv")
tasks.startScripts {
    doLast {
        unixScript.writeText(unixScript.readText().replace("APP_HOME_PLACEHOLDER", "'\"\$APP_HOME\"'"))
        windowsScript.writeText(windowsScript.readText().replace("APP_HOME_PLACEHOLDER", "%APP_HOME%"))
    }
}
