/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import com.osfans.trime.R
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.KeyboardColorGroups
import com.osfans.trime.data.theme.KeyboardStyleTarget
import com.osfans.trime.ime.appearance.SurfaceAppearance
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.sqrt

/** Public-API experiment only. Synthetic pixels below an actual input-method window. */
@androidx.annotation.RequiresApi(31)
class DynamicGlassProbeRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun <T> ui(action: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun member(value: Any, name: String): Any? {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value) } catch (_: NoSuchFieldException) { }
            try { return type.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value) } catch (_: NoSuchMethodException) { }
            type = type.superclass
        }
        error("Missing $name")
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun await(label: String, condition: () -> Boolean) {
        repeat(250) { if (ui(condition)) return; SystemClock.sleep(40) }; error(label)
    }
    private fun shot(name: String): Bitmap {
        SystemClock.sleep(500)
        val b = checkNotNull(uiAutomation.takeScreenshot())
        File(targetContext.cacheDir, "dynamic-glass-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return b
    }
    override fun onStart() = runBlocking {
        val result = Bundle(); var activity: Activity? = null; var dialog: Dialog? = null
        try {
            check(Build.VERSION.SDK_INT >= 31 && targetContext.packageName.endsWith(".debug.sentences"))
            await("Preferences not ready") { runCatching { AppPrefs.defaultInstance() }.isSuccess }
            val prefs = AppPrefs.defaultInstance(); CustomKeyboards.awaitBuiltinLayouts()
            ui { prefs.profile.dataStorageMode.setValue(DataStorageMode.APP_STORAGE) }
            CustomKeyboards.init(targetContext); CustomKeyboards.awaitBuiltinLayouts()
            CustomKeyboards.resetBuiltinSet("custom_liquid_glass")
            ui {
                prefs.keyboard.floatingKeyboard.setValue(false); prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                prefs.keyboard.oneHandMode.setValue(0); prefs.keyboard.hideInputBar.setValue(false)
                prefs.candidates.mode.setValue(PopupCandidatesMode.DISABLED); prefs.candidates.floatingBall.setValue(false)
                prefs.general.inputStatsEnabled.setValue(false); prefs.general.sentenceLearningEnabled.setValue(false)
                KeyboardColorGroups.deactivate()
                KeyboardModeBinding.save(true, KeyboardModeBinding()); KeyboardModeBinding.save(false, KeyboardModeBinding())
            }
            KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin", "custom_liquid_glass", "液态玻璃"))
            activity = startActivitySync(Intent(targetContext, CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("IME missing") { KeyboardWindow.instance != null }
            fun ime() = member(KeyboardWindow.instance!!, "service") as TrimeInputMethodService
            fun input() = member(ime(), "inputView") as InputView
            val pattern = object : Drawable() {
                var phase = 0
                private val paint = Paint()
                override fun draw(canvas: Canvas) {
                    for (y in bounds.top until bounds.bottom step 8) for (x in bounds.left until bounds.right step 8) {
                        val bright = (x / 8 + y / 8) % 2 == 0
                        paint.color = if (phase == 0) (if (bright) Color.rgb(35, 200, 255) else Color.rgb(5, 40, 70))
                            else (if (bright) Color.rgb(255, 180, 45) else Color.rgb(115, 20, 10))
                        canvas.drawRect(x.toFloat(), y.toFloat(), x + 8f, y + 8f, paint)
                    }
                }
                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(colorFilter: ColorFilter?) {}
                @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.OPAQUE
            }
            ui {
                activity!!.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity!!.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                activity!!.window.setBackgroundDrawable(pattern)
                activity!!.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).setBackgroundColor(Color.TRANSPARENT)
                val cfg = SurfaceAppearance.current(true)
                SurfaceAppearance.save(cfg.copy(backdrop = cfg.backdrop.copy(systemBlur = false, imageOpacity = 0, imageAlpha = 0)), true, CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId))
                com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh()
            }
            await("Transparent probe layout missing") { input().isShown && SurfaceAppearance.current(true).backdrop.imageOpacity == 0 }
            SystemClock.sleep(700)
            val rect = ui { val v = input().keyboardView; val xy = IntArray(2); v.getLocationOnScreen(xy); Rect(xy[0], xy[1], xy[0]+v.width, xy[1]+v.height) }
            val sample = ui {
                val keys = views(input()).filterIsInstance<KeyView>().filter { it.isShown }.sortedBy { it.y }.take(10).sortedBy { it.x }
                val a = IntArray(2); val b = IntArray(2); keys[0].getLocationOnScreen(a); keys[1].getLocationOnScreen(b)
                val x = (a[0] + keys[0].width + b[0]) / 2
                Rect(x-2, a[1]+keys[0].height/4, x+2, a[1]+keys[0].height*3/4)
            }
            result.putString("keyboard_rect", rect.toShortString()); result.putString("sample_rect", sample.toShortString())
            val wm = ime().getSystemService(Context.WINDOW_SERVICE) as WindowManager
            result.putBoolean("cross_window_enabled", wm.isCrossWindowBlurEnabled)
            val before = shot("no-blur")
            ui {
                dialog = Dialog(ime(), R.style.DebugDynamicGlassWindow).apply {
                    setContentView(FrameLayout(context)); setCancelable(false)
                    val w = checkNotNull(window)
                    w.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius=24*context.resources.displayMetrics.density })
                    w.attributes = w.attributes.apply {
                        type=WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA
                        token=checkNotNull(ime().window.window).decorView.windowToken
                        gravity=Gravity.TOP or Gravity.LEFT; x=rect.left; y=rect.top; width=rect.width(); height=rect.height()
                        flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        format=PixelFormat.TRANSLUCENT
                        setFitInsetsTypes(0)
                        layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    }
                    show(); w.setBackgroundBlurRadius(80)
                }
            }
            val after = shot("public-media-80")
            result.putString("media_actual_rect", ui {
                val v=dialog!!.window!!.decorView; val xy=IntArray(2);v.getLocationOnScreen(xy)
                Rect(xy[0],xy[1],xy[0]+v.width,xy[1]+v.height).toShortString()
            })
            ui { pattern.phase=1; pattern.invalidateSelf() }
            val changed = shot("public-media-warm")
            fun stats(b: Bitmap): Pair<Double, Double> {
                val values = ArrayList<Double>()
                for (y in sample.top until sample.bottom) for (x in sample.left until sample.right) {
                    val c=b.getPixel(x,y); values += (Color.red(c)+Color.green(c)+Color.blue(c))/3.0
                }
                val mean=values.average(); return mean to sqrt(values.sumOf { (it-mean)*(it-mean) }/values.size)
            }
            result.putString("without_blur_mean_stddev", stats(before).toString())
            result.putString("public_blur_mean_stddev", stats(after).toString())
            result.putString("warm_blur_mean_stddev", stats(changed).toString())
            result.putString("result", "PROBE_COMPLETED_REQUIRES_PIXEL_REVIEW")
            before.recycle(); after.recycle(); changed.recycle()
        } catch (t: Throwable) { result.putString("result", "FAIL: ${t.stackTraceToString()}") }
        finally { ui { dialog?.dismiss(); activity?.finish() } }
        finish(if (result.getString("result")?.startsWith("PROBE_COMPLETED") == true) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
