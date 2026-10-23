// Packs the SDK-free APK: dexes the compiled app with dx, writes the binary manifest and
// resource table, draws the launcher icon, zips and signs (v1 + v2) with apksig.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    kotlin("jvm")
    application
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation("com.android.tools.build:apksig:2.3.0")
    implementation("com.jakewharton.android.repackaged:dalvik-dx:16.0.1")
}
application { mainClass.set("com.killchain.packer.PackKt") }
