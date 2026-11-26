package dev.weatherwall

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.LocationManager
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var modeBtn: Button
    private val modes = arrayOf("Auto (Open-Meteo)", "Clear", "Clouds", "Rain", "Snow", "Fog")
    private val prefs by lazy { getSharedPreferences("ww", 0) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        status = TextView(this).apply { textSize = 15f }
        col.addView(status)
        add(col, "1. Download models (~104 MB)") { work { Models.download(this) { say(it) }; say("Models ready") } }
        add(col, "2. Choose photo → build scene") {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 1)
        }
        modeBtn = add(col, "") { prefs.edit().putInt("mode", (prefs.getInt("mode", 0) + 1) % modes.size).apply(); refresh() }
        add(col, "Use my location for Auto") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 2)
        }
        add(col, "3. Set as live wallpaper") {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, WallService::class.java)))
        }
        setContentView(ScrollView(this).apply { addView(col) })
        refresh()
    }

    private fun add(p: LinearLayout, t: String, f: () -> Unit): Button =
        Button(this).apply { text = t; setOnClickListener { f() } }.also { p.addView(it) }

    private fun work(f: () -> Unit) = Thread {
        try { f() } catch (e: Throwable) { say("Error: $e") }
    }.start()

    private fun say(t: String) = runOnUiThread { status.text = t }

    private fun refresh() {
        modeBtn.text = "Weather: " + modes[prefs.getInt("mode", 0)]
        status.text = if (Models.ready(this)) "Models: ok" else "Models: not downloaded"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (requestCode == 1 && resultCode == RESULT_OK && uri != null) work {
            if (!Models.ready(this)) { say("Download models first"); return@work }
            say("Loading photo…")
            Models.build(this, load(uri)) { say(it) }
            say("Scene ready. Tap 3 to set the wallpaper.")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 2 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val l = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER).firstNotNullOfOrNull {
                try { lm.getLastKnownLocation(it) } catch (e: SecurityException) { null }
            }
            if (l != null) {
                prefs.edit().putFloat("lat", l.latitude.toFloat()).putFloat("lon", l.longitude.toFloat()).apply()
                say("Location saved")
            } else say("No cached location: open Maps once, then retry")
        }
    }

    private fun load(u: Uri): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (max(o.outWidth, o.outHeight) / s > 4096) s *= 2
        val b = contentResolver.openInputStream(u)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        }!!
        val rot = when (contentResolver.openInputStream(u)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }) {
            6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
        }
        return if (rot == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot) }, true)
    }
}
