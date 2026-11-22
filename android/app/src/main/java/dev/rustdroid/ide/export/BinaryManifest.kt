package dev.rustdroid.ide.export

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * R4A: edits a compiled (binary XML) AndroidManifest.xml — the format inside
 * an APK. "Export APK" uses it to give the game template its own package,
 * name and version. Only two things are touched:
 *  - the string pool (all texts live there): selected strings are replaced
 *    and the pool is rebuilt (always written back as UTF-16);
 *  - the integer value of `android:versionCode`.
 * Every other chunk is copied byte for byte. Pure — unit-tested on the JVM
 * with the real template manifest.
 */
object BinaryManifest {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val UTF8_FLAG = 0x100
    private const val TYPE_INT_DEC = 0x10

    /** The texts of the manifest, in string-pool order. */
    fun strings(axml: ByteArray): List<String> = readPool(buf(axml), poolOffset(axml)).strings

    /**
     * A new manifest where every string equal to a key of [replace] becomes
     * its value, and versionCode (when given) is set to [versionCode].
     */
    fun rewrite(axml: ByteArray, replace: Map<String, String>, versionCode: Int? = null): ByteArray {
        val b = buf(axml)
        require(b.getShort(0).toInt() and 0xffff == RES_XML_TYPE) { "not a binary XML file" }
        val poolAt = poolOffset(axml)
        val pool = readPool(b, poolAt)
        require(pool.styleCount == 0) { "styled strings are not supported" }

        val strings = pool.strings.map { replace[it] ?: it }
        val newPool = writePool(strings)

        val before = axml.copyOfRange(0, poolAt)
        val after = axml.copyOfRange(poolAt + pool.chunkSize, axml.size)
        val out = before + newPool + after
        val ob = buf(out)
        ob.putInt(4, out.size) // file header: total size

        if (versionCode != null) {
            setIntAttribute(ob, poolAt + newPool.size, strings, "versionCode", versionCode)
        }
        return out
    }

    // ---- string pool ----

    private class Pool(val strings: List<String>, val styleCount: Int, val chunkSize: Int)

    private fun buf(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun poolOffset(axml: ByteArray): Int {
        val b = buf(axml)
        var off = b.getShort(2).toInt() and 0xffff // file header size
        while (off < axml.size) {
            val type = b.getShort(off).toInt() and 0xffff
            if (type == RES_STRING_POOL_TYPE) return off
            off += b.getInt(off + 4)
        }
        error("no string pool")
    }

    private fun readPool(b: ByteBuffer, off: Int): Pool {
        val chunkSize = b.getInt(off + 4)
        val count = b.getInt(off + 8)
        val styleCount = b.getInt(off + 12)
        val flags = b.getInt(off + 16)
        val stringsStart = b.getInt(off + 20)
        val utf8 = flags and UTF8_FLAG != 0
        val headerSize = b.getShort(off + 2).toInt() and 0xffff
        val strings = (0 until count).map { i ->
            var p = off + stringsStart + b.getInt(off + headerSize + 4 * i)
            if (utf8) {
                // utf16 length, then utf8 length (each 1 or 2 bytes)
                if (b.get(p).toInt() and 0x80 != 0) p += 2 else p += 1
                var len = b.get(p).toInt() and 0xff
                p += 1
                if (len and 0x80 != 0) {
                    len = ((len and 0x7f) shl 8) or (b.get(p).toInt() and 0xff)
                    p += 1
                }
                String(ByteArray(len) { b.get(p + it) }, Charsets.UTF_8)
            } else {
                var len = b.getShort(p).toInt() and 0xffff
                p += 2
                if (len and 0x8000 != 0) {
                    len = ((len and 0x7fff) shl 16) or (b.getShort(p).toInt() and 0xffff)
                    p += 2
                }
                String(CharArray(len) { b.getChar(p + 2 * it) })
            }
        }
        return Pool(strings, styleCount, chunkSize)
    }

    private fun writePool(strings: List<String>): ByteArray {
        val data = java.io.ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for ((i, s) in strings.withIndex()) {
            offsets[i] = data.size()
            val len = s.length
            val bb = buf(ByteArray(4 + 2 * len + 2))
            if (len > 0x7fff) {
                bb.putShort((0x8000 or (len ushr 16)).toShort())
                bb.putShort((len and 0xffff).toShort())
            } else {
                bb.putShort(len.toShort())
            }
            for (c in s) bb.putChar(c)
            bb.putShort(0) // terminator
            data.write(bb.array(), 0, bb.position())
        }
        while (data.size() % 4 != 0) data.write(0)

        val header = 28
        val stringsStart = header + 4 * strings.size
        val size = stringsStart + data.size()
        val out = buf(ByteArray(size))
        out.putShort(RES_STRING_POOL_TYPE.toShort())
        out.putShort(header.toShort())
        out.putInt(size)
        out.putInt(strings.size)
        out.putInt(0) // styles
        out.putInt(0) // flags: UTF-16, not sorted
        out.putInt(stringsStart)
        out.putInt(0) // stylesStart
        offsets.forEach { out.putInt(it) }
        out.put(data.toByteArray())
        return out.array()
    }

    // ---- attributes ----

    /** Set the integer value of every attribute named [name] (e.g. versionCode). */
    private fun setIntAttribute(b: ByteBuffer, from: Int, strings: List<String>, name: String, value: Int) {
        val nameIdx = strings.indexOf(name)
        require(nameIdx >= 0) { "no $name attribute" }
        var off = from
        var found = false
        val end = b.getInt(4)
        while (off < end) {
            val type = b.getShort(off).toInt() and 0xffff
            val size = b.getInt(off + 4)
            if (type == RES_XML_START_ELEMENT_TYPE) {
                val ext = off + (b.getShort(off + 2).toInt() and 0xffff)
                val attrStart = b.getShort(ext + 8).toInt() and 0xffff
                val attrSize = b.getShort(ext + 10).toInt() and 0xffff
                val attrCount = b.getShort(ext + 12).toInt() and 0xffff
                for (i in 0 until attrCount) {
                    val a = ext + attrStart + i * attrSize
                    if (b.getInt(a + 4) == nameIdx) {
                        require(b.get(a + 15).toInt() == TYPE_INT_DEC) { "$name is not an integer" }
                        b.putInt(a + 16, value)
                        found = true
                    }
                }
            }
            off += size
        }
        require(found) { "no $name attribute" }
    }

    /** Integer value of the first attribute named [name] (for checks and tests). */
    fun intAttribute(axml: ByteArray, name: String): Int? {
        val b = buf(axml)
        val poolAt = poolOffset(axml)
        val pool = readPool(b, poolAt)
        val nameIdx = pool.strings.indexOf(name).takeIf { it >= 0 } ?: return null
        var off = poolAt + pool.chunkSize
        while (off < axml.size) {
            val type = b.getShort(off).toInt() and 0xffff
            if (type == RES_XML_START_ELEMENT_TYPE) {
                val ext = off + (b.getShort(off + 2).toInt() and 0xffff)
                val attrStart = b.getShort(ext + 8).toInt() and 0xffff
                val attrSize = b.getShort(ext + 10).toInt() and 0xffff
                val attrCount = b.getShort(ext + 12).toInt() and 0xffff
                for (i in 0 until attrCount) {
                    val a = ext + attrStart + i * attrSize
                    if (b.getInt(a + 4) == nameIdx) return b.getInt(a + 16)
                }
            }
            off += b.getInt(off + 4)
        }
        return null
    }
}
