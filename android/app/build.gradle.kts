// Standard Android Gradle Plugin build of the app (Android Studio, or with ANDROID_HOME set).
// Use this for a Play Store release:  ./gradlew :app:bundleRelease  (an .aab, signed with your upload key).
// The SDK-free build in ../apk produces the same app as a signed APK without the SDK.
plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android")
}

android {
    namespace = "com.killchain.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.killchain.game"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        create("release") {
            val ks = rootProject.file("release.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = (findProperty("kc.storepass") as String?) ?: "killchain"
                keyAlias = "killchain"
                keyPassword = (findProperty("kc.storepass") as String?) ?: "killchain"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (rootProject.file("release.keystore").exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies { implementation(project(":core")) }
