pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}
rootProject.name = "KillChain"
// Pure-Kotlin game core (map, data, simulation, AI): builds and tests on any JVM.
include(":core")
// The Android app, built the standard way with the Android Gradle Plugin and SDK
// (Android Studio, or ANDROID_HOME set). This is the module to use for a Play Store bundle.
if (file("app").exists() && (System.getenv("ANDROID_HOME") != null || file("local.properties").exists())) include(":app")
// SDK-free build of the same app sources, for machines without the Android SDK:
// compiles against the Android framework jar from Maven Central, runs the app under
// Robolectric for tests and screenshots, and packs a signed APK with dx + apksig.
include(":packer", ":apk")
