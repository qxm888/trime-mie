/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import android.content.Context
import android.graphics.Typeface
import android.icu.text.BreakIterator
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.AutoScaleTextView
import com.osfans.trime.ime.keyboard.GestureFrame
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerOn
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageDrawable

class SwitchOptionEntryUi(
    override val ctx: Context,
    private val scope: ThemeScope,
) : Ui {
    private val theme: Theme get() = scope.theme

    /** 自定义键盘模式下跟随当前键盘配色（浅色强调底、键面文字色）。 */
    private val palette get() = com.osfans.trime.data.keymap.CustomKeyboards.activePalette()

    private fun parseColor(hex: String): Int? =
        runCatching { android.graphics.Color.parseColor(hex) }.getOrNull()

    private fun accentColor(): Int? = palette?.let { p -> parseColor(p.accent) ?: parseColor(p.text) }

    private fun menuTextColor(): Int = palette?.let { parseColor(it.text) } ?: scope.colors.keyTextColor

    private fun tileBackground(): android.graphics.drawable.Drawable? {
        val accent = accentColor()
        return if (accent != null) {
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dp(16).toFloat()
                // 强调色的 13% 淡底：比纯白光板更有主题感，又不会盖过图标。
                setColor((accent and 0x00FFFFFF) or (0x22 shl 24))
            }
        } else {
            scope.decorDrawable(
                "key_back_color",
                "key_border_color",
                ctx.dp(theme.style.keyBorder),
                ctx.dp(theme.style.roundCorner),
            )
        }
    }

    val bkg =
        frameLayout {
            background = tileBackground()
        }

    val icon =
        imageView {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

    val textIcon =
        view(::AutoScaleTextView) {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            // keep original typeface, apply textStyle only
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 600 = Semi Bold, 700 = Bold which is too heavy
                typeface = Typeface.create(typeface, 600, false)
            } else {
                setTypeface(typeface, Typeface.BOLD)
            }
        }

    val label =
        textView {
            textSize = 12f
            gravity = gravityCenter
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(menuTextColor())
        }

    override val root =
        object : GestureFrame(ctx) {
            val content =
                constraintLayout {
                    add(
                        bkg,
                        lParams(dp(48), dp(48)) {
                            topOfParent(dp(4))
                            centerHorizontally()
                            above(label)
                        },
                    )
                    add(
                        icon,
                        lParams {
                            centerOn(bkg)
                        },
                    )
                    add(
                        textIcon,
                        lParams(wrapContent, wrapContent) {
                            centerOn(bkg)
                        },
                    )
                    add(
                        label,
                        lParams(0, wrapContent) {
                            below(bkg, dp(4))
                            centerHorizontally()
                        },
                    )
                }

            init {
                add(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                // 高度跟随系统字体缩放：大字号下两行标签也不会被裁掉。
                val scaled = ctx.resources.displayMetrics.scaledDensity
                val labelBlock = (12f * scaled * 1.3f * 2).toInt()
                val height = (dp(4 + 48 + 6) + labelBlock + dp(6)).coerceAtMost(dp(160))
                layoutParams = ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, height)
            }
        }

    /** Restyles the row after a scheme switch; the icon/text-icon colors are live. */
    fun refreshColors() {
        bkg.background = tileBackground()
        label.setTextColor(menuTextColor())
    }

    fun setEntry(entry: SwitchOptionEntry) {
        if (entry.icon != 0) {
            icon.visibility = View.VISIBLE
            textIcon.visibility = View.GONE
            icon.imageDrawable = ctx.drawable(entry.icon)!!.apply {
                mutate()
                setTint(menuTextColor())
            }
        } else {
            icon.visibility = View.GONE
            textIcon.visibility = View.VISIBLE
            textIcon.text = getFirstCharacter(entry.label)
            textIcon.setTextColor(menuTextColor())
        }
        label.text = entry.label
    }

    private fun getFirstCharacter(s: String): String {
        if (s.isEmpty()) return ""
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val iterator = BreakIterator.getCharacterInstance()
            iterator.setText(s)
            s.substring(iterator.first(), iterator.next())
        } else {
            s.substring(0, s.offsetByCodePoints(0, 1))
        }
    }
}
