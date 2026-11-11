package de.itdummkopfen.text2video

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimaler WAV-Leser (16-Bit PCM), wie ihn die Android-Sprachausgabe erzeugt. */
object Wav {

    class Pcm(val sampleRate: Int, val channels: Int, val data: ByteArray) {
        val frameBytes: Int get() = 2 * channels
        val durationUs: Long get() = data.size.toLong() / frameBytes * 1_000_000L / sampleRate
    }

    fun read(file: File): Pcm {
        val b = file.readBytes()
        if (b.size < 12 || ascii(b, 0) != "RIFF" || ascii(b, 8) != "WAVE") {
            throw IOException("Sprachausgabe lieferte keine WAV-Datei")
        }
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var data: ByteArray? = null

        while (pos + 8 <= b.size && data == null) {
            val id = ascii(b, pos)
            val start = pos + 8
            var size = bb.getInt(pos + 4)
            if (size < 0 || start + size > b.size) size = b.size - start
            when (id) {
                "fmt " -> {
                    format = bb.getShort(start).toInt() and 0xFFFF
                    channels = bb.getShort(start + 2).toInt()
                    rate = bb.getInt(start + 4)
                    bits = bb.getShort(start + 14).toInt()
                }
                "data" -> {
                    if (size == 0) size = b.size - start   // manche Engines schreiben 0
                    data = b.copyOfRange(start, start + size)
                }
            }
            pos = start + size + (size and 1)
        }

        if (data == null || rate <= 0 || channels <= 0) throw IOException("WAV-Datei unvollständig")
        if ((format != 1 && format != 0xFFFE) || bits != 16) {
            throw IOException("Nicht unterstütztes Audioformat ($bits Bit, Format $format)")
        }
        val frame = 2 * channels
        val usable = data.size / frame * frame
        return Pcm(rate, channels, if (usable == data.size) data else data.copyOf(usable))
    }

    /** Stille in Millisekunden passend zum Format. */
    fun silence(rate: Int, channels: Int, ms: Int): ByteArray =
        ByteArray(rate * ms / 1000 * 2 * channels)

    private fun ascii(b: ByteArray, off: Int) = String(b, off, 4, Charsets.US_ASCII)
}
