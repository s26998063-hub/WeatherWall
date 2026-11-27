package dev.weatherwall

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

private object Noise {
    /** Tileable fractal value noise: white bitmap, alpha carries the pattern. */
    fun make(size: Int, seed: Int, lo: Float, hi: Float): Bitmap {
        val rnd = Random(seed)
        val grids = Array(4) { o -> val n = 4 shl o; FloatArray(n * n) { rnd.nextFloat() } }
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            var v = 0f; var amp = 0.5f; var tot = 0f
            for (o in 0 until 4) {
                val n = 4 shl o
                val fx = x.toFloat() / size * n; val fy = y.toFloat() / size * n
                val ix = floor(fx).toInt(); val iy = floor(fy).toInt()
                val tx = fx - ix; val ty = fy - iy
                val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
                val g = grids[o]
                val a = g[(iy % n) * n + ix % n]; val b = g[(iy % n) * n + (ix + 1) % n]
                val cc = g[((iy + 1) % n) * n + ix % n]; val d = g[((iy + 1) % n) * n + (ix + 1) % n]
                val top = a + (b - a) * sx; val bot = cc + (d - cc) * sx
                v += amp * (top + (bot - top) * sy)
                tot += amp; amp *= 0.5f
            }
            v /= tot
            val al = ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            px[y * size + x] = ((al * 255).toInt() shl 24) or 0xFFFFFF
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}

private fun softSprite(sz: Int): Bitmap {
    val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(sz / 2f, sz / 2f, sz / 2f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x80FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    Canvas(b).drawCircle(sz / 2f, sz / 2f, sz / 2f, p)
    return b
}

/** Irregular fluffy flake: a cluster of small soft blobs, not a perfect disc. */
private fun clumpSprite(sz: Int, seed: Int, blur: Boolean): Bitmap {
    val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
    val cv = Canvas(b)
    val r = Random(seed)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    if (blur) p.alpha = 170
    val cx = sz / 2f
    val parts = 7 + r.nextInt(6)
    for (k in 0 until parts) {
        val a = r.nextFloat() * 6.2832f
        val d = r.nextFloat() * sz * 0.22f
        val px = cx + cos(a) * d * 1.3f
        val py = cx + sin(a) * d
        val rad = sz * (0.07f + r.nextFloat() * 0.12f) * (if (blur) 1.8f else 1f)
        val inner = if (blur) 0.15f else 0.55f
        p.shader = RadialGradient(px, py, rad,
            intArrayOf(0xFFFFFFFF.toInt(), 0xF0FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, inner, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(px, py, rad, p)
    }
    return b
}

/** Out-of-focus lens disc: faint body, brighter rim. */
private fun bokehSprite(sz: Int): Bitmap {
    val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(sz / 2f, sz / 2f, sz / 2f,
            intArrayOf(0x30FFFFFF, 0x38FFFFFF, 0xA0FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.78f, 0.92f, 1f), Shader.TileMode.CLAMP)
    }
    Canvas(b).drawCircle(sz / 2f, sz / 2f, sz / 2f, p)
    return b
}

private fun hash01(x: Int, y: Int): Float {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 65535f
}

private fun crispSprite(sz: Int): Bitmap {
    val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(sz / 2f, sz / 2f, sz / 2f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xF2FFFFFF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }
    Canvas(b).drawCircle(sz / 2f, sz / 2f, sz / 2f, p)
    return b
}

/** Live wallpaper: photo + cut-out foreground + depth haze + animated rain / snow / fog / clouds / sun. */
class WallService : WallpaperService() {
    override fun onCreateEngine(): Engine = E()

    inner class E : Engine(), SensorEventListener, Choreographer.FrameCallback {
        private val prefs = getSharedPreferences("ww", 0)
        private var sw = 0; private var sh = 0
        private var photo: Bitmap? = null
        private var fg: Bitmap? = null
        private var haze: Bitmap? = null
        private var stamp = -1L
        private var tx = 0f; private var ty = 0f; private var gx = 0f; private var gy = 0f
        private var bx = 0f; private var bz = 0f
        private var wx = 1            // 1 clear, 2 clouds, 3 rain, 4 snow, 5 fog
        private var lastFetch = 0L
        private var vis = false
        private var last = 0L
        private var time = 0f
        private var cf: ColorMatrixColorFilter? = null
        private var cfAt = -10f
        private var day = 1f
        private val rnd = Random(7)
        private val sharpSet = Array(3) { clumpSprite(64, 200 + it, false) }
        private val blurSet = Array(3) { clumpSprite(64, 200 + it, true) }
        private val groundP = Paint()
        private val frostTex by lazy { Noise.make(128, 21, .36f, .68f) }
        private val frostSh by lazy { BitmapShader(frostTex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        private var cap: Bitmap? = null
        private val fadeP = Paint()
        private val fogTex by lazy { Noise.make(128, 3, .30f, .75f) }
        private val cloudTex by lazy { Noise.make(128, 9, .42f, .62f) }
        private val fogA by lazy { BitmapShader(fogTex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        private val fogB by lazy { BitmapShader(fogTex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        private val fogC by lazy { BitmapShader(fogTex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        private val cloudA by lazy { BitmapShader(cloudTex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        private val cloudB by lazy { BitmapShader(cloudTex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
        private val m = Matrix()
        private val rf = RectF()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val hazePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val nPaint = Paint()
        private val glowP = Paint()
        private val spriteP = Paint(Paint.FILTER_BITMAP_FLAG)
        private val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val n = 320
        private val ax = FloatArray(n) { rnd.nextFloat() }
        private val ay = FloatArray(n) { rnd.nextFloat() }
        private val az = FloatArray(n) { rnd.nextFloat() }
        private val ph = FloatArray(n) { rnd.nextFloat() * 6.28f }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            sw = width; sh = height; stamp = -1L
            glowP.shader = RadialGradient(sw * 0.15f, -sh * 0.02f, sw * 1.2f, 0xCCFFE9B8.toInt(), 0x00FFE9B8, Shader.TileMode.CLAMP)
            fadeP.shader = LinearGradient(0f, 0f, 0f, sh * 0.56f, 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            fadeP.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            groundP.shader = LinearGradient(0f, sh * 0.5f, 0f, sh.toFloat(), 0x00FFFFFF, 0xD0FFFFFF.toInt(), Shader.TileMode.CLAMP)
            groundP.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            if (vis) loadScene()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            vis = visible
            val sm = getSystemService(SENSOR_SERVICE) as SensorManager
            if (visible) {
                loadScene(); weather()
                sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
                last = 0L
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                sm.unregisterListener(this)
                Choreographer.getInstance().removeFrameCallback(this)
            }
        }

        override fun onDestroy() {
            vis = false
            Choreographer.getInstance().removeFrameCallback(this)
            super.onDestroy()
        }

        override fun onSensorChanged(e: SensorEvent) {
            val rx = e.values[0]; val rz = e.values[2]
            bx += (rx - bx) * 0.003f; bz += (rz - bz) * 0.003f      // slow baseline = resting pose
            gx = (-(rx - bx) / 3f).coerceIn(-1f, 1f)
            gy = ((rz - bz) / 3f).coerceIn(-1f, 1f)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}

        private fun loadScene() {
            val st = prefs.getLong("scene", 0L)
            if (sw == 0 || (st == stamp && photo != null)) return
            val d = File(filesDir, "scene")
            val p = BitmapFactory.decodeFile(File(d, "photo.jpg").path) ?: return
            val s = max(sw.toFloat() / p.width, sh.toFloat() / p.height) * 1.14f
            val tw = (p.width * s).toInt(); val th = (p.height * s).toInt()
            photo = Bitmap.createScaledBitmap(p, tw, th, true)
            fg = BitmapFactory.decodeFile(File(d, "fg.png").path)?.let { Bitmap.createScaledBitmap(it, tw, th, true) }
            haze = BitmapFactory.decodeFile(File(d, "haze.png").path)?.let { Bitmap.createScaledBitmap(it, tw, th, true) }
            cap = fg?.let { makeCap(it) }
            stamp = st
        }

        /** Snow resting on upward-facing edges of the cut-out: per-column distance below the nearest transparent pixel. */
        private fun makeCap(f: Bitmap): Bitmap {
            val w = f.width; val h = f.height
            val src = IntArray(w * h); f.getPixels(src, 0, w, 0, 0, w, h)
            val out = IntArray(w * h)
            val k0 = max(6f, w * 0.010f)
            val r = Random(11)
            val g = FloatArray(w / 16 + 3) { 0.55f + r.nextFloat() * 0.9f }
            val dist = IntArray(w) { 1 shl 20 }
            for (y in 0 until h) for (x in 0 until w) {
                val a = src[y * w + x] ushr 24
                if (a < 110) { dist[x] = 0; continue }
                val d = dist[x]; dist[x] = d + 1
                val gx = x / 16f; val i0 = gx.toInt(); val t = gx - i0
                val kk = k0 * (g[i0] + (g[i0 + 1] - g[i0]) * t)
                if (d < kk) {
                    val q = 1f - d / kk
                    val al = (Math.pow(q.toDouble(), 0.6).toFloat() * (a / 255f) * 235f).toInt().coerceIn(0, 255)
                    val nz = 0.6f + 0.4f * hash01(x shr 1, y shr 1)
                    out[y * w + x] = ((al * nz).toInt().coerceIn(0, 255) shl 24) or 0xEAF0FA
                } else if (d < kk * 2.6f) {
                    val q2 = 1f - (d - kk) / (kk * 1.6f)
                    if (hash01(x shr 1, y shr 1) < 0.16f * q2) out[y * w + x] = (210 shl 24) or 0xEAF0FA
                }
            }
            return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }

        private fun weather() {
            val mode = prefs.getInt("mode", 0)
            if (mode != 0) { wx = mode; return }
            if (System.currentTimeMillis() - lastFetch < 30 * 60_000L) return
            lastFetch = System.currentTimeMillis()
            val lat = prefs.getFloat("lat", 999f); val lon = prefs.getFloat("lon", 999f)
            if (lat > 90f) { wx = 1; return }
            Thread {
                try {
                    val j = JSONObject(URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=weather_code").readText())
                    wx = when (j.getJSONObject("current").getInt("weather_code")) {
                        0, 1 -> 1
                        2, 3 -> 2
                        45, 48 -> 5
                        in 51..67, in 80..82, in 95..99 -> 3
                        in 71..77, 85, 86 -> 4
                        else -> 1
                    }
                } catch (e: Exception) { }
            }.start()
        }

        private val keys = arrayOf(
            floatArrayOf(0f, .30f, .38f, .62f), floatArrayOf(5f, .42f, .46f, .70f), floatArrayOf(7f, 1f, .80f, .62f),
            floatArrayOf(10f, 1f, 1f, 1f), floatArrayOf(16f, 1f, 1f, 1f), floatArrayOf(18.5f, 1f, .66f, .45f),
            floatArrayOf(20.5f, .52f, .46f, .68f), floatArrayOf(24f, .30f, .38f, .62f)
        )

        private fun filter(): ColorMatrixColorFilter {
            val c = Calendar.getInstance()
            val t = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f
            var rgb = floatArrayOf(1f, 1f, 1f)
            for (i in 1 until keys.size) if (t <= keys[i][0]) {
                val a = keys[i - 1]; val b = keys[i]; val f = (t - a[0]) / (b[0] - a[0])
                rgb = FloatArray(3) { a[it + 1] + (b[it + 1] - a[it + 1]) * f }
                break
            }
            day = (((rgb[0] + rgb[1] + rgb[2]) / 3f - .6f) / .4f).coerceIn(0f, 1f)
            val d = when (wx) { 2 -> .93f; 3 -> .85f; 4 -> .95f; 5 -> .9f; else -> 1f }
            val k = when (wx) { 4 -> .94f; 5 -> .82f; else -> 1f }
            val off = when (wx) { 2 -> 8f; 4 -> 22f; 5 -> 30f; else -> 0f }
            val cr = if (wx == 4) .95f else 1f; val cb = if (wx == 4) 1.06f else 1f
            val off2 = off * (0.35f + 0.65f * day)
            val cm = ColorMatrix(floatArrayOf(
                rgb[0] * d * k * cr, 0f, 0f, 0f, off2,
                0f, rgb[1] * d * k, 0f, 0f, off2,
                0f, 0f, rgb[2] * d * k * cb, 0f, off2,
                0f, 0f, 0f, 1f, 0f))
            val sat = ColorMatrix().apply { setSaturation(when (wx) { 2 -> .65f; 3 -> .55f; 4 -> .6f; 5 -> .5f; else -> 1f }) }
            cm.postConcat(sat)
            return ColorMatrixColorFilter(cm)
        }

        override fun doFrame(now: Long) {
            if (!vis) return
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
            last = now; time += dt
            step(dt)
            draw()
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun step(dt: Float) {
            tx += (gx - tx) * minOf(1f, dt * 8f); ty += (gy - ty) * minOf(1f, dt * 8f)
            if (cf == null || time - cfAt > 1f) { weather(); cf = filter(); cfAt = time }
            val w = sw.toFloat(); val h = sh.toFloat()
            if (w == 0f) return
            val cnt = if (wx == 3) 140 else if (wx == 4) n else 0
            for (i in 0 until cnt) {
                val z = az[i]
                if (wx == 3) {
                    val sp = 900f + z * 1900f
                    ay[i] += sp * dt / h; ax[i] += sp * 0.10f * dt / w
                    if (ay[i] > 1.05f) { ay[i] = -0.12f; ax[i] = rnd.nextFloat() * 1.15f - 0.1f }
                    if (ax[i] > 1.15f) ax[i] -= 1.25f
                } else {
                    ay[i] += (45f + z * 150f) * dt / h
                    ax[i] += (sin(time * (0.5f + z * 0.5f) + ph[i]) * (10f + z * 28f) + 8f) * dt / w
                    if (ay[i] > 1.06f) { ay[i] = -0.06f; ax[i] = rnd.nextFloat() }
                    if (ax[i] > 1.08f) ax[i] -= 1.16f
                }
            }
        }

        private fun draw() {
            val s = surfaceHolder
            val c: Canvas = (try { s.lockHardwareCanvas() } catch (e: Exception) { s.lockCanvas() }) ?: return
            try { render(c) } finally { s.unlockCanvasAndPost(c) }
        }

        private fun layer(c: Canvas, b: Bitmap, x: Float, y: Float, sc: Float, pt: Paint) {
            c.save(); c.translate(x, y); c.scale(sc, sc); c.drawBitmap(b, 0f, 0f, pt); c.restore()
        }

        private fun place(s: BitmapShader, scale: Float, vx: Float, vy: Float) {
            m.reset(); m.postScale(scale, scale)
            m.postTranslate((time * vx) % (128f * scale), (time * vy) % (128f * scale))
            s.setLocalMatrix(m)
        }

        private fun strip(c: Canvas, s: BitmapShader, a: Int, y0: Float, y1: Float, w: Float) {
            nPaint.shader = s; nPaint.alpha = a.coerceIn(0, 255)
            c.drawRect(0f, y0, w, y1, nPaint)
        }

        private fun clouds(c: Canvas, w: Float, h: Float) {
            val top = if (wx == 2) 150 else 130
            val bottom = h * 0.56f
            place(cloudA, 7f, 14f, 0f); place(cloudB, 11f, 7f, 2f)
            val id = c.saveLayer(0f, 0f, w, bottom, null)
            nPaint.shader = cloudA; nPaint.alpha = top
            c.drawRect(0f, 0f, w, bottom, nPaint)
            nPaint.shader = cloudB; nPaint.alpha = top / 2
            c.drawRect(0f, 0f, w, bottom, nPaint)
            c.drawRect(0f, 0f, w, bottom, fadeP)
            c.restoreToCount(id)
        }

        private fun particles(c: Canvas, w: Float, h: Float, near: Boolean) {
            if (wx != 3 && wx != 4) return
            val cnt = if (wx == 3) 140 else n
            val ds = w / 1080f
            for (i in 0 until cnt) {
                val z = az[i]
                if ((z >= .5f) != near) continue
                val x = ax[i] * w + tx * (10f + z * 22f); val y = ay[i] * h
                if (wx == 3) {
                    val len = 26f + z * 110f
                    pp.strokeWidth = 1.2f + z * 2.6f
                    pp.color = Color.argb((50 + z * 110).toInt(), 205, 220, 240)
                    c.drawLine(x, y, x - len * 0.10f, y - len, pp)
                } else {
                    val i4 = i % 3; val i3 = i % 3
                    when {
                        z < .5f -> {
                            val sz = ds * (6f + z * 14f)
                            spriteP.alpha = (150 + z * 90).toInt()
                            flakeDraw(c, sharpSet[i4], x, y, sz, sz, ph[i] * 57.3f + time * (10f + ph[i] * 5f))
                        }
                        z < .75f -> {
                            val sz = ds * (13f + (z - .5f) * 36f)
                            spriteP.alpha = 215
                            flakeDraw(c, sharpSet[i4], x, y, sz, sz * 1.15f, ph[i] * 57.3f + time * (8f + ph[i] * 4f))
                        }
                        else -> {
                            val sz = ds * (24f + (z - .75f) * 88f)
                            spriteP.alpha = (130 - (z - .75f) * 150).toInt()
                            flakeDraw(c, blurSet[i3], x, y, sz, sz * 1.5f, sin(time * .7f + ph[i]) * 12f)
                        }
                    }
                }
            }
        }

        private fun flakeDraw(c: Canvas, bmp: Bitmap, x: Float, y: Float, wd: Float, ht: Float, deg: Float) {
            c.save(); c.translate(x, y); c.rotate(deg)
            rf.set(-wd / 2, -ht / 2, wd / 2, ht / 2)
            c.drawBitmap(bmp, null, rf, spriteP)
            c.restore()
        }

        /** Frosted ground: mottled white that thickens toward the bottom. */
        private fun frost(c: Canvas, w: Float, h: Float) {
            val y0 = h * 0.5f
            place(frostSh, 5f, 0f, 0f)
            val id = c.saveLayer(0f, y0, w, h, null)
            nPaint.shader = frostSh; nPaint.alpha = 255
            c.drawRect(0f, y0, w, h, nPaint)
            c.drawRect(0f, y0, w, h, groundP)
            c.restoreToCount(id)
        }

        private fun render(c: Canvas) {
            val w = c.width.toFloat(); val h = c.height.toFloat()
            c.drawColor(Color.BLACK)
            val p = photo
            if (p == null) {
                pp.color = Color.WHITE; pp.textSize = 42f; pp.strokeWidth = 1f
                c.drawText("WeatherWall: build a scene in the app first", 40f, h / 2, pp); return
            }
            paint.colorFilter = cf; hazePaint.colorFilter = cf; nPaint.colorFilter = cf; spriteP.colorFilter = cf
            
            val br = 1f + 0.006f * sin(time * 0.25f)
            val sway = sin(time * 0.8f) * (when (wx) { 3 -> 6f; 4 -> 4f; else -> 2f })
            val bx0 = (w - p.width * br) / 2 + tx * 8f + sin(time * 0.11f) * 10f
            val by0 = (h - p.height * br) / 2 + ty * 8f
            layer(c, p, bx0, by0, br, paint)
            val ha = when (wx) { 5 -> 235; 2 -> 90; 3 -> 80; 4 -> 70; else -> 0 }
            haze?.let { if (ha > 0) { hazePaint.alpha = ha; layer(c, it, bx0, by0, br, hazePaint) } }
            if (wx == 5) {
                place(fogA, 9f, 18f, 0f); place(fogB, 5f, 34f, 4f)
                strip(c, fogA, 120, 0f, h, w); strip(c, fogB, 90, 0f, h, w)
            }
            if (wx == 4) frost(c, w, h)
            if (wx == 2 || wx == 3 || wx == 4) clouds(c, w, h)
            particles(c, w, h, false)
            val f = fg
            val fx = if (f != null) (w - f.width * br) / 2 + tx * 20f + sway else 0f
            val fy = if (f != null) (h - f.height * br) / 2 + ty * 20f else 0f
            f?.let { layer(c, it, fx, fy, br, paint) }
            if (wx == 4) cap?.let { layer(c, it, fx, fy, br, paint) }
            if (wx == 5) { place(fogC, 7f, 52f, 6f); strip(c, fogC, 70, 0f, h, w) }
            if (wx == 1) {
                glowP.alpha = (255 * (0.6f + 0.08f * sin(time * 0.3f)) * day).toInt().coerceIn(0, 255)
                c.drawRect(0f, 0f, w, h, glowP)
            }
            particles(c, w, h, true)
        }
    }
}
