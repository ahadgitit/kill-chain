package com.killchain.packer

import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class Counting(private val out: java.io.OutputStream) : java.io.FilterOutputStream(out) {
    var n = 0L
    override fun write(b: Int) { out.write(b); n++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); n += len }
}

/**
 * Builds a signed APK without the Android SDK.
 *   pack key=value ...
 *     inputs=a.jar:dir:...   compiled classes (app, core, Kotlin stdlib)
 *     assets=dir             files for assets/
 *     out=app.apk  work=dir  package=  label=  versionCode=  versionName=  minSdk=  targetSdk=
 *     keystore=  storepass=  alias=  store=dir (optional Play Store listing art)
 */
fun main(argv: Array<String>) {
    val a = argv.associate { it.substringBefore('=') to it.substringAfter('=') }
    val work = File(a.getValue("work")).apply { deleteRecursively(); mkdirs() }
    val pkg = a.getValue("package")
    val minSdk = a.getValue("minSdk").toInt(); val targetSdk = a.getValue("targetSdk").toInt()

    // 1. Dex. Multi-release jars carry Java 9+ classes under META-INF; dx only needs the base ones.
    val inputs = a.getValue("inputs").split(File.pathSeparator).filter { it.isNotBlank() }.mapIndexed { i, p ->
        val f = File(p)
        if (f.isDirectory || !f.exists()) return@mapIndexed f
        val clean = File(work, "in$i.jar")
        ZipFile(f).use { z ->
            ZipOutputStream(clean.outputStream()).use { zo ->
                for (e in z.entries()) {
                    if (!e.name.endsWith(".class") || e.name.startsWith("META-INF/") || e.name.endsWith("module-info.class")) continue
                    zo.putNextEntry(ZipEntry(e.name)); z.getInputStream(e).copyTo(zo); zo.closeEntry()
                }
            }
        }
        clean
    }.filter { it.exists() }
    val dex = File(work, "classes.dex")
    val dxArgs = arrayOf("--dex", "--min-sdk-version=$minSdk", "--output=${dex.path}") + inputs.map { it.path }
    com.android.dx.command.Main.main(dxArgs)
    check(dex.exists()) { "dx produced no classes.dex" }

    // 2. Resources: the adaptive launcher icon (and legacy PNGs for old launchers).
    val arsc = Arsc(pkg)
    val files = LinkedHashMap<String, ByteArray>()
    val dens = listOf("mdpi" to 160, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)
    for ((q, d) in dens) {
        val k = d / 160.0
        val bg = "res/drawable-$q-v4/ic_launcher_background.png"; files[bg] = Icon.adaptiveBg((108 * k).toInt())
        val fg = "res/drawable-$q-v4/ic_launcher_foreground.png"; files[fg] = Icon.adaptiveFg((108 * k).toInt())
        arsc.add("drawable", "ic_launcher_background", Arsc.Cfg(d, 4), bg)
        arsc.add("drawable", "ic_launcher_foreground", Arsc.Cfg(d, 4), fg)
    }
    for ((q, d) in dens) {
        val p = "res/mipmap-$q-v4/ic_launcher.png"; files[p] = Icon.legacy((48 * d / 160.0).toInt())
        arsc.add("mipmap", "ic_launcher", Arsc.Cfg(d, 4), p)
    }
    val x = Axml()
    val bgId = arsc.id("drawable", "ic_launcher_background"); val fgId = arsc.id("drawable", "ic_launcher_foreground")
    val DRAWABLE = 0x01010199
    val adaptive = x.encode(Axml.El("adaptive-icon", emptyList(), listOf(
        Axml.El("background", listOf(x.ref("drawable", bgId, DRAWABLE)), emptyList()),
        Axml.El("foreground", listOf(x.ref("drawable", fgId, DRAWABLE)), emptyList()),
    )))
    files["res/mipmap-anydpi-v26/ic_launcher.xml"] = adaptive
    arsc.add("mipmap", "ic_launcher", Arsc.Cfg(0xfffe, 26), "res/mipmap-anydpi-v26/ic_launcher.xml")
    val iconId = arsc.id("mipmap", "ic_launcher")

    // 3. Manifest.
    val activity = Axml.El("activity", listOf(
        x.str("name", a.getValue("activity"), 0x01010003),
        x.bool("exported", true, 0x01010010),
        x.int("screenOrientation", 6, 0x0101001e),                // sensorLandscape
        x.hex("configChanges", 0x1ff0, 0x0101001f),               // orientation|screenSize|screenLayout|keyboard|uiMode|density...
        x.int("launchMode", 2, 0x0101001d),                       // singleTask
        x.bool("hardwareAccelerated", true, 0x010102d3),
        x.bool("resizeableActivity", true, 0x010104f6),
    ), listOf(Axml.El("intent-filter", emptyList(), listOf(
        Axml.El("action", listOf(x.str("name", "android.intent.action.MAIN", 0x01010003)), emptyList()),
        Axml.El("category", listOf(x.str("name", "android.intent.category.LAUNCHER", 0x01010003)), emptyList()),
    ))))
    val app = Axml.El("application", listOf(
        x.str("label", a.getValue("label"), 0x01010001),
        x.ref("icon", iconId, 0x01010002),
        x.ref("roundIcon", iconId, 0x0101052c),
        x.ref("theme", 0x0103022f, 0x01010000),                   // Theme.Material.NoActionBar.Fullscreen
        x.bool("allowBackup", true, 0x01010280),
        x.bool("isGame", true, 0x010103f4),
        x.int("appCategory", 0, 0x01010545),                      // game
        x.bool("supportsRtl", false, 0x010103af),
        x.bool("hardwareAccelerated", true, 0x010102d3),
    ), listOf(activity))
    val manifest = x.encode(Axml.El("manifest", listOf(
        x.int("versionCode", a.getValue("versionCode").toInt(), 0x0101021b),
        x.str("versionName", a.getValue("versionName"), 0x0101021c),
        x.int("compileSdkVersion", targetSdk, 0x01010572),
        x.str("package", pkg, 0, ns = null),
    ), listOf(
        Axml.El("uses-sdk", listOf(x.int("minSdkVersion", minSdk, 0x0101020c), x.int("targetSdkVersion", targetSdk, 0x01010270)), emptyList()),
        Axml.El("uses-feature", listOf(x.str("name", "android.hardware.touchscreen", 0x01010003), x.bool("required", false, 0x0101028e)), emptyList()),
        app,
    )))

    // 4. Zip: resources.arsc and PNGs stored (uncompressed, aligned by the signer), the rest deflated.
    val unsigned = File(work, "unsigned.apk")
    val counter = Counting(unsigned.outputStream().buffered())
    ZipOutputStream(counter).use { zo ->
        fun put(name: String, data: ByteArray, store: Boolean) {
            val e = ZipEntry(name)
            if (store) {
                e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                e.crc = CRC32().apply { update(data) }.value
                // zipalign: pad the local header's extra field so the data starts on a 4-byte boundary.
                val pad = ((4 - (counter.n + 30 + name.toByteArray().size) % 4) % 4).toInt()
                if (pad > 0) e.extra = ByteArray(pad)
            }
            e.time = 1_700_000_000_000L
            zo.putNextEntry(e); zo.write(data); zo.closeEntry()
        }
        put("AndroidManifest.xml", manifest, false)
        put("classes.dex", dex.readBytes(), false)
        put("resources.arsc", arsc.encode(), true)
        for ((n, d) in files) put(n, d, n.endsWith(".png"))
        a["assets"]?.let { dir ->
            val root = File(dir)
            root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach {
                val rel = it.relativeTo(root).invariantSeparatorsPath
                put("assets/$rel", it.readBytes(), rel.endsWith(".bin") || rel.endsWith(".ttf"))
            }
        }
    }

    // 5. Sign (APK Signature Scheme v2, aligned).
    val ks = KeyStore.getInstance("PKCS12")
    val pass = a.getValue("storepass").toCharArray()
    File(a.getValue("keystore")).inputStream().use { ks.load(it, pass) }
    val alias = a.getValue("alias")
    val key = ks.getKey(alias, pass) as PrivateKey
    val certs = ks.getCertificateChain(alias).map { it as X509Certificate }
    val out = File(a.getValue("out")).apply { parentFile?.mkdirs(); delete() }
    ApkSigner.Builder(listOf(ApkSigner.SignerConfig.Builder("KILLCHN", key, certs).build()))
        .setInputApk(unsigned).setOutputApk(out).setMinSdkVersion(minSdk)
        .setV1SigningEnabled(false).setV2SigningEnabled(true)   // v2 covers every device from minSdk 24 up
        .build().sign()
    a["store"]?.let { Icon.storeArt(File(it)) }
    println("APK: ${out.path} (${out.length() / 1024} KB)")
}
