/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * 给任意背景 drawable 套一层圆角裁剪。
 *
 * 键盘四角本来靠容器 `clipPath` 裁，但那条路要经过一整条 canvas 调用链：
 * 容器 draw → 子视图 → 背景 ImageView → drawable。任何一层（硬件层、离屏缓冲、
 * 某个 ROM 的渲染加速）把 clip 吃掉，四角就退回直角，而键盘看起来和没设置一样。
 *
 * 这里让背景自己就是圆的：drawable 只把自己画进圆角路径里，不依赖外层是否裁了。
 * 纯色、图片、渐变都一样处理，不需要为每种背景类型单独写圆角实现。
 */
internal class CornerClipDrawable(private val wrapped: Drawable) : Drawable() {
    private val path = Path()
    private val rect = RectF()

    /** TL, TR, BR, BL 各两个分量，单位是像素。 */
    var radii: FloatArray = FloatArray(8)
        set(value) {
            if (field.contentEquals(value)) return
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        if (radii.none { it > 0f }) {
            wrapped.draw(canvas)
            return
        }
        val save = canvas.save()
        rect.set(wrapped.bounds)
        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.clipPath(path)
        wrapped.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        super.setBounds(left, top, right, bottom)
        wrapped.bounds = Rect(left, top, right, bottom)
    }

    override fun onBoundsChange(bounds: Rect) {
        wrapped.bounds = bounds
    }

    override fun getIntrinsicWidth(): Int = wrapped.intrinsicWidth

    override fun getIntrinsicHeight(): Int = wrapped.intrinsicHeight

    override fun setAlpha(alpha: Int) {
        wrapped.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        wrapped.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
