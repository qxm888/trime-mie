/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.appearance.SurfaceAppearanceConfig
import com.osfans.trime.ime.appearance.BackdropBlur
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.util.ColorUtils

/** Draw the real key background path and compare pixels with Android's Drawable compositor. */
class TransparentKeysRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun <T> ui(action: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun member(value: Any, name: String): Any {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value)!! }
            catch (_: NoSuchFieldException) { }
            try { return type.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value)!! }
            catch (_: NoSuchMethodException) { }
            type = type.superclass
        }
        error("Missing $name")
    }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            check(targetContext.packageName.endsWith(".debug.sentences"))
            ui {
                val prefs = AppPrefs.defaultInstance()
                prefs.profile.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)
                prefs.keyboard.surfaceAppearance.setValue("")
                prefs.keyboard.keyBackground.setValue("")
                prefs.keyboard.floatingKeyboard.setValue(false)
                prefs.keyboard.oneHandMode.setValue(0)
            }
            activity = startActivitySync(Intent(targetContext, AiTextFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            repeat(150) {
                if (ui { KeyboardWindow.instance != null }) return@repeat
                SystemClock.sleep(60)
            }
            val count = ui {
                val ime = member(checkNotNull(KeyboardWindow.instance), "service") as TrimeInputMethodService
                val view = descendants(member(ime, "inputView") as View).filterIsInstance<KeyView>().first { it.width > 0 && it.height > 0 }
                val key = member(view, "key") as Key
                check(!key.usesImageBackground && !key.hasWholeKeyBackground)
                val config = view.javaClass.getDeclaredField("frameAppearance").apply { isAccessible = true }
                val draw = view.javaClass.getDeclaredMethod("drawBackground", Canvas::class.java, Key::class.java).apply { isAccessible = true }
                var ancestor: View? = view
                var backdrop: BackdropBlur? = null
                while (ancestor != null && backdrop == null) {
                    backdrop = ancestor.getTag(com.osfans.trime.R.id.keyboard_backdrop_blur) as? BackdropBlur
                    ancestor = ancestor.parent as? View
                }
                val shared = checkNotNull(backdrop)
                val front = shared.javaClass.getDeclaredField("front").apply { isAccessible = true }
                val frontKey = shared.javaClass.getDeclaredField("frontKey").apply { isAccessible = true }
                val oldFront = front.get(shared); val oldKey = frontKey.get(shared)
                val blue = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                front.set(shared, blue); frontKey.set(shared, blue)
                val variants = listOf("0x00", "0x00000000", "#00000000", "#00ff0000", "#80000000", "#80ff0000", "#ff00ff00")
                var comparisons = 0
                try { for (glass in listOf(false, true)) for (pressed in listOf(false, true)) {
                    if (pressed) key.onPressed() else key.cancelPress()
                    val background = key.getBackgroundDrawable() as GradientDrawable
                    val oldColor = if (android.os.Build.VERSION.SDK_INT >= 24) background.color!!.defaultColor else error("Pixel fixture requires API 24+")
                    val oldAlpha = background.alpha
                    try {
                        for (raw in variants) for (alpha in listOf(255, 128, 0)) for (opacity in listOf(100, 50, 0)) {
                            val color = ColorUtils.parseColor(raw)
                            background.setColor(color); background.alpha = alpha
                            config.set(view, SurfaceAppearanceConfig(keyOpacity = opacity, keyBlur = if (glass) 65 else 0, glassOpacity = 100))
                            val actual = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                            val expected = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(actual); canvas.drawColor(Color.WHITE)
                            draw.invoke(view, canvas, key)
                            val reference = Canvas(expected); reference.drawColor(Color.WHITE)
                            val bounds = RectF(view.paddingLeft.toFloat(), view.paddingTop.toFloat(),
                                (view.width - view.paddingRight).toFloat(), (view.height - view.paddingBottom).toFloat())
                            val layer = reference.saveLayerAlpha(bounds, opacity * 255 / 100)
                            if (glass) reference.drawRect(bounds, Paint().apply { this.color = Color.BLUE })
                            GradientDrawable().apply {
                                setColor(color); this.alpha = alpha
                                setBounds(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
                            }.draw(reference)
                            reference.restoreToCount(layer)
                            val x = bounds.centerX().toInt(); val y = bounds.centerY().toInt()
                            val got = actual.getPixel(x, y); val want = expected.getPixel(x, y)
                            check(listOf(0, 8, 16, 24).all { shift -> kotlin.math.abs((got ushr shift and 255) - (want ushr shift and 255)) <= 1 }) {
                                "raw=$raw drawableAlpha=$alpha opacity=$opacity pressed=$pressed got=${got.toUInt().toString(16)} expected=${want.toUInt().toString(16)}"
                            }
                            if (raw == "0x00" && alpha == 255 && opacity == 100 && !pressed && !glass) {
                                java.io.File(targetContext.filesDir, "transparent-key-actual.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                // Replay the previous Paint.alpha assignment: this is the reported black rectangle.
                                val paint = Paint().apply { this.color = color; this.alpha = alpha }
                                check(paint.color == Color.BLACK && got == Color.WHITE)
                            }
                            comparisons++
                            actual.recycle(); expected.recycle()
                        }
                    } finally { background.setColor(oldColor); background.alpha = oldAlpha }
                } } finally { front.set(shared, oldFront); frontKey.set(shared, oldKey); blue.recycle() }
                key.cancelPress()
                comparisons
            }
            result.putInt("pixel_comparisons", count)
            result.putString("result", "PASS_REAL_KEY_TRANSPARENT_SEMITRANSPARENT_OPAQUE_NORMAL_PRESSED")
        } catch (t: Throwable) { result.putString("result", "FAIL: ${t.stackTraceToString()}") }
        finally { ui { activity?.finish() } }
        finish(if (result.getString("result")?.startsWith("PASS") == true) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
