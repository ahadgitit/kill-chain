// Pure-Kotlin game core (map, data, simulation, AI). No Android dependencies, so it can be
// built and tested on any JVM, and the Android app uses it as a library.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    kotlin("jvm")
    application
}
// Java 8 bytecode and the Java 8 API, so the classes run on Android (minSdk 26) as they are.
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll("-Xjdk-release=1.8", "-Xlambdas=class", "-Xsam-conversions=class")
    }
}
application { mainClass.set("com.killchain.core.HeadlessKt") }
dependencies { testImplementation(kotlin("test")) }
tasks.test { useJUnitPlatform() }
