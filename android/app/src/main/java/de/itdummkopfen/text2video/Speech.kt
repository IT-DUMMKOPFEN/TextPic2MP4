package de.itdummkopfen.text2video

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android-Sprachausgabe (offline mit den Stimmen des Geräts, z. B. Google).
 * Langer Text wird in Stücke geteilt, jeweils als WAV erzeugt und zusammengefügt.
 */
class Speech(context: Context, private val onReady: (Boolean) -> Unit) : TextToSpeech.OnInitListener {

    private val latches = ConcurrentHashMap<String, CountDownLatch>()
    private val errors = ConcurrentHashMap<String, Int>()
    private val tts = TextToSpeech(context.applicationContext, this)

    var germanVoices: List<Voice> = emptyList()
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            onReady(false); return
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { latches[it]?.countDown() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { errors[it] = -1; latches[it]?.countDown() }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { errors[it] = errorCode; latches[it]?.countDown() }
            }
        })
        tts.setLanguage(Locale.GERMANY)
        germanVoices = try {
            (tts.voices ?: emptySet())
                .filter { it.locale.language.startsWith("de") }
                .filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
                .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.locale.country != "DE" }, { it.name }))
        } catch (e: Exception) {
            emptyList()
        }
        onReady(true)
    }

    val defaultVoice: Voice?
        get() = try { tts.defaultVoice } catch (e: Exception) { null }

    fun configure(voice: Voice?, rate: Float, pitch: Float) {
        if (voice != null) tts.setVoice(voice) else tts.setLanguage(Locale.GERMANY)
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
    }

    fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.stop(); tts.shutdown()
    }

    /** Erzeugt PCM-Audio für den gesamten Text. Muss im Hintergrund-Thread laufen. */
    fun synthesize(text: String, workDir: File, cancelled: () -> Boolean, progress: (Int, Int) -> Unit): Wav.Pcm {
        val maxLen = minOf(3000, TextToSpeech.getMaxSpeechInputLength() - 100)
        val chunks = splitText(text, maxLen)
        if (chunks.isEmpty()) throw IOException("Kein Text vorhanden")

        val out = ByteArrayOutputStream()
        var rate = 0
        var channels = 0

        chunks.forEachIndexed { i, chunk ->
            if (cancelled()) throw InterruptedException()
            val file = File(workDir, "tts_$i.wav").apply { delete() }
            val id = "chunk_${System.nanoTime()}_$i"
            val latch = CountDownLatch(1)
            latches[id] = latch
            val r = tts.synthesizeToFile(chunk, Bundle(), file, id)
            if (r != TextToSpeech.SUCCESS) throw IOException("Sprachausgabe hat die Anfrage abgelehnt")
            if (!latch.await(180, TimeUnit.SECONDS)) throw IOException("Sprachausgabe: Zeitüberschreitung")
            latches.remove(id)
            errors.remove(id)?.let { throw IOException("Sprachausgabe-Fehler (Code $it)") }

            val pcm = Wav.read(file)
            file.delete()
            if (i == 0) {
                rate = pcm.sampleRate; channels = pcm.channels
                out.write(Wav.silence(rate, channels, 300))     // kurzer Vorlauf
            } else if (pcm.sampleRate != rate || pcm.channels != channels) {
                throw IOException("Uneinheitliches Audioformat der Sprachausgabe")
            }
            out.write(pcm.data)
            out.write(Wav.silence(rate, channels, 150))         // Pause zwischen Abschnitten
            progress(i + 1, chunks.size)
        }
        out.write(Wav.silence(rate, channels, 600))             // Nachlauf
        return Wav.Pcm(rate, channels, out.toByteArray())
    }

    companion object {
        /** Teilt an Satzenden, notfalls an Leerzeichen. */
        fun splitText(text: String, maxLen: Int): List<String> {
            val result = ArrayList<String>()
            val sentences = text.trim().split(Regex("(?<=[.!?…:;])\\s+|\\n+"))
            val sb = StringBuilder()
            fun flush() {
                if (sb.isNotBlank()) result.add(sb.toString().trim()); sb.setLength(0)
            }
            for (s0 in sentences) {
                var s = s0.trim()
                if (s.isEmpty()) continue
                while (s.length > maxLen) {          // überlanger Satz
                    flush()
                    var cut = s.lastIndexOf(' ', maxLen)
                    if (cut < maxLen / 2) cut = maxLen
                    result.add(s.substring(0, cut).trim())
                    s = s.substring(cut).trim()
                }
                if (sb.length + s.length + 1 > maxLen) flush()
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
            flush()
            return result
        }
    }
}
