package dev.weatherwall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Downloads LiteRT (.tflite) models once, then builds a "scene":
 * photo + foreground cut-out + depth haze + (optional) sky mask.
 * Every model runs on the GPU in explicit FP32; on failure or a broken output (NaN / constant) it re-runs on the CPU.
 */
object Models {
    // Depth Anything V2 Small, dynamic INT8 weights, FP32 interface, ~27.7 MB. Input [1,3,518,686] NCHW, output [1,518,686].
    private const val DEPTH_URL =
        "https://huggingface.co/litert-community/depth-anything-v2-small/resolve/main/tflite/depth_anything_v2_small_wi8_afp32.tflite"
    private const val DEPTH_W = 686
    private const val DEPTH_H = 518

    // u2netp.tflite and skywater.tflite are produced by the "models" GitHub Actions workflow
    // and published as assets of the release tagged "models" in this repo (BuildConfig.MODELS_REPO is set by CI).
    private fun releaseUrl(name: String) = "https://github.com/${BuildConfig.MODELS_REPO}/releases/download/models/$name"
    private const val SEG_SIDE = 320   // U2-Net-P input
    private const val SKY_SIDE = 384   // SkyWater-Seg input

    private const val MIN_BYTES = 1_000_000L

    private fun depthFile(c: Context) = File(c.filesDir, "depth_wi8.tflite")
    private fun segFile(c: Context) = File(c.filesDir, "u2netp.tflite")
    private fun skyFile(c: Context) = File(c.filesDir, "skywater.tflite")

    /** Depth + cut-out are required; the sky model is optional and only gates the sky mask. */
    fun ready(c: Context) = depthFile(c).length() > MIN_BYTES && segFile(c).length() > MIN_BYTES
    fun skyReady(c: Context) = skyFile(c).length() > MIN_BYTES

    fun download(c: Context, log: (String) -> Unit) {
        // remove the old ONNX files of the previous version
        File(c.filesDir, "depth32.onnx").delete(); File(c.filesDir, "u2netp.onnx").delete()
        val repoSet = BuildConfig.MODELS_REPO.isNotEmpty()
        val jobs = mutableListOf(DEPTH_URL to depthFile(c))
        if (repoSet) { jobs += releaseUrl("u2netp.tflite") to segFile(c); jobs += releaseUrl("skywater.tflite") to skyFile(c) }
        else log("MODELS_REPO is empty (local build): u2netp / sky are not downloaded")
        for ((url, f) in jobs) {
            if (f.length() > MIN_BYTES) continue
            log("Downloading ${f.name}…")
            try { fetch(url, f) } catch (e: Exception) {
                if (f == skyFile(c)) log("Sky model unavailable (${e.message}); continuing without sky") else throw e
            }
        }
    }

    private fun fetch(url: String, f: File) {
        val tmp = File(f.path + ".part")
        val con = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000 }
        try {
            if (con.responseCode !in 200..299) error("HTTP ${con.responseCode} for $url")
            con.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        } finally { con.disconnect() }
        if (tmp.length() < MIN_BYTES) { tmp.delete(); error("file too small: $url") }
        if (!tmp.renameTo(f)) error("rename failed: ${f.name}")
    }

    fun build(c: Context, src: Bitmap, log: (String) -> Unit) {
        val photo = fit(src, 2048)
        val w = photo.width
        val h = photo.height
        val env = Environment.create()
        try {
            log("Segmentation…")
            val m = run(env, segFile(c), prep(photo, SEG_SIDE, SEG_SIDE), log)
            log("Depth…")
            val d = run(env, depthFile(c), prep(photo, DEPTH_W, DEPTH_H), log)
            require(m.size == SEG_SIDE * SEG_SIDE) { "u2netp output ${m.size}, expected ${SEG_SIDE * SEG_SIDE}" }
            require(d.size == DEPTH_W * DEPTH_H) { "depth output ${d.size}, expected ${DEPTH_W * DEPTH_H}" }

            // foreground = photo * soft mask
            val mn = norm(m)
            val maskPx = IntArray(mn.size) { i ->
                val t = ((mn[i] - 0.3f) / 0.4f).coerceIn(0f, 1f)
                (((t * t * (3 - 2 * t)) * 255).toInt() shl 24) or 0xFFFFFF
            }
            val maskBig = Bitmap.createScaledBitmap(Bitmap.createBitmap(maskPx, SEG_SIDE, SEG_SIDE, Bitmap.Config.ARGB_8888), w, h, true)
            val fg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(fg).apply {
                drawBitmap(photo, 0f, 0f, null)
                drawBitmap(maskBig, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
            }

            // haze = far pixels more opaque (Depth Anything: larger value = closer)
            val dn = norm(d)
            val hazePx = IntArray(dn.size) { i -> (((1f - dn[i]).pow(1.5f) * 200).toInt() shl 24) or 0xC8D2DC }
            val haze = Bitmap.createScaledBitmap(Bitmap.createBitmap(hazePx, DEPTH_W, DEPTH_H, Bitmap.Config.ARGB_8888), w, h, true)

            val dir = File(c.filesDir, "scene").apply { mkdirs() }
            save(photo, File(dir, "photo.jpg"), true)
            save(fg, File(dir, "fg.png"), false)
            save(haze, File(dir, "haze.png"), false)

            // sky = alpha mask (optional). The converted model outputs one channel: sky probability in [0,1].
            val skyOut = File(dir, "sky.png")
            skyOut.delete()
            if (skyReady(c)) {
                try {
                    log("Sky…")
                    val s = run(env, skyFile(c), prep(photo, SKY_SIDE, SKY_SIDE), log)
                    val side = sqrt(s.size.toDouble()).toInt()
                    require(side * side == s.size && side >= 64) { "sky output ${s.size} is not a square map" }
                    val px = IntArray(s.size) { i -> ((s[i].coerceIn(0f, 1f) * 255).toInt() shl 24) or 0xFFFFFF }
                    val big = Bitmap.createScaledBitmap(Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888), w, h, true)
                    save(big, skyOut, false)
                } catch (e: Exception) { log("Sky skipped: ${e.message}") }
            }
            c.getSharedPreferences("ww", 0).edit().putLong("scene", System.currentTimeMillis()).apply()
        } finally { env.close() }
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

    /** RGB -> flat NCHW float array, ImageNet-normalised, stretched to w x h (no aspect-ratio preservation). */
    private fun prep(bmp: Bitmap, w: Int, h: Int): FloatArray {
        val px = IntArray(w * h)
        Bitmap.createScaledBitmap(bmp, w, h, true).getPixels(px, 0, w, 0, 0, w, h)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val plane = w * h
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = px[i]
            out[i] = (((p shr 16) and 255) / 255f - mean[0]) / std[0]
            out[plane + i] = (((p shr 8) and 255) / 255f - mean[1]) / std[1]
            out[2 * plane + i] = ((p and 255) / 255f - mean[2]) / std[2]
        }
        return out
    }

    /** GPU (explicit FP32) first; CPU if it throws or the output is unusable. */
    private fun run(env: Environment, model: File, input: FloatArray, log: (String) -> Unit): FloatArray {
        try {
            val o = runOn(env, model, input, gpu = true)
            if (sane(o)) { log("${model.name}: GPU"); return o }
            log("${model.name}: GPU output unusable (NaN/constant), retrying on CPU")
        } catch (e: Throwable) {
            log("${model.name}: GPU failed (${e.message}), retrying on CPU")
        }
        val o = runOn(env, model, input, gpu = false)
        check(sane(o)) { "${model.name}: CPU output unusable" }
        log("${model.name}: CPU")
        return o
    }

    private fun runOn(env: Environment, model: File, input: FloatArray, gpu: Boolean): FloatArray {
        val opts = if (gpu) CompiledModel.Options(Accelerator.GPU).apply {
            gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
        } else CompiledModel.Options(Accelerator.CPU)
        return CompiledModel.create(model.absolutePath, opts, env).use { m ->
            val ins = m.createInputBuffers()
            val outs = m.createOutputBuffers()
            try {
                ins[0].writeFloat(input)
                m.run(ins, outs)
                outs[0].readFloat()
            } finally {
                ins.forEach { it.close() }
                outs.forEach { it.close() }
            }
        }
    }

    private fun sane(a: FloatArray): Boolean {
        var mn = Float.POSITIVE_INFINITY; var mx = Float.NEGATIVE_INFINITY
        for (v in a) { if (v.isNaN() || v.isInfinite()) return false; if (v < mn) mn = v; if (v > mx) mx = v }
        return mx - mn > 1e-6f
    }
}
