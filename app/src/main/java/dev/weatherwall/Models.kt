package dev.weatherwall

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.File
import java.net.URL
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Downloads two small ONNX models once, then builds a "scene" (photo + foreground cut-out + depth haze). */
object Models {
    // Depth Anything V2 Small (Apache-2.0), int8, ~99 MB
    private const val DEPTH_URL = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx"
    // U2-Net-P (Apache-2.0), ~4.6 MB, md5 8e83ca70e441ab06c318d82300c84806
    private const val SEG_URL = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx"

    private fun depthFile(c: Context) = File(c.filesDir, "depth32.onnx")
    private fun segFile(c: Context) = File(c.filesDir, "u2netp.onnx")
    fun ready(c: Context) = depthFile(c).length() > 1_000_000 && segFile(c).length() > 1_000_000

    fun download(c: Context, log: (String) -> Unit) {
        for ((url, f) in listOf(SEG_URL to segFile(c), DEPTH_URL to depthFile(c))) {
            if (f.length() > 1_000_000) continue
            log("Downloading ${f.name}…")
            val tmp = File(f.path + ".part")
            val con = URL(url).openConnection().apply { connectTimeout = 15000; readTimeout = 30000 }
            con.getInputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(f)
        }
    }

    fun build(c: Context, src: Bitmap, log: (String) -> Unit) {
        val photo = fit(src, 2048)
        val w = photo.width
        val h = photo.height
        val env = OrtEnvironment.getEnvironment()
        log("Segmentation…")
        val (m, ms) = infer(env, segFile(c), photo, 320)
        log("Depth…")
        val (d, ds) = infer(env, depthFile(c), photo, 518)

        // foreground = photo * soft mask
        val mn = norm(m)
        val maskPx = IntArray(mn.size) { i ->
            val t = ((mn[i] - 0.3f) / 0.4f).coerceIn(0f, 1f)
            (((t * t * (3 - 2 * t)) * 255).toInt() shl 24) or 0xFFFFFF
        }
        val maskBig = Bitmap.createScaledBitmap(Bitmap.createBitmap(maskPx, ms, ms, Bitmap.Config.ARGB_8888), w, h, true)
        val fg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(fg).apply {
            drawBitmap(photo, 0f, 0f, null)
            drawBitmap(maskBig, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        }

        // haze = far pixels more opaque (Depth Anything: larger value = closer)
        val dn = norm(d)
        val hazePx = IntArray(dn.size) { i -> (((1f - dn[i]).pow(1.5f) * 200).toInt() shl 24) or 0xC8D2DC }
        val haze = Bitmap.createScaledBitmap(Bitmap.createBitmap(hazePx, ds, ds, Bitmap.Config.ARGB_8888), w, h, true)

        val dir = File(c.filesDir, "scene").apply { mkdirs() }
        save(photo, File(dir, "photo.jpg"), true)
        save(fg, File(dir, "fg.png"), false)
        save(haze, File(dir, "haze.png"), false)
        c.getSharedPreferences("ww", 0).edit().putLong("scene", System.currentTimeMillis()).apply()
    }

    private fun save(b: Bitmap, f: File, jpg: Boolean) {
        f.outputStream().use { b.compress(if (jpg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, it) }
    }

    private fun fit(b: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / max(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }

    private fun norm(a: FloatArray): FloatArray {
        val mn = a.min()
        val r = (a.max() - mn).let { if (it > 0f) it else 1f }
        return FloatArray(a.size) { (a[it] - mn) / r }
    }

    /** Runs a square-input ImageNet-normalised model, returns (flat output, output side). */
    private fun infer(env: OrtEnvironment, model: File, bmp: Bitmap, side: Int): Pair<FloatArray, Int> {
        val px = IntArray(side * side)
        Bitmap.createScaledBitmap(bmp, side, side, true).getPixels(px, 0, side, 0, 0, side, side)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val buf = FloatBuffer.allocate(3 * side * side)
        for (ch in 0..2) for (p in px) buf.put((((p shr (16 - 8 * ch)) and 255) / 255f - mean[ch]) / std[ch])
        buf.rewind()
        val shape = longArrayOf(1, 3, side.toLong(), side.toLong())
        return OrtSession.SessionOptions().use { o ->
            o.setIntraOpNumThreads(4)
            env.createSession(model.path, o).use { s ->
                OnnxTensor.createTensor(env, buf, shape).use { t ->
                    s.run(mapOf(s.inputNames.first() to t)).use { r ->
                        val fb = (r[0] as OnnxTensor).floatBuffer
                        val a = FloatArray(fb.remaining())
                        fb.get(a)
                        a to sqrt(a.size.toDouble()).toInt()
                    }
                }
            }
        }
    }
}
