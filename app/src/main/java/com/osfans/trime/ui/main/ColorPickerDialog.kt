/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt

/**
 * 统一的取色面板：饱和度/明度方块 + 色相条 + 透明度条 + 可直接手输的色值。
 *
 * 设置里所有颜色入口都走这一套，「只能手打十六进制」的旧弹窗已下线。
 * 输出格式与全项目一致：不透明为 `#RRGGBB`，带透明度为 `#AARRGGBB`。
 */
object ColorPickerDialog {

    fun show(context: Context, title: String, initial: String, allowAlpha: Boolean = true, picked: (String) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + .5f).toInt()

        var alpha = 255
        var hue = 0f
        var sat = 0f
        var value = 1f
        parseColor(initial)?.let { color ->
            val argb = color.toLong() and 0xFFFFFFFFL
            alpha = ((argb shr 24) and 0xFF).toInt()
            val hsv = FloatArray(3)
            Color.RGBToHSV(
                ((argb shr 16) and 0xFF).toInt(),
                ((argb shr 8) and 0xFF).toInt(),
                (argb and 0xFF).toInt(),
                hsv,
            )
            hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        }
        if (!allowAlpha) alpha = 255

        // 提前声明，供下面各控件的事件回调引用（Kotlin 局部函数必须先声明后使用）。
        var refresh: () -> Unit = {}

        fun currentColor(): Int = Color.HSVToColor(if (allowAlpha) alpha else 255, floatArrayOf(hue, sat, value))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val preview = View(context)
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(52)))

        val square = SvSquareView(context).apply {
            setPadding(0, dp(10), 0, dp(10))
        }
        body.addView(square, LinearLayout.LayoutParams(-1, dp(180)))

        val hueBar = GradientBarView(context).apply {
            colorProvider = { hueColors() }
            onValue = { fraction ->
                hue = (fraction * 360f).coerceIn(0f, 359.99f)
                square.hue = hue
                refresh()
            }
        }

        val alphaBar = GradientBarView(context).apply {
            colorProvider = {
                // 透明 → 当前色，两端 alpha 不同、RGB 相同，看起来就是一条「浓淡」条
                intArrayOf(currentColor() and 0x00FFFFFF, currentColor() or (0xFF shl 24))
            }
            onValue = { fraction ->
                alpha = (fraction * 255f).roundToInt().coerceIn(0, 255)
                refresh()
            }
        }

        body.addView(rowCaption(context, dp(10), "色相"))
        body.addView(hueBar, LinearLayout.LayoutParams(-1, dp(28)))
        if (allowAlpha) {
            body.addView(rowCaption(context, dp(14), "透明度"))
            body.addView(alphaBar, LinearLayout.LayoutParams(-1, dp(28)))
        }

        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val inputLayout = TextInputLayout(context).apply {
            hint = if (allowAlpha) "色值 · #RRGGBB 或 #AARRGGBB，可直接输入" else "色值 · #RRGGBB，可直接输入"
            setPadding(0, dp(14), 0, 0)
            addView(input)
        }
        body.addView(inputLayout, LinearLayout.LayoutParams(-1, -2))

        var writing = false
        fun syncPreview() {
            val color = currentColor()
            preview.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(color)
                setStroke(dp(1), 0x55777777)
            }
            square.sat = sat; square.value = value; square.invalidate()
            hueBar.fraction = hue / 360f; hueBar.invalidate()
            alphaBar.fraction = alpha / 255f; alphaBar.invalidate()
            if (!writing) {
                writing = true
                input.setText(format(alpha, color))
                input.setSelection(input.text?.length ?: 0)
                writing = false
            }
        }

        fun applyTyped(text: String) {
            val parsed = parseColor(text) ?: return
            val argb = parsed.toLong() and 0xFFFFFFFFL
            alpha = if (allowAlpha) ((argb shr 24) and 0xFF).toInt() else 255
            val hsv = FloatArray(3)
            Color.RGBToHSV(
                ((argb shr 16) and 0xFF).toInt(),
                ((argb shr 8) and 0xFF).toInt(),
                (argb and 0xFF).toInt(),
                hsv,
            )
            hue = hsv[0]; sat = hsv[1]; value = hsv[2]
            syncPreview()
        }

        refresh = { syncPreview() }
        square.onChange = { s, v ->
            sat = s; value = v
            syncPreview()
        }
        input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyTyped(input.text?.toString().orEmpty()) }

        square.hue = hue
        syncPreview()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = input.text?.toString().orEmpty().trim()
                // 手输优先：用户最后动的可能是文字而不是滑块
                val parsed = parseColor(typed)
                if (parsed == null) {
                    inputLayout.error = "请输入 #RRGGBB 或 #AARRGGBB"
                    return@setOnClickListener
                }
                val argb = parsed.toLong() and 0xFFFFFFFFL
                val a = if (allowAlpha) ((argb shr 24) and 0xFF).toInt() else 255
                val rgb = Color.rgb(
                    ((argb shr 16) and 0xFF).toInt(),
                    ((argb shr 8) and 0xFF).toInt(),
                    (argb and 0xFF).toInt(),
                )
                picked(format(a, rgb))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun rowCaption(context: Context, topPadding: Int, text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, topPadding, 0, 0)
    }

    private fun hueColors() = intArrayOf(
        Color.HSVToColor(floatArrayOf(0f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(60f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(120f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(180f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(240f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(300f, 1f, 1f)),
        Color.HSVToColor(floatArrayOf(360f, 1f, 1f)),
    )

    /** 接受 `#RRGGBB` / `#AARRGGBB` / `#RGB`，带不带 `#` 都行；失败返回 null。 */
    fun parseColor(raw: String): Int? {
        val text = raw.trim().removePrefix("#")
        if (text.length != 3 && text.length != 6 && text.length != 8) return null
        if (text.any { it.digitToIntOrNull(16) == null }) return null
        return runCatching { Color.parseColor("#" + text) }.getOrNull()
    }

    fun format(alpha: Int, color: Int): String =
        if (alpha >= 255) String.format("#%06X", 0xFFFFFF and color)
        else String.format("#%02X%06X", alpha.coerceIn(0, 255), 0xFFFFFF and color)

    /** 饱和度 / 明度方块。 */
    private class SvSquareView(context: Context) : View(context) {
        var hue = 0f
        var sat = 0f
        var value = 1f
        var onChange: ((Float, Float) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        }
        private val markerShade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = 0x66000000
        }
        private val rect = RectF()
        private var whiteToHue: Shader? = null
        private var blackOverlay: Shader? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rect.set(0f, 0f, w.toFloat(), h.toFloat())
            blackOverlay = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        }

        override fun onDraw(canvas: Canvas) {
            if (width == 0 || height == 0) return
            whiteToHue = LinearGradient(
                0f, 0f, width.toFloat(), 0f,
                Color.WHITE, Color.HSVToColor(floatArrayOf(hue, 1f, 1f)),
                Shader.TileMode.CLAMP,
            )
            paint.shader = whiteToHue
            canvas.drawRect(rect, paint)
            paint.shader = blackOverlay
            canvas.drawRect(rect, paint)
            paint.shader = null
            val x = sat * width
            val y = (1f - value) * height
            canvas.drawCircle(x, y, 9f, markerShade)
            canvas.drawCircle(x, y, 9f, marker)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                parent?.requestDisallowInterceptTouchEvent(true)
                sat = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                value = (1f - event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                invalidate()
                onChange?.invoke(sat, value)
            }
            return true
        }
    }

    /** 色相条 / 透明度条：一条渐变 + 一个拖动手柄。 */
    private class GradientBarView(context: Context) : View(context) {
        var colorProvider: () -> IntArray = { IntArray(0) }
        var fraction = 0f
        var onValue: ((Float) -> Unit)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.WHITE
        }
        private val thumbShade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = 0x66000000
        }
        private val rect = RectF()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rect.set(0f, 4f, w.toFloat(), (h - 4).toFloat())
        }

        override fun onDraw(canvas: Canvas) {
            if (width == 0 || height == 0) return
            val colors = colorProvider()
            if (colors.size >= 2) {
                paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, colors, null, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, 6f, 6f, paint)
                paint.shader = null
            }
            val x = fraction.coerceIn(0f, 1f) * width
            canvas.drawCircle(x, height / 2f, 10f, thumbShade)
            canvas.drawCircle(x, height / 2f, 10f, thumb)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
                parent?.requestDisallowInterceptTouchEvent(true)
                fraction = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                invalidate()
                onValue?.invoke(fraction)
            }
            return true
        }
    }
}
