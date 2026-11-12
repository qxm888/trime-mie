/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.WindowManager
import com.osfans.trime.util.DiagnosticLog
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * 整块面板的「真背景模糊」背板。
 *
 * 和 [PanelGradient] 不是一类东西：渐变是我们自己往画布上涂颜色，模糊是**系统**
 * 在合成时把这块区域背后的内容先糊掉再叠上来，看起来像隔着玻璃看后面那个 App。
 *
 * 实现走 Android 12 的 `ViewRootImpl.createBackgroundBlurDrawable()`（@hide，
 * 反射调用），它按 Drawable 的矩形注册模糊区域——面板画多大就只糊那一块，
 * 不像 `FLAG_BLUR_BEHIND` / `setBackgroundBlurRadius` 那样糊整个窗口背后。
 *
 * 优先级在渐变之上：开关打开时纯色、渐变、图片背景全部让位。
 */
@Serializable
data class PanelBackdrop(
    val enabled: Boolean = false,
    /** 模糊强度 0–100，线性映射到系统半径 0–[MAX_RADIUS_PX]。 */
    val strength: Int = 60,
    /**
     * 图片/视频背景本身的透明度（0–100）。
     *
     * 图片和视频不像纯色那样天然支持 alpha，如果原样铺上来会把背后的模糊完全盖死，
     * 所以这里给一个统一的混入比例：100 = 原图不透明，55 = 糊掉的图半透明、
     * 背后的模糊还能透出来，0 = 只剩背后的模糊。
     */
    val imageAlpha: Int = 55,
    /**
     * 图片/视频整层的不透明度（0–100）。
     *
     * 100 = 图（磨砂版）完全盖住面板，背后的系统模糊看不见；
     * 60 左右 = 磨砂的图还透出后面那个 App 的模糊，两层糊叠在一起。
     * 和 [imageAlpha]（图自己被糊掉多少）是两回事。
     */
    val imageOpacity: Int = 100,
) {
    fun sanitized(): PanelBackdrop = copy(
        strength = strength.coerceIn(0, 100),
        imageAlpha = imageAlpha.coerceIn(0, 100),
        imageOpacity = imageOpacity.coerceIn(0, 100),
    )

    /** 模糊半径（像素）。系统那个接口按像素收，0 表示关。 */
    fun radiusPx(): Int = (sanitized().strength / 100f * MAX_RADIUS_PX).roundToInt()

    companion object {
        const val MAX_RADIUS_PX = 200
    }
}

/**
 * 反射包装 `BackgroundBlurDrawable`：一个面板只建一次，之后只改半径/颜色/圆角。
 *
 * 每次 `createBackgroundBlurDrawable()` 都会在窗口里注册一个新的模糊区域，
 * 布局一抖动就重建会越积越多，所以这里按 host 缓存一个 Drawable 反复改参数。
 */
object SystemBackdrop {
    /** 最近一次申请结果，给设置页做回显：这里说没成，屏幕上当然也就没有。 */
    @Volatile
    var lastStatus: String = "未尝试"
        private set

    /**
     * 每个 host 单独记一份状态。键盘面板和悬浮候选窗是两个 host，共用一个全局
     * [lastStatus] 时会互相覆盖：候选窗成功把状态写成 "ok"，键盘这边失败想重试
     * 就读不到 "no_view_root" 了。
     */
    private val hostStatus = WeakHashMap<View, String>()

    /** 只读某个 host 的最近一次结果。 */
    fun statusOf(host: View): String = synchronized(hostStatus) { hostStatus[host].orEmpty() }

    private fun status(host: View?, value: String) {
        lastStatus = value
        if (host != null) synchronized(hostStatus) { hostStatus[host] = value }
        DiagnosticLog.event("appearance", "backdrop", value)
    }

    /** 自糊层（图片/视频）最近一次的状态，同样只在设置页回显。 */
    @Volatile
    var selfBlurStatus: String = "未启用"
        private set

    fun recordSelfBlur(status: String) { selfBlurStatus = status }

    /**
     * 系统跨窗口模糊开关（省电模式 / 开发者选项 / 播放视频）会随时翻转。
     * 每次翻转递增一次，强制所有 holder 在下一次 [obtain] 重新申请，否则
     * 「系统关掉再打开」之后缓存的旧 drawable 永远不会被重新登记。
     */
    @Volatile
    private var epoch = 0

    fun bumpEpoch() { epoch++ }

    private class Holder(
        val drawable: Drawable,
        val setRadius: Method?,
        val setColor: Method?,
        val setCorners: Method?,
        /** 创建这个 drawable 时的 ViewRootImpl；窗口重建后对象会换，必须丢掉重来。 */
        val owner: Any?,
        val epoch: Int,
    )

    private val holders = WeakHashMap<View, Holder>()

    /** 窗口重建后旧的 drawable 属于上一个 ViewRootImpl，必须丢掉重来。 */
    fun forget(host: View) {
        synchronized(holders) { holders.remove(host) }
        synchronized(hostStatus) { hostStatus.remove(host) }
        lastStatus = "未尝试"
    }

    /**
     * 窗口身份 + 系统开关代数。转屏/窗口重建后 ViewRootImpl 会换成新对象，旧
     * drawable 属于上一个窗口，系统不再为它登记模糊区域——必须丢掉重来。调用方
     * 把它拼进缓存 key，让「半径和圆角都没变」的短路也能在转屏时失效。
     */
    fun windowStamp(host: View): String = "${System.identityHashCode(viewRootImpl(host))}:$epoch"

    fun obtain(host: View, radiusPx: Int, color: Int, cornerRadiiPx: FloatArray): Drawable? {
        val vri = viewRootImpl(host)
        val currentEpoch = epoch
        // 窗口换了、系统开关翻了、或窗口还没 attach：缓存一律作废。
        val cached = synchronized(holders) { holders[host] }
        if (cached != null && (vri == null || cached.owner !== vri || cached.epoch != currentEpoch)) {
            synchronized(holders) { holders.remove(host) }
        }
        val holder = synchronized(holders) { holders[host] }
            ?: createHolder(host, vri, currentEpoch)?.also { synchronized(holders) { holders[host] = it } }
            ?: return null
        runCatching { holder.setRadius?.invoke(holder.drawable, radiusPx) }
        runCatching { holder.setColor?.invoke(holder.drawable, color) }
        if (cornerRadiiPx.size >= 8) {
            runCatching {
                holder.setCorners?.invoke(
                    holder.drawable,
                    cornerRadiiPx[0], cornerRadiiPx[2], cornerRadiiPx[4], cornerRadiiPx[6],
                )
            }
        }
        status(host, "ok radius=$radiusPx color=${Integer.toHexString(color)}${systemBlurSuffix(host)}")
        return holder.drawable
    }

    /**
     * 系统总开关的状态。false 时不管窗口怎么写都不会糊（省电模式、开发者选项、
     * 播放视频时系统会临时关掉），单独记一笔，省得和「窗口类型被拒」混在一起查。
     */
    private fun systemBlurSuffix(host: View): String = runCatching {
        val wm = host.context.getSystemService(WindowManager::class.java)
        val enabled = wm?.isCrossWindowBlurEnabled ?: true
        if (enabled) "" else " system_blur=off"
    }.getOrDefault("")

    private fun createHolder(host: View, vri: Any?, stampEpoch: Int): Holder? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            status(host, "api=${Build.VERSION.SDK_INT}<31")
            return null
        }
        if (vri == null) {
            status(host, "no_view_root")
            return null
        }
        val drawable = runCatching {
            val maker = vri.javaClass.getDeclaredMethod("createBackgroundBlurDrawable")
            maker.isAccessible = true
            maker.invoke(vri) as? Drawable
        }.getOrElse { error ->
            status(host, "fail ${error.javaClass.simpleName}")
            DiagnosticLog.event("appearance", "backdrop_create_failed", "type=${error.javaClass.simpleName}")
            null
        }
        if (drawable == null) {
            status(host, "unsupported")
            return null
        }
        val type = drawable.javaClass
        fun method(name: String, vararg params: Class<*>): Method? =
            runCatching { type.getDeclaredMethod(name, *params).apply { isAccessible = true } }.getOrNull()
        return Holder(
            drawable = drawable,
            setRadius = method("setBlurRadius", Integer.TYPE),
            setColor = method("setColor", Integer.TYPE),
            setCorners = method("setCornerRadius", java.lang.Float.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE, java.lang.Float.TYPE),
            owner = vri,
            epoch = stampEpoch,
        )
    }

    private fun viewRootImpl(host: View): Any? {
        // 路径一：View.getViewRootImpl()（@hide，大部分 ROM 都还留着）。
        runCatching {
            val getter = View::class.java.getDeclaredMethod("getViewRootImpl")
            getter.isAccessible = true
            getter.invoke(host)?.let { return it }
        }
        // 路径二：attach 之后根 View 的 parent 就是 ViewRootImpl。
        runCatching {
            val parent = host.rootView.parent
            if (parent != null && parent.javaClass.simpleName.contains("ViewRootImpl")) return parent
        }
        return null
    }
}

/**
 * 垫在按键下面的一层「糊掉的自己」。
 *
 * 系统模糊只能糊窗口下面那个 App，碰不到我们自己画的图/视频。要让图片和视频也被糊，
 * 只能把面板内容抓成缩略图、在后台线程上糊一遍再贴回来——[BackdropBlur] 已经在做
 * 这件事（按键的「动态模糊背景」用的就是它），这里把它铺满整块面板。
 *
 * 这一层只负责画「糊掉后的图/视频」，系统模糊在它下面的 sibling 上；两者叠起来就是
 * 「磨砂的图 + 图背后糊着的 App」。
 */
class GlassBackdropView(context: Context) : View(context) {
    /** 0–100：糊掉之后的图/视频有多不透明，剩下的透给背后的系统模糊。 */
    var imageAlpha: Int = 55
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 面板四角半径（px），自糊层也必须裁，否则会糊到圆角外面「收不住」。 */
    var radii: FloatArray = FloatArray(8)
        set(value) {
            field = value.copyOf()
            invalidate()
        }

    /**
     * 异步首帧还没算出来时的占位：直接用未糊的原图铺一层，视觉上从「清晰」
     * 过渡到「磨砂」，而不是先黑/透空一段时间再突然出现。
     */
    var placeholder: Drawable? = null
        set(value) {
            field = value
            invalidate()
        }

    private val area = RectF()
    private val clipPath = android.graphics.Path()

    init {
        // 纯绘制 View 必须显式打开 onDraw，否则 ViewGroup 会整块跳过它。
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        if (imageAlpha <= 0 || width <= 0 || height <= 0) return
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        val saved = canvas.save()
        if (radii.any { it > 0f }) {
            Squircle.into(clipPath, area, radii)
            canvas.clipPath(clipPath)
        }
        val alpha = imageAlpha * 255 / 100
        if (!BackdropBlur.draw(this, canvas, area, alpha)) {
            // 第一帧还没糊好：先铺未糊的原图，别留一块黑/透空。
            placeholder?.let {
                it.setBounds(0, 0, width, height)
                it.alpha = alpha
                it.draw(canvas)
                it.alpha = 255
            }
        }
        canvas.restoreToCount(saved)
    }
}

/**
 * 系统模糊的落点。
 *
 * 系统那边只能用**圆弧**画角（`setCornerRadius`），我们的面板是 G2 连续圆角，
 * 两者在 45° 处的轮廓差好几像素——直接交给系统画，角上就会露出一条没糊的缝。
 * 所以这里让系统按直角矩形登记模糊区域，再由我们自己用同一个 G2 路径裁一刀，
 * 角部形状就和按键、描边完全一致。
 */
class BlurBackdropView(context: Context) : View(context) {
    private var blur: Drawable? = null
    private var radii: FloatArray = FloatArray(8)
    private val path = android.graphics.Path()
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setBackdrop(drawable: Drawable?, cornerRadiiPx: FloatArray) {
        blur = drawable
        radii = cornerRadiiPx.copyOf()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val drawable = blur ?: return
        if (width <= 0 || height <= 0) return
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        Squircle.into(path, rect, radii)
        val saved = canvas.save()
        canvas.clipPath(path)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        canvas.restoreToCount(saved)
    }
}
