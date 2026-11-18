// Top-level build file — configuration shared across subprojects.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// FakePlayer serves the players' own `sonos-<MAC>.local` names on loopback, and a live run reaches
// real players by them. A JVM-wide HTTPS proxy (some CI and cloud containers set one through
// JAVA_TOOL_OPTIONS) would take those connections and reset them: 224 :core tests failed that way.
// A speaker is never behind a proxy, so `*.local` joins whatever skip list the build was given.
subprojects {
    tasks.withType<Test>().configureEach {
        val skip = System.getProperty("http.nonProxyHosts")
        systemProperty("http.nonProxyHosts", listOfNotNull("*.local", skip).joinToString("|"))
    }
}
