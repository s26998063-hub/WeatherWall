package dev.weatherwall

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
        private val flake = softSprite(64)
        private val warm = PorterDuffColorFilter(0xFFFFE2A8.toInt(), PorterDuff.Mode.SRC_IN)
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
        private val n = 140
        private val ax = FloatArray(n) { rnd.nextFloat() }
        private val ay = FloatArray(n) { rnd.nextFloat() }
        private val az = FloatArray(n) { rnd.nextFloat() }
        private val ph = FloatArray(n) { rnd.nextFloat() * 6.28f }
        private val lx = FloatArray(14)
        private val ly = FloatArray(14)
        private val lt = FloatArray(14)

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            sw = width; sh = height; stamp = -1L
            glowP.shader = RadialGradient(sw * 0.15f, -sh * 0.02f, sw * 1.2f, 0xCCFFE9B8.toInt(), 0x00FFE9B8, Shader.TileMode.CLAMP)
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
            stamp = st
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
            floatArrayOf(0f, .66f, .72f, .90f), floatArrayOf(5f, .75f, .78f, .92f), floatArrayOf(7f, 1f, .85f, .75f),
            floatArrayOf(10f, 1f, 1f, 1f), floatArrayOf(16f, 1f, 1f, 1f), floatArrayOf(18.5f, 1f, .78f, .62f),
            floatArrayOf(20.5f, .78f, .76f, .92f), floatArrayOf(24f, .66f, .72f, .90f)
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
            day = (((rgb[0] + rgb[1] + rgb[2]) / 3f - .7f) / .3f).coerceIn(0f, 1f)
            val d = when (wx) { 2 -> .93f; 3 -> .85f; 4 -> .95f; 5 -> .9f; else -> 1f }
            val k = when (wx) { 4 -> .88f; 5 -> .82f; else -> 1f }
            val off = when (wx) { 2 -> 8f; 4 -> 22f; 5 -> 30f; else -> 0f }
            val cm = ColorMatrix(floatArrayOf(
                rgb[0] * d * k, 0f, 0f, 0f, off,
                0f, rgb[1] * d * k, 0f, 0f, off,
                0f, 0f, rgb[2] * d * k, 0f, off,
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
            for (i in 0 until n) {
                val z = az[i]
                when (wx) {
                    3 -> {
                        val sp = 900f + z * 1900f
                        ay[i] += sp * dt / h; ax[i] += sp * 0.10f * dt / w
                        if (ay[i] > 1.05f) { ay[i] = -0.12f; ax[i] = rnd.nextFloat() * 1.15f - 0.1f }
                        if (ax[i] > 1.15f) ax[i] -= 1.25f
                    }
                    4 -> {
                        ay[i] += (60f + z * 220f) * dt / h
                        ax[i] += sin(time * (0.6f + z * 0.6f) + ph[i]) * (12f + z * 40f) * dt / w
                        if (ay[i] > 1.08f) { ay[i] = -0.08f; ax[i] = rnd.nextFloat() }
                    }
                    1 -> {
                        ay[i] -= (8f + z * 22f) * dt / h
                        ax[i] += sin(time * 0.4f + ph[i]) * 14f * dt / w
                        if (ay[i] < -0.05f) { ay[i] = 1.05f; ax[i] = rnd.nextFloat() }
                    }
                }
            }
            val f = fg
            if (f != null && (wx == 1 || wx == 4)) for (i in 0 until 14) {
                lt[i] -= dt * 0.9f
                if (lt[i] <= 0f && rnd.nextFloat() < dt * 3f) {
                    val px = rnd.nextInt(f.width); val py = rnd.nextInt(f.height)
                    if (Color.alpha(f.getPixel(px, py)) > 220) { lx[i] = px.toFloat(); ly[i] = py.toFloat(); lt[i] = 1f }
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
            place(cloudA, 7f, 14f, 0f); place(cloudB, 11f, 7f, 2f)
            for (i in 0 until 8) {
                val a = (top * (1f - i / 8f)).toInt()
                strip(c, cloudA, a, i * h * 0.07f, (i + 1) * h * 0.07f + 1f, w)
                strip(c, cloudB, a / 2, i * h * 0.07f, (i + 1) * h * 0.07f + 1f, w)
            }
        }

        private fun particles(c: Canvas, w: Float, h: Float, near: Boolean) {
            if (wx == 2 || wx == 5) return
            for (i in 0 until n) {
                val z = az[i]
                if ((z >= .5f) != near) continue
                val x = ax[i] * w + tx * (10f + z * 22f); val y = ay[i] * h
                if (wx == 3) {
                    val len = 26f + z * 110f
                    pp.strokeWidth = 1.2f + z * 2.6f
                    pp.color = Color.argb((50 + z * 110).toInt(), 205, 220, 240)
                    c.drawLine(x, y, x - len * 0.10f, y - len, pp)
                } else {
                    val sz = if (wx == 4) 8f + z * z * 70f else 4f + z * 12f
                    spriteP.alpha = if (wx == 4) (80 + z * 140).toInt() else (30 + z * 60).toInt()
                    rf.set(x - sz / 2, y - sz / 2, x + sz / 2, y + sz / 2)
                    c.drawBitmap(flake, null, rf, spriteP)
                }
            }
        }

        private fun render(c: Canvas) {
            val w = c.width.toFloat(); val h = c.height.toFloat()
            c.drawColor(Color.BLACK)
            val p = photo
            if (p == null) {
                pp.color = Color.WHITE; pp.textSize = 42f; pp.strokeWidth = 1f
                c.drawText("WeatherWall: build a scene in the app first", 40f, h / 2, pp); return
            }
            paint.colorFilter = cf; hazePaint.colorFilter = cf; nPaint.colorFilter = cf
            spriteP.colorFilter = if (wx == 1) warm else null
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
            if (wx == 2 || wx == 3 || wx == 4) clouds(c, w, h)
            particles(c, w, h, false)
            val f = fg
            val fx = if (f != null) (w - f.width * br) / 2 + tx * 20f + sway else 0f
            val fy = if (f != null) (h - f.height * br) / 2 + ty * 20f else 0f
            f?.let { layer(c, it, fx, fy, br, paint) }
            if (wx == 5) { place(fogC, 7f, 52f, 6f); strip(c, fogC, 70, 0f, h, w) }
            if (wx == 1) {
                glowP.alpha = (255 * (0.55f + 0.2f * sin(time * 0.5f)) * day).toInt().coerceIn(0, 255)
                c.drawRect(0f, 0f, w, h, glowP)
            }
            if (f != null && (wx == 1 || wx == 4)) for (i in 0 until 14) if (lt[i] > 0f) {
                val a = sin(lt[i] * PI.toFloat()); val sz = 12f + 34f * a
                spriteP.alpha = (255 * a).toInt().coerceIn(0, 255)
                val x = fx + lx[i] * br; val y = fy + ly[i] * br
                rf.set(x - sz / 2, y - sz / 2, x + sz / 2, y + sz / 2)
                c.drawBitmap(flake, null, rf, spriteP)
            }
            particles(c, w, h, true)
        }
    }
}
