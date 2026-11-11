package de.itdummkopfen.text2video

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Standbild + PCM-Audio -> MP4 (H.264 1280×720 + AAC) nur mit Android-Bordmitteln.
 */
object VideoEncoder {

    private const val TIMEOUT_US = 10_000L
    private const val COLOR_NV12 = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private const val COLOR_I420 = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    private const val COLOR_FLEX = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    fun encode(
        frame: FrameRenderer.Yuv,
        pcm: Wav.Pcm,
        out: File,
        fps: Int = 25,
        cancelled: () -> Boolean,
        progress: (Float) -> Unit,
    ) {
        // 1) Audio komplett nach AAC kodieren (klein, passt in den Speicher)
        val (audioFormat, audio) = encodeAudio(pcm, cancelled)

        // 2) Video kodieren und dabei Audio + Video in die MP4 schreiben
        val durationUs = pcm.durationUs
        val totalFrames = maxOf(1L, (durationUs * fps + 999_999L) / 1_000_000L)
        val w = FrameRenderer.W
        val h = FrameRenderer.H

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val colors = codec.codecInfo
            .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats.toSet()
        val color = when {
            COLOR_NV12 in colors -> COLOR_NV12
            COLOR_I420 in colors -> COLOR_I420
            else -> COLOR_FLEX
        }
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        // Rohdaten einmal im passenden Speicherlayout vorbereiten
        val raw: ByteArray? = when (color) {
            COLOR_NV12 -> ByteArray(w * h * 3 / 2).also { a ->
                System.arraycopy(frame.y, 0, a, 0, w * h)
                var p = w * h
                for (i in frame.u.indices) { a[p++] = frame.u[i]; a[p++] = frame.v[i] }
            }
            COLOR_I420 -> ByteArray(w * h * 3 / 2).also { a ->
                System.arraycopy(frame.y, 0, a, 0, w * h)
                System.arraycopy(frame.u, 0, a, w * h, frame.u.size)
                System.arraycopy(frame.v, 0, a, w * h + frame.u.size, frame.v.size)
            }
            else -> null
        }

        out.delete()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var vTrack = -1
        var aTrack = -1
        var muxing = false
        var aIdx = 0
        val info = MediaCodec.BufferInfo()
        val aInfo = MediaCodec.BufferInfo()

        fun writeAudioUpTo(ptsUs: Long) {
            while (aIdx < audio.size && audio[aIdx].ptsUs <= ptsUs) {
                val s = audio[aIdx++]
                aInfo.set(0, s.data.size, s.ptsUs, s.flags)
                muxer.writeSampleData(aTrack, ByteBuffer.wrap(s.data), aInfo)
            }
        }

        try {
            var frameNo = 0L
            var inputDone = false
            var outputDone = false
            var lastPct = -1
            while (!outputDone) {
                if (cancelled()) throw InterruptedException()
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val pts = frameNo * 1_000_000L / fps
                        if (frameNo >= totalFrames) {
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            if (raw != null) {
                                val buf = codec.getInputBuffer(idx)
                                    ?: throw IOException("Encoder: kein Eingabepuffer")
                                buf.clear()
                                if (buf.capacity() < raw.size) throw IOException("Encoder-Puffer zu klein")
                                buf.put(raw)
                                codec.queueInputBuffer(idx, 0, raw.size, pts, 0)
                            } else {
                                val img = codec.getInputImage(idx)
                                    ?: throw IOException("Encoder: kein Eingabebild")
                                writeImage(img, frame, w, h)
                                codec.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0)
                            }
                            frameNo++
                        }
                    }
                }

                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxing) throw IOException("Videoformat hat sich unerwartet geändert")
                        vTrack = muxer.addTrack(codec.outputFormat)
                        aTrack = muxer.addTrack(audioFormat)
                        muxer.start()
                        muxing = true
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && buf != null) {
                            if (!muxing) throw IOException("Encoder lieferte Daten vor dem Format")
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(vTrack, buf, info)
                            writeAudioUpTo(info.presentationTimeUs)
                            val pct = (info.presentationTimeUs * 100 / maxOf(1L, durationUs)).toInt()
                            if (pct != lastPct) { lastPct = pct; progress(minOf(1f, pct / 100f)) }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (muxing) writeAudioUpTo(Long.MAX_VALUE)
            progress(1f)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (muxing) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
    }

    private fun encodeAudio(pcm: Wav.Pcm, cancelled: () -> Boolean): Pair<MediaFormat, List<Sample>> {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, pcm.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val samples = ArrayList<Sample>()
        var outFormat: MediaFormat? = null
        val data = pcm.data
        val frameBytes = pcm.frameBytes
        var inPos = 0
        var inputDone = false
        var outputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (!outputDone) {
                if (cancelled()) throw InterruptedException()
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx) ?: throw IOException("Audio-Encoder: kein Puffer")
                        buf.clear()
                        val n = minOf(buf.remaining(), data.size - inPos) / frameBytes * frameBytes
                        val pts = inPos.toLong() / frameBytes * 1_000_000L / pcm.sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(data, inPos, n)
                            inPos += n
                            codec.queueInputBuffer(idx, 0, n, pts, 0)
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = codec.outputFormat
                } else if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && buf != null) {
                        val arr = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(arr)
                        samples.add(Sample(arr, info.presentationTimeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME))
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
        return Pair(outFormat ?: throw IOException("Audio-Encoder lieferte kein Format"), samples)
    }

    /** Fallback für Encoder, die nur das flexible YUV-Format anbieten. */
    private fun writeImage(img: Image, f: FrameRenderer.Yuv, w: Int, h: Int) {
        val p = img.planes
        writePlane(p[0], f.y, w, h)
        writePlane(p[1], f.u, w / 2, h / 2)
        writePlane(p[2], f.v, w / 2, h / 2)
    }

    private fun writePlane(plane: Image.Plane, src: ByteArray, w: Int, h: Int) {
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        if (ps == 1) {
            for (row in 0 until h) {
                buf.position(row * rs)
                buf.put(src, row * w, w)
            }
        } else {
            for (row in 0 until h) {
                val base = row * rs
                val s = row * w
                for (col in 0 until w) buf.put(base + col * ps, src[s + col])
            }
        }
    }
}
