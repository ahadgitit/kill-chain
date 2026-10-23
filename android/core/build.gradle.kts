// Pure-Kotlin game core (map, data, simulation, AI). No Android dependencies, so it can be
// built and tested on any JVM, and the Android app uses it as a library.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    kotlin("jvm")
    application
}
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
application { mainClass.set("com.killchain.core.HeadlessKt") }
dependencies { testImplementation(kotlin("test")) }
tasks.test { useJUnitPlatform() }
