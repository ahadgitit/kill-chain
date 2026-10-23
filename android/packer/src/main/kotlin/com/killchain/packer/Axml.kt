package com.killchain.packer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Little-endian byte builder. */
class Bytes {
    private val out = ByteArrayOutputStream()
    val size get() = out.size()
    fun u8(v: Int) = apply { out.write(v and 0xff) }
    fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
    fun u32(v: Int) = apply { u16(v); u16(v ushr 16) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun pad4() = apply { while (out.size() % 4 != 0) u8(0) }
    fun zeros(n: Int) = apply { repeat(n) { u8(0) } }
    fun toByteArray(): ByteArray = out.toByteArray()
}

/** A chunk: type, header size, then header fields and body; total size is filled in. */
fun chunk(type: Int, headerSize: Int, header: Bytes.() -> Unit, body: ByteArray = ByteArray(0)): ByteArray {
    val h = Bytes().apply(header).toByteArray()
    require(h.size + 8 == headerSize) { "header size ${h.size + 8} != $headerSize for type $type" }
    return Bytes().u16(type).u16(headerSize).u32(headerSize + body.size).bytes(h).bytes(body).toByteArray()
}

/** A UTF-16 string pool chunk (as aapt writes for binary XML). */
fun stringPool(strings: List<String>, utf8: Boolean = false): ByteArray {
    val data = Bytes()
    val offsets = IntArray(strings.size)
    for ((i, s) in strings.withIndex()) {
        offsets[i] = data.size
        if (utf8) {
            val b = s.toByteArray(Charsets.UTF_8)
            fun len(n: Int) { if (n > 0x7f) { data.u8(0x80 or (n shr 8)); data.u8(n and 0xff) } else data.u8(n) }
            len(s.length); len(b.size); data.bytes(b).u8(0)
        } else {
            require(s.length < 0x8000)
            data.u16(s.length)
            for (ch in s) data.u16(ch.code)
            data.u16(0)
        }
    }
    data.pad4()
    val headerSize = 28
    val stringsStart = headerSize + strings.size * 4
    val body = Bytes()
    for (o in offsets) body.u32(o)
    body.bytes(data.toByteArray())
    return chunk(0x0001, headerSize, {
        u32(strings.size); u32(0); u32(if (utf8) 0x100 else 0); u32(stringsStart); u32(0)
    }, body.toByteArray())
}

/** Minimal binary XML writer (AndroidManifest.xml and res/ XML files). */
class Axml {
    companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val T_REF = 0x01; const val T_STRING = 0x03; const val T_DEC = 0x10; const val T_HEX = 0x11; const val T_BOOL = 0x12
    }
    class Attr(val ns: String?, val name: String, val resId: Int, val type: Int, val data: Int, val str: String? = null)
    class El(val name: String, val attrs: List<Attr>, val kids: List<El>)

    fun str(name: String, value: String, id: Int = 0, ns: String? = ANDROID) = Attr(ns, name, id, T_STRING, 0, value)
    fun int(name: String, value: Int, id: Int) = Attr(ANDROID, name, id, T_DEC, value)
    fun hex(name: String, value: Int, id: Int) = Attr(ANDROID, name, id, T_HEX, value)
    fun bool(name: String, value: Boolean, id: Int) = Attr(ANDROID, name, id, T_BOOL, if (value) -1 else 0)
    fun ref(name: String, value: Int, id: Int) = Attr(ANDROID, name, id, T_REF, value)

    fun encode(root: El): ByteArray {
        // String pool: attribute names with resource ids first (in the order of the resource map).
        val resAttrs = LinkedHashMap<String, Int>()
        fun walk(e: El) { for (a in e.attrs) if (a.resId != 0) resAttrs[a.name] = a.resId; e.kids.forEach(::walk) }
        walk(root)
        val strings = ArrayList<String>(resAttrs.keys)
        fun idx(s: String): Int { val i = strings.indexOf(s); if (i >= 0) return i; strings.add(s); return strings.size - 1 }
        val nodes = Bytes()
        val nsUri = idx(ANDROID); val nsPrefix = idx("android")
        fun node(type: Int, body: Bytes.() -> Unit) = nodes.bytes(chunk(type, 16, { u32(1); u32(-1) }, Bytes().apply(body).toByteArray()))
        node(0x0100) { u32(nsPrefix); u32(nsUri) }
        fun emit(e: El) {
            val attrs = e.attrs.sortedWith(compareBy({ if (it.resId == 0) Int.MAX_VALUE else it.resId }, { it.name }))
            val name = idx(e.name)
            node(0x0102) {
                u32(-1); u32(name); u16(20); u16(20); u16(attrs.size); u16(0); u16(0); u16(0)
                for (a in attrs) {
                    u32(if (a.ns != null) idx(a.ns) else -1)
                    u32(idx(a.name))
                    val raw = if (a.type == T_STRING) idx(a.str!!) else -1
                    u32(raw)
                    u16(8); u8(0); u8(a.type); u32(if (a.type == T_STRING) raw else a.data)
                }
            }
            e.kids.forEach(::emit)
            node(0x0103) { u32(-1); u32(name) }
        }
        emit(root)
        node(0x0101) { u32(nsPrefix); u32(nsUri) }
        val resMap = Bytes().apply { for (id in resAttrs.values) u32(id) }.toByteArray()
        val body = Bytes().bytes(stringPool(strings)).bytes(chunk(0x0180, 8, {}, resMap)).bytes(nodes.toByteArray()).toByteArray()
        return chunk(0x0003, 8, {}, body)
    }
}

fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
