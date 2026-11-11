package de.itdummkopfen.text2video

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/** Lädt das Bild und baut daraus das 1280×720-Standbild. */
object FrameRenderer {
    const val W = 1280
    const val H = 720

    enum class Mode { BLUR, BLACK, FILL }

    fun load(cr: ContentResolver, uri: Uri, maxSide: Int = 2560): Bitmap {
        val src = ImageDecoder.createSource(cr, uri)
        // ImageDecoder berücksichtigt die EXIF-Drehung automatisch
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = max(w, h).toFloat() / maxSide
            if (scale > 1f) {
                decoder.setTargetSize(max(1, (w / scale).toInt()), max(1, (h / scale).toInt()))
            }
        }
    }

    fun render(src: Bitmap, mode: Mode): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        when (mode) {
            Mode.FILL -> c.drawBitmap(src, null, rect(src, W, H, cover = true), paint)
            Mode.BLACK -> c.drawBitmap(src, null, rect(src, W, H, cover = false), paint)
            Mode.BLUR -> {
                // Unschärfe durch stufenweises Verkleinern und wieder Vergrößern
                val a = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
                Canvas(a).drawBitmap(src, null, rect(src, 320, 180, cover = true), paint)
                val b = Bitmap.createScaledBitmap(a, 80, 45, true)
                val tiny = Bitmap.createScaledBitmap(b, 32, 18, true)
                val back = Bitmap.createScaledBitmap(tiny, 160, 90, true)
                c.drawBitmap(back, null, RectF(0f, 0f, W.toFloat(), H.toFloat()), paint)
                c.drawColor(Color.argb(80, 0, 0, 0))
                c.drawBitmap(src, null, rect(src, W, H, cover = false), paint)
                a.recycle(); b.recycle(); tiny.recycle(); back.recycle()
            }
        }
        return out
    }

    private fun rect(src: Bitmap, dw: Int, dh: Int, cover: Boolean): RectF {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val s = if (cover) max(dw / sw, dh / sh) else min(dw / sw, dh / sh)
        val w = sw * s
        val h = sh * s
        return RectF((dw - w) / 2f, (dh - h) / 2f, (dw + w) / 2f, (dh + h) / 2f)
    }

    /** YUV 4:2:0 (BT.709, begrenzter Bereich) für den Video-Encoder. */
    class Yuv(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    fun toYuv(bmp: Bitmap): Yuv {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val y = ByteArray(w * h)
        val u = ByteArray(w * h / 4)
        val v = ByteArray(w * h / 4)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            y[i] = (16 + ((47 * r + 157 * g + 16 * b + 128) shr 8)).coerceIn(16, 235).toByte()
        }
        var k = 0
        for (row in 0 until h step 2) {
            for (col in 0 until w step 2) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val p = px[(row + dy) * w + col + dx]
                    r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                }
                r /= 4; g /= 4; b /= 4
                u[k] = (128 + ((-26 * r - 87 * g + 112 * b + 128) shr 8)).coerceIn(16, 240).toByte()
                v[k] = (128 + ((112 * r - 102 * g - 10 * b + 128) shr 8)).coerceIn(16, 240).toByte()
                k++
            }
        }
        return Yuv(y, u, v)
    }
}
