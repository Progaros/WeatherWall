package info.olli.weatherlwp

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Minimal looping GIF89a writer for ARGB frames (test-only; android.jar has no javax.imageio).
 * One median-cut palette is shared by all frames so colours stay stable while the scene moves,
 * and ordered dithering hides the steps in sky gradients without frame-to-frame flicker.
 */
internal object GifWriter {

    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    private const val DITHER_SPREAD = 22

    fun write(file: File, width: Int, height: Int, frames: List<IntArray>, delayCs: Int) {
        val palette = buildPalette(frames)
        val lookup = buildLookup(palette)
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        writeShort(out, width); writeShort(out, height)
        out.write(0xF7); out.write(0); out.write(0)  // global table, 256 entries
        for (rgb in palette) { out.write(rgb shr 16 and 0xFF); out.write(rgb shr 8 and 0xFF); out.write(rgb and 0xFF) }
        // NETSCAPE2.0 application extension: loop forever
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0))
        val indices = ByteArray(width * height)
        for (frame in frames) {
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04))  // graphic control, disposal "keep"
            writeShort(out, delayCs); out.write(0); out.write(0)
            out.write(0x2C); writeShort(out, 0); writeShort(out, 0)
            writeShort(out, width); writeShort(out, height); out.write(0)
            for (i in frame.indices) {
                val d = (BAYER[(i / width and 3) * 4 + (i % width and 3)] * 2 - 15) * DITHER_SPREAD / 30
                val p = frame[i]
                val r = ((p shr 16 and 0xFF) + d).coerceIn(0, 255)
                val g = ((p shr 8 and 0xFF) + d).coerceIn(0, 255)
                val b = ((p and 0xFF) + d).coerceIn(0, 255)
                indices[i] = lookup[(r shr 2 shl 12) or (g shr 2 shl 6) or (b shr 2)]
            }
            out.write(8)
            writeSubBlocks(out, lzw(indices))
        }
        out.write(0x3B)
        file.writeBytes(out.toByteArray())
    }

    private fun key15(argb: Int) = (argb shr 9 and 0x7C00) or (argb shr 6 and 0x3E0) or (argb shr 3 and 0x1F)

    private fun buildPalette(frames: List<IntArray>): IntArray {
        val counts = LongArray(32768)
        for (frame in frames) for (p in frame) counts[key15(p)]++
        val used = counts.indices.filter { counts[it] > 0 }.toIntArray()

        fun channel(k: Int, c: Int) = k shr (10 - 5 * c) and 31
        // Median cut: repeatedly split the box with the widest (count-weighted) channel range.
        val boxes = mutableListOf(used)
        while (boxes.size < 256) {
            var bestIdx = -1; var bestScore = 0L; var bestChannel = 0
            for ((idx, box) in boxes.withIndex()) {
                if (box.size < 2) continue
                val total = box.sumOf { counts[it] }
                for (c in 0..2) {
                    val range = box.maxOf { channel(it, c) } - box.minOf { channel(it, c) }
                    val score = range.toLong() * range * total
                    if (score > bestScore) { bestScore = score; bestIdx = idx; bestChannel = c }
                }
            }
            if (bestIdx < 0) break
            val sorted = boxes[bestIdx].sortedBy { channel(it, bestChannel) }
            val half = sorted.sumOf { counts[it] } / 2
            var acc = 0L; var cut = 1
            for (j in sorted.indices) { acc += counts[sorted[j]]; if (acc >= half) { cut = (j + 1).coerceIn(1, sorted.size - 1); break } }
            boxes[bestIdx] = sorted.subList(0, cut).toIntArray()
            boxes.add(sorted.subList(cut, sorted.size).toIntArray())
        }
        return IntArray(256) { i ->
            val box = boxes.getOrNull(i) ?: return@IntArray 0
            val total = box.sumOf { counts[it] }.coerceAtLeast(1)
            fun avg(c: Int) = (box.sumOf { counts[it] * (channel(it, c) * 8 + 4) } / total).toInt().coerceIn(0, 255)
            (avg(0) shl 16) or (avg(1) shl 8) or avg(2)
        }
    }

    /** Nearest palette entry for every 6-bit-per-channel colour (finer than the 5-bit palette boxes). */
    private fun buildLookup(palette: IntArray): ByteArray = ByteArray(262144) { k ->
        val r = (k shr 12 and 63) * 4 + 2; val g = (k shr 6 and 63) * 4 + 2; val b = (k and 63) * 4 + 2
        var best = 0; var bestDist = Int.MAX_VALUE
        for (i in palette.indices) {
            val c = palette[i]
            val dr = r - (c shr 16 and 0xFF); val dg = g - (c shr 8 and 0xFF); val db = b - (c and 0xFF)
            val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
            if (d < bestDist) { bestDist = d; best = i }
        }
        best.toByte()
    }

    /** GIF LZW with 8-bit minimum code size; grows the code width exactly as decoders expect. */
    private fun lzw(indices: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var bitBuf = 0L; var bitCount = 0; var codeSize = 9
        fun emit(code: Int) {
            bitBuf = bitBuf or (code.toLong() shl bitCount); bitCount += codeSize
            while (bitCount >= 8) { out.write((bitBuf and 0xFF).toInt()); bitBuf = bitBuf ushr 8; bitCount -= 8 }
        }
        val dict = HashMap<Int, Int>(8192)
        var next = 258
        emit(256)
        var prefix = indices[0].toInt() and 0xFF
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            val found = dict[key]
            if (found != null) { prefix = found; continue }
            emit(prefix)
            if (next < 4096) {
                dict[key] = next++
                if (next > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                emit(256); dict.clear(); next = 258; codeSize = 9
            }
            prefix = k
        }
        emit(prefix)
        emit(257)
        if (bitCount > 0) out.write((bitBuf and 0xFF).toInt())
        return out.toByteArray()
    }

    private fun writeSubBlocks(out: ByteArrayOutputStream, data: ByteArray) {
        var pos = 0
        while (pos < data.size) {
            val n = minOf(255, data.size - pos)
            out.write(n); out.write(data, pos, n); pos += n
        }
        out.write(0)
    }

    private fun writeShort(out: ByteArrayOutputStream, v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
}
