package dev.weatherwall

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed module (legacy API, scope: Samsung Dressroom).
 *  - WeatherLandingActivity: the dead "Try" button is enabled and opens WeatherWall instead.
 *  - Every other Dressroom screen: a floating "Weather+" button opens WeatherWall.
 *    Check the LSPosed log ("WeatherWall: activity ...") to find the editor class and restrict ONLY below.
 */
class Hook : IXposedHookLoadPackage {
    private val only = ""   // e.g. "EditActivity": inject the floating button only into activities whose class name contains this

    override fun handleLoadPackage(lp: XC_LoadPackage.LoadPackageParam) {
        if (lp.packageName != "com.samsung.android.app.dressroom") return
        XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val a = param.thisObject as Activity
                if (!a.javaClass.name.startsWith("com.samsung.android.app.dressroom")) return
                try { inject(a) } catch (t: Throwable) { XposedBridge.log("WeatherWall: $t") }
            }
        })
    }

    private fun inject(a: Activity) {
        val name = a.javaClass.name
        XposedBridge.log("WeatherWall: activity $name")
        val root = a.window.decorView as ViewGroup
        if (name.endsWith(".WeatherLandingActivity")) {
            firstButton(root)?.let { b ->
                b.isEnabled = true; b.alpha = 1f
                b.setOnClickListener { open(a) }
            }
            return
        }
        if (only.isNotEmpty() && !name.contains(only)) return
        if (root.findViewWithTag<View>("ww_btn") != null) return
        val btn = Button(a).apply {
            tag = "ww_btn"; text = "☁ Weather+"; setTextColor(Color.WHITE)
            setBackgroundColor(0xCC202020.toInt()); setOnClickListener { open(a) }
        }
        root.addView(btn, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 32, 280) })
    }

    private fun open(a: Activity) {
        a.startActivity(Intent().setComponent(ComponentName("dev.weatherwall", "dev.weatherwall.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun firstButton(v: View): Button? {
        if (v is Button) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstButton(v.getChildAt(i))?.let { return it }
        return null
    }
}
