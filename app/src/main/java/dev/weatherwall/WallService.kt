package dev.weatherwall

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Calendar
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Live wallpaper: photo + cut-out foreground (tilt parallax) + depth haze + rain/snow + time-of-day tint. */
class WallService : WallpaperService() {
    override fun onCreateEngine(): Engine = E()

    inner class E : Engine(), SensorEventListener {
        private val h = Handler(Looper.getMainLooper())
        private val prefs = getSharedPreferences("ww", 0)
        private var photo: Bitmap? = null
        private var fg: Bitmap? = null
        private var haze: Bitmap? = null
        private var stamp = 0L
        private var tx = 0f; private var ty = 0f; private var gx = 0f; private var gy = 0f
        private var bx = 0f; private var bz = 0f
        private var wx = 1            // 1 clear, 2 clouds, 3 rain, 4 snow, 5 fog
        private var lastFetch = 0L
        private var vis = false
        private var frame = 0
        private var cf: ColorMatrixColorFilter? = null
        private val rnd = Random(1)
        private val n = 120
        private val px = FloatArray(n) { rnd.nextFloat() }
        private val py = FloatArray(n) { rnd.nextFloat() }
        private val ps = FloatArray(n) { 0.5f + rnd.nextFloat() }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val hazePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val pp = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tick = object : Runnable {
            override fun run() { if (vis) { draw(); h.postDelayed(this, 33) } }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            vis = visible
            val sm = getSystemService(SENSOR_SERVICE) as SensorManager
            if (visible) {
                loadScene(); weather()
                sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
                h.post(tick)
            } else {
                sm.unregisterListener(this); h.removeCallbacks(tick)
            }
        }

        override fun onDestroy() { vis = false; h.removeCallbacks(tick); super.onDestroy() }

        override fun onSensorChanged(e: SensorEvent) {
            val rx = e.values[0]; val rz = e.values[2]
            bx += (rx - bx) * 0.003f; bz += (rz - bz) * 0.003f      // slow baseline = resting pose
            gx = (-(rx - bx) / 3f).coerceIn(-1f, 1f)
            gy = ((rz - bz) / 3f).coerceIn(-1f, 1f)
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}

        private fun loadScene() {
            val st = prefs.getLong("scene", 0L)
            if (st == stamp && photo != null) return
            val d = File(filesDir, "scene")
            photo = BitmapFactory.decodeFile(File(d, "photo.jpg").path)
            fg = BitmapFactory.decodeFile(File(d, "fg.png").path)
            haze = BitmapFactory.decodeFile(File(d, "haze.png").path)
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
            val d = when (wx) { 2 -> .92f; 3 -> .78f; 4 -> .88f; 5 -> .9f; else -> 1f }
            val cm = ColorMatrix(floatArrayOf(
                rgb[0] * d, 0f, 0f, 0f, 0f,
                0f, rgb[1] * d, 0f, 0f, 0f,
                0f, 0f, rgb[2] * d, 0f, 0f,
                0f, 0f, 0f, 1f, 0f))
            val sat = ColorMatrix().apply { setSaturation(when (wx) { 2 -> .6f; 3 -> .5f; 4 -> .6f; 5 -> .5f; else -> 1f }) }
            cm.postConcat(sat)
            return ColorMatrixColorFilter(cm)
        }

        private fun draw() {
            val sh = surfaceHolder
            val c: Canvas = (try { sh.lockHardwareCanvas() } catch (e: Exception) { sh.lockCanvas() }) ?: return
            try { render(c) } finally { sh.unlockCanvasAndPost(c) }
        }

        private fun layer(c: Canvas, b: Bitmap, x: Float, y: Float, s: Float, pt: Paint) {
            c.save(); c.translate(x, y); c.scale(s, s); c.drawBitmap(b, 0f, 0f, pt); c.restore()
        }

        private fun render(c: Canvas) {
            val w = c.width.toFloat(); val hh = c.height.toFloat()
            c.drawColor(Color.BLACK)
            val p = photo
            if (p == null) {
                pp.color = Color.WHITE; pp.textSize = 42f
                c.drawText("WeatherWall: build a scene in the app first", 40f, hh / 2, pp); return
            }
            if (frame++ % 30 == 0) { cf = filter(); weather() }
            tx += (gx - tx) * 0.12f; ty += (gy - ty) * 0.12f
            paint.colorFilter = cf; hazePaint.colorFilter = cf
            val s = max(w / p.width, hh / p.height) * 1.1f
            val ox = (w - p.width * s) / 2; val oy = (hh - p.height * s) / 2
            layer(c, p, ox + tx * 6, oy + ty * 6, s, paint)
            haze?.let {
                hazePaint.alpha = when (wx) { 5 -> 255; 2 -> 110; 3 -> 90; 4 -> 80; else -> 0 }
                if (hazePaint.alpha > 0) layer(c, it, ox + tx * 6, oy + ty * 6, s, hazePaint)
            }
            clouds(c, w)
            particles(c, w, hh, 0, n / 2)
            fg?.let { layer(c, it, ox + tx * 18, oy + ty * 18, s, paint) }
            particles(c, w, hh, n / 2, n)
        }

        private val cx = floatArrayOf(.1f, .4f, .7f, .95f, .25f, .6f, .85f)
        private val cy = floatArrayOf(.06f, .12f, .04f, .16f, .22f, .28f, .02f)
        private val cr = floatArrayOf(.45f, .55f, .5f, .4f, .35f, .4f, .3f)
        private var cp: Array<Paint>? = null

        private fun clouds(c: Canvas, w: Float) {
            val k = when (wx) { 2 -> 1f; 3, 4 -> 1.2f; 5 -> .6f; else -> return }
            val ps = cp ?: Array(cx.size) { i ->
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(0f, 0f, cr[i] * w, intArrayOf(0xFFD5DCE4.toInt(), 0x00D5DCE4), null, Shader.TileMode.CLAMP)
                }
            }.also { cp = it }
            val t = (System.currentTimeMillis() % 600_000L) / 600_000f
            for (i in cx.indices) {
                val x = ((cx[i] + t * (0.4f + i * 0.05f)) % 1.3f - 0.15f) * w
                ps[i].alpha = (210 * k).toInt().coerceAtMost(255)
                ps[i].colorFilter = cf
                c.save(); c.translate(x, cy[i] * c.height); c.drawCircle(0f, 0f, cr[i] * w, ps[i]); c.restore()
            }
        }

        private fun particles(c: Canvas, w: Float, hh: Float, from: Int, to: Int) {
            if (wx != 3 && wx != 4) return
            val rain = wx == 3
            for (i in from until to) {
                py[i] += (if (rain) 0.035f else 0.006f) * ps[i]
                if (py[i] > 1f) { py[i] = -0.05f; px[i] = rnd.nextFloat() }
                val x = px[i] * w + (if (rain) 0f else sin((py[i] * 9 + i).toDouble()).toFloat() * 20f) + tx * 10f * ps[i]
                val y = py[i] * hh
                if (rain) {
                    pp.color = Color.argb(110, 200, 215, 235); pp.strokeWidth = 2f * ps[i]
                    c.drawLine(x, y, x - 6f, y + 38f * ps[i], pp)
                } else {
                    pp.color = Color.argb(200, 255, 255, 255)
                    c.drawCircle(x, y, 3f * ps[i] + 1f, pp)
                }
            }
        }
    }
}
