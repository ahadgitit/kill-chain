// SDK-free build of the Android app (sources in ../app). Compiles against the Android framework
// jar published on Maven Central, tests under Robolectric, and packs a signed APK:
//   gradle :apk:assembleApk   ->  apk/build/outputs/KillChain.apk
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { kotlin("jvm") }

val androidJar = "org.robolectric:android-all:15-robolectric-12650502"
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.addAll("-Xjdk-release=1.8", "-Xlambdas=class", "-Xsam-conversions=class")
    }
}
sourceSets {
    main { kotlin.srcDir("../app/src/main/kotlin") }
}
dependencies {
    compileOnly(androidJar)
    implementation(project(":core"))
    testImplementation(androidJar)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
// Robolectric's own downloader gets rate-limited; Gradle fetches the instrumented framework instead.
val robolectricSdk by configurations.creating { isTransitive = false }
dependencies { robolectricSdk("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7") }
val robolectricDeps by tasks.registering(Copy::class) {
    from(robolectricSdk); into(layout.buildDirectory.dir("robolectric-deps"))
}
// androidx.test (Google's Maven only) is replaced by small stand-ins in src/test/java.
configurations.matching { it.name.startsWith("test") }.all {
    for (g in listOf("androidx.test", "androidx.test.espresso", "androidx.test.ext", "androidx.test.services", "androidx.annotation",
        "androidx.concurrent", "androidx.tracing", "androidx.lifecycle", "androidx.core", "androidx.collection", "androidx.arch.core")) exclude(group = g)
}

// The tests compile to Java 17 (Robolectric needs it); only main is dexed.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17); freeCompilerArgs.set(listOf()) }
}
tasks.named<JavaCompile>("compileTestJava") { sourceCompatibility = "17"; targetCompatibility = "17" }
// What the Android Gradle Plugin would hand Robolectric: where the manifest and assets are.
val robolectricConfig by tasks.registering {
    val out = layout.buildDirectory.dir("robolectric-config")
    outputs.dir(out)
    doLast {
        val f = out.get().file("com/android/tools/test_config.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("android_merged_manifest=${file("src/test/robolectric/AndroidManifest.xml").path}\n" +
            "android_merged_assets=${file("../app/src/main/assets").path}\n" +
            "android_resource_apk=${layout.buildDirectory.file("pack/unsigned.apk").get().asFile.path}\n" +
            "android_custom_package=com.killchain.game\n")
    }
}
sourceSets { test { resources.srcDir(robolectricConfig) } }
tasks.test {
    dependsOn(robolectricDeps, "assembleApk")
    systemProperty("robolectric.offline", "true")
    // The game core is plain Kotlin: running it uninstrumented keeps the simulation fast.
    systemProperty("robolectric.classesToNotInstrumentRegex", "com\\.killchain\\.core\\..*")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-deps").get().asFile.path)
    systemProperty("robolectric.graphicsMode", "NATIVE")
    systemProperty("kc.shots", layout.buildDirectory.dir("shots").get().asFile.path)
    systemProperty("kc.assets", file("../app/src/main/assets").path)
    maxHeapSize = "3g"
    testLogging { showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

val versionCode = 1
val versionName = "1.0"
val keystore = (findProperty("kc.keystore") as String?) ?: rootProject.file("release.keystore").path
val storePass = (findProperty("kc.storepass") as String?) ?: "killchain"

val genKeystore by tasks.registering(Exec::class) {
    onlyIf { !file(keystore).exists() }
    commandLine("keytool", "-genkeypair", "-keystore", keystore, "-storetype", "PKCS12", "-storepass", storePass,
        "-alias", "killchain", "-keyalg", "RSA", "-keysize", "4096", "-validity", "10000",
        "-dname", "CN=Kill Chain, O=Kill Chain, C=PK")
}

tasks.register<JavaExec>("assembleApk") {
    dependsOn("jar", ":core:jar", genKeystore)
    group = "build"
    description = "Builds and signs apk/build/outputs/KillChain.apk"
    classpath = project(":packer").the<SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("com.killchain.packer.PackKt")
    // apksig 2.3 (the version on Maven Central) uses JDK-internal X.509 classes for v1 signing.
    jvmArgs("--add-exports=java.base/sun.security.x509=ALL-UNNAMED", "--add-exports=java.base/sun.security.pkcs=ALL-UNNAMED",
        "--add-exports=java.base/sun.security.util=ALL-UNNAMED")
    val runtime = configurations.getByName("runtimeClasspath")
    doFirst {
        val inputs = (listOf(tasks.getByName<Jar>("jar").archiveFile.get().asFile) + runtime.files).joinToString(File.pathSeparator)
        args(
            "inputs=$inputs",
            "assets=${file("../app/src/main/assets").path}",
            "out=${layout.buildDirectory.file("outputs/KillChain.apk").get().asFile.path}",
            "work=${layout.buildDirectory.dir("pack").get().asFile.path}",
            "store=${layout.buildDirectory.dir("outputs/store").get().asFile.path}",
            "resOut=${file("../app/src/main/res").path}",
            "package=com.killchain.game", "activity=com.killchain.app.MainActivity", "label=Kill Chain",
            "versionCode=$versionCode", "versionName=$versionName", "minSdk=26", "targetSdk=35",
            "keystore=$keystore", "storepass=$storePass", "alias=killchain",
        )
    }
}
