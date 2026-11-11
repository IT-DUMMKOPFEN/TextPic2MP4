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

        // Flexibles YUV-Format: der Encoder gibt über getInputImage() sein echtes
        // Speicherlayout (Zeilenabstand, Pixelabstand, Lage von U/V) vor.
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FLEX)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val writer = FrameWriter(frame, w, h)

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
                            val img = codec.getInputImage(idx)
                                ?: throw IOException("Encoder: kein Eingabebild")
                            writer.write(img)
                            codec.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0)
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

    /**
     * Schreibt das Standbild in das Eingabebild des Encoders – passend zu dessen
     * Layout. Wiederkehrende Layouts werden einmal vorbereitet und dann pro
     * Frame nur noch blockweise kopiert.
     */
    private class FrameWriter(private val f: FrameRenderer.Yuv, private val w: Int, private val h: Int) {
        private val cw = w / 2
        private val ch = h / 2
        private var yRows: ByteArray? = null     // Y mit Zeilenabstand des Encoders
        private var uvRows: ByteArray? = null    // U/V verschränkt (NV12 oder NV21)
        private var layoutKey = ""

        fun write(img: Image) {
            val p = img.planes
            val y = p[0]; val u = p[1]; val v = p[2]
            val key = "${y.rowStride}/${y.pixelStride}/${u.rowStride}/${u.pixelStride}/${v.rowStride}/${v.pixelStride}"
            if (key != layoutKey) { yRows = null; uvRows = null; layoutKey = key }

            writeLuma(y)

            if (u.pixelStride == 2 && v.pixelStride == 2 && u.rowStride == v.rowStride) {
                when (chromaOrder(u.buffer, v.buffer)) {
                    1 -> { writeInterleaved(u.buffer, v.buffer, u.rowStride, f.u, f.v); return }   // NV12: U zuerst
                    2 -> { writeInterleaved(v.buffer, u.buffer, v.rowStride, f.v, f.u); return }   // NV21: V zuerst
                }
            }
            writePlaneGeneric(u, f.u, cw, ch)
            writePlaneGeneric(v, f.v, cw, ch)
        }

        private fun writeLuma(plane: Image.Plane) {
            val buf = plane.buffer
            val rs = plane.rowStride
            if (plane.pixelStride != 1) { writePlaneGeneric(plane, f.y, w, h); return }
            if (rs == w) {
                buf.position(0)
                buf.put(f.y, 0, minOf(f.y.size, buf.remaining()))
                return
            }
            val rows = yRows ?: ByteArray(rs * h).also { a ->
                for (r in 0 until h) System.arraycopy(f.y, r * w, a, r * rs, w)
                yRows = a
            }
            buf.position(0)
            buf.put(rows, 0, minOf(rows.size, buf.remaining()))
        }

        /** 1 = V liegt direkt hinter U (NV12), 2 = U liegt direkt hinter V (NV21), 0 = getrennt. */
        private fun chromaOrder(ub: ByteBuffer, vb: ByteBuffer): Int {
            if (ub.limit() < 2 || vb.limit() < 2) return 0
            val u1 = ub.get(1); val v0 = vb.get(0)
            vb.put(0, 0x5A.toByte())
            val nv12 = ub.get(1) == 0x5A.toByte()
            vb.put(0, 0x00.toByte())
            val nv12b = ub.get(1) == 0x00.toByte()
            ub.put(1, u1); vb.put(0, v0)
            if (nv12 && nv12b) return 1
            val v1 = vb.get(1); val u0 = ub.get(0)
            ub.put(0, 0x5A.toByte())
            val nv21 = vb.get(1) == 0x5A.toByte()
            ub.put(0, 0x00.toByte())
            val nv21b = vb.get(1) == 0x00.toByte()
            vb.put(1, v1); ub.put(0, u0)
            return if (nv21 && nv21b) 2 else 0
        }

        private fun writeInterleaved(buf: ByteBuffer, other: ByteBuffer, rs: Int, first: ByteArray, second: ByteArray) {
            val rows = uvRows ?: ByteArray(rs * ch).also { a ->
                for (r in 0 until ch) {
                    var o = r * rs
                    val s = r * cw
                    for (c in 0 until cw) { a[o++] = first[s + c]; a[o++] = second[s + c] }
                }
                uvRows = a
            }
            buf.position(0)
            buf.put(rows, 0, minOf(rows.size, buf.remaining()))
            // Der erste Puffer endet vor dem allerletzten Byte der zweiten Ebene –
            // dieses über deren eigenen Puffer setzen.
            val last = (ch - 1) * rs + (cw - 1) * 2
            if (last < other.limit()) other.put(last, second[second.size - 1])
        }

        private fun writePlaneGeneric(plane: Image.Plane, src: ByteArray, pw: Int, ph: Int) {
            val buf = plane.buffer
            val rs = plane.rowStride
            val ps = plane.pixelStride
            for (row in 0 until ph) {
                val base = row * rs
                val s = row * pw
                if (ps == 1) {
                    buf.position(base)
                    buf.put(src, s, pw)
                } else {
                    for (col in 0 until pw) buf.put(base + col * ps, src[s + col])
                }
            }
        }
    }
}
