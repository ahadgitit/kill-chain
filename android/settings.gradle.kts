pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "KillChain"
include(":core")
// The Android app module needs the Android Gradle Plugin and SDK (from Google's Maven).
if (file("app").exists() && (System.getenv("ANDROID_HOME") != null || file("local.properties").exists())) include(":app")
