package com.killchain.packer

/**
 * Minimal resources.arsc writer: one package (0x7f) whose entries are all file references
 * (PNG or binary XML) in density / SDK-version configurations.
 */
class Arsc(private val pkg: String) {
    class Cfg(val density: Int = 0, val sdk: Int = 0)
    class Variant(val cfg: Cfg, val path: String)
    class Entry(val name: String, val variants: MutableList<Variant> = mutableListOf())
    class Type(val name: String, val entries: MutableList<Entry> = mutableListOf())

    val types = mutableListOf<Type>()

    fun add(type: String, name: String, cfg: Cfg, path: String): Int {
        val t = types.find { it.name == type } ?: Type(type).also { types.add(it) }
        val e = t.entries.find { it.name == name } ?: Entry(name).also { t.entries.add(it) }
        e.variants.add(Variant(cfg, path))
        return id(type, name)
    }

    fun id(type: String, name: String): Int {
        val ti = types.indexOfFirst { it.name == type }
        val ei = types[ti].entries.indexOfFirst { it.name == name }
        return (0x7f shl 24) or ((ti + 1) shl 16) or ei
    }

    private fun config(c: Cfg): Bytes {
        val b = Bytes().u32(64)
        b.zeros(4)                // imsi
        b.zeros(4)                // locale
        b.u8(0); b.u8(0); b.u16(c.density)   // orientation, touchscreen, density
        b.zeros(4)                // input
        b.zeros(4)                // screen size
        b.u16(c.sdk); b.u16(0)    // version
        while (b.size < 64) b.u8(0)
        return b
    }

    fun encode(): ByteArray {
        val values = types.flatMap { t -> t.entries.flatMap { e -> e.variants.map { it.path } } }.distinct()
        val keys = types.flatMap { t -> t.entries.map { it.name } }.distinct()
        val pkgBody = Bytes()
        val typeStrings = stringPool(types.map { it.name })
        val keyStrings = stringPool(keys)
        val pkgHeader = 288
        pkgBody.bytes(typeStrings).bytes(keyStrings)
        for ((ti, t) in types.withIndex()) {
            val n = t.entries.size
            // Type spec: which configuration dimensions each entry varies over.
            val spec = Bytes()
            for (e in t.entries) {
                var flags = 0
                if (e.variants.map { it.cfg.density }.distinct().size > 1) flags = flags or 0x0100
                if (e.variants.map { it.cfg.sdk }.distinct().size > 1) flags = flags or 0x0400
                spec.u32(flags)
            }
            pkgBody.bytes(chunk(0x0202, 16, { u8(ti + 1); u8(0); u16(0); u32(n) }, spec.toByteArray()))
            val cfgs = t.entries.flatMap { e -> e.variants.map { it.cfg } }.distinctBy { it.density to it.sdk }
            for (c in cfgs) {
                val offs = Bytes(); val data = Bytes()
                for (e in t.entries) {
                    val v = e.variants.find { it.cfg.density == c.density && it.cfg.sdk == c.sdk }
                    if (v == null) { offs.u32(-1); continue }
                    offs.u32(data.size)
                    data.u16(8).u16(0).u32(keys.indexOf(e.name))
                    data.u16(8).u8(0).u8(Axml.T_STRING).u32(values.indexOf(v.path))
                }
                val headerSize = 20 + 64
                val entriesStart = headerSize + n * 4
                val cfgBytes = config(c).toByteArray()
                pkgBody.bytes(chunk(0x0201, headerSize, { u8(ti + 1); u8(0); u16(0); u32(n); u32(entriesStart); bytes(cfgBytes) },
                    offs.toByteArray() + data.toByteArray()))
            }
        }
        val name = Bytes()
        for (i in 0 until 128) name.u16(if (i < pkg.length) pkg[i].code else 0)
        val pkgChunk = chunk(0x0200, pkgHeader, {
            u32(0x7f); bytes(name.toByteArray())
            u32(pkgHeader); u32(types.size)                       // type strings offset, last public type
            u32(pkgHeader + typeStrings.size); u32(keys.size)     // key strings offset, last public key
            u32(0)                                                // typeIdOffset
        }, pkgBody.toByteArray())
        val body = stringPool(values, utf8 = true) + pkgChunk
        return chunk(0x0002, 12, { u32(1) }, body)
    }
}
