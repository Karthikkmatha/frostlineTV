package tv.frostline.feature.chat

/**
 * Reads the container of an image file (no decoding) to say how many frames it has. Used to decide "is this emote
 * animated" without depending on the platform decoder, which hides that on some formats.
 */
object ImageSniff {
    /** Frames in a GIF or WebP file; 1 for a still or an unknown format. */
    fun frameCount(b: ByteArray): Int = when {
        isGif(b) -> gifFrames(b)
        isWebp(b) -> webpFrames(b)
        else -> 1
    }

    fun isAnimated(b: ByteArray) = frameCount(b) > 1

    private val headBuf = ThreadLocal.withInitial { ByteArray(HEAD) }

    /**
     * Same answer as [isAnimated] on the file's bytes, but reads at most [HEAD] bytes into a per-thread buffer instead of the
     * whole file into a new array on every decode (a transient allocation of the size of the emote file, thousands of times in fast chat).
     * Only a GIF that is longer than the buffer and shows one frame so far is read whole.
     */
    fun isAnimated(file: java.io.File): Boolean {
        val buf = headBuf.get()!!
        val n = file.inputStream().use { ins ->
            var got = 0
            while (got < buf.size) { val k = ins.read(buf, got, buf.size - got); if (k < 0) break; got += k }
            got
        }
        val head = if (n == buf.size) buf else buf.copyOf(n)
        // A WebP that is not flagged animated in its VP8X header is a still, whatever its size.
        if (isWebp(head) && !(head.size >= 21 && String(head, 12, 4, Charsets.ISO_8859_1) == "VP8X" && (u8(head, 20) and 0x02) != 0)) return false
        val frames = frameCount(head)
        if (frames > 1 || n < HEAD) return frames > 1
        // The head is full: a plain still has no more to say; a GIF or WebP with one frame so far might.
        return if (isGif(head) || isWebp(head)) isAnimated(file.readBytes()) else false
    }

    private const val HEAD = 32 * 1024

    private fun isGif(b: ByteArray) = b.size > 13 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()
    private fun isWebp(b: ByteArray) = b.size > 16 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP"

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le32(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16) or (u8(b, i + 3) shl 24)

    private fun webpFrames(b: ByteArray): Int {
        var p = 12
        var frames = 0
        while (p + 8 <= b.size) {
            val tag = String(b, p, 4, Charsets.ISO_8859_1)
            val size = le32(b, p + 4)
            if (size < 0) break
            if (tag == "ANMF") frames++
            p += 8 + size + (size and 1)
        }
        return frames.coerceAtLeast(1)
    }

    private fun gifFrames(b: ByteArray): Int {
        var p = 13
        val flags = u8(b, 10)
        if (flags and 0x80 != 0) p += 3 * (1 shl ((flags and 7) + 1))
        var frames = 0
        while (p < b.size) {
            when (u8(b, p)) {
                0x3B -> return frames.coerceAtLeast(1)
                0x21 -> { p += 2; p = skipSubBlocks(b, p) }
                0x2C -> {
                    frames++
                    if (p + 10 > b.size) return frames
                    val f = u8(b, p + 9)
                    p += 10
                    if (f and 0x80 != 0) p += 3 * (1 shl ((f and 7) + 1))
                    p++ // LZW minimum code size
                    p = skipSubBlocks(b, p)
                }
                else -> return frames.coerceAtLeast(1)
            }
        }
        return frames.coerceAtLeast(1)
    }

    private fun skipSubBlocks(b: ByteArray, start: Int): Int {
        var p = start
        while (p < b.size) {
            val n = u8(b, p)
            p += 1 + n
            if (n == 0) break
        }
        return p
    }
}
