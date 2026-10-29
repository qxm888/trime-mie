/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.theme.*
import com.osfans.trime.ime.appearance.*
import com.osfans.trime.ime.candidates.popup.*

/** Uses the same candidate/material renderers as the IME; never changes live layouts. */
class FloatingCandidatesPreview(context: Context, private val typography: Map<String,FontSpec>) : FrameLayout(context) {
    private val d=resources.displayMetrics.density
    private fun dp(n:Int)=(n*d).toInt()
    private val theme=StandaloneKeyboardTheme.base()
    private val scope=ThemeScope(theme).apply { updateScheme(theme.presetColorSchemes.getValue("default")) }
    private var style=FloatingCandidateStyle()
    private fun resolved(): FloatingCandidateStyle = style.copy(fonts=FloatingCandidateStyle.roles.keys.associateWith { role ->
        val own=style.fonts[role] ?: FontSpec()
        val inherited=typography[role] ?: FontSpec()
        FontSpec(own.font.ifBlank { inherited.font.ifBlank { "system:sans-serif" } }, own.size.takeIf { it>0 } ?: inherited.size)
    })
    private val panel=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;clipToPadding=false;clipChildren=false }
    private val header=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
    private val preedit=TextView(context).apply { text="ni hao" }
    private val pager=PaginationUi(context,scope) { style }
    private val items=listOf(
        LabeledCandidateItemUi(context,scope) { resolved() },
        LabeledCandidateItemUi(context,scope) { resolved() },
    )
    private val ball=TextView(context).apply { gravity=Gravity.CENTER }
    private var base: android.graphics.drawable.Drawable?=null
    private var blurConfig=SurfaceAppearanceConfig()
    init {
        clipChildren=false;clipToPadding=false;setPadding(dp(16),dp(12),dp(16),dp(12))
        contentDescription="悬浮候选窗实时预览"
        header.addView(preedit,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(pager.root,LinearLayout.LayoutParams(dp(20),dp(20)))
        panel.addView(header)
        items.forEach { panel.addView(it.root,LinearLayout.LayoutParams(-1,-2));it.root.isClickable=true }
        addView(panel,LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=dp(44) })
        addView(ball,LayoutParams(dp(44),dp(44),Gravity.BOTTOM or Gravity.START))
        BackdropBlur(panel,{blurConfig}) { canvas -> base?.let { it.setBounds(0,0,panel.width,panel.height);it.draw(canvas) } }
    }
    fun render(next:FloatingCandidateStyle) {
        style=if(next.enabled) next.sanitized() else FloatingCandidateStyle(panel=SurfaceStyle(),elevation=0,
            paddingHorizontal=theme.window.insets.horizontal,paddingVertical=theme.window.insets.vertical,
            itemPaddingHorizontal=theme.window.itemPadding.horizontal,itemPaddingVertical=theme.window.itemPadding.vertical)
        contentDescription=if(next.enabled) "悬浮候选窗实时预览" else "已关闭自定义，预览默认外观"
        base=FloatingCandidateRenderer.base(scope,style)
        panel.background=FloatingCandidateRenderer.panel(panel,scope,style,base)
        panel.alpha=style.opacity/100f
        panel.setPadding(dp(style.paddingHorizontal),dp(style.paddingVertical),dp(style.paddingHorizontal),dp(style.paddingVertical))
        FloatingCandidateRenderer.elevation(panel,style.panel.radius ?: theme.window.cornerRadius,style.elevation,style.shadowColor)
        panel.clipToOutline=true
        preedit.visibility=if(style.showPreedit) VISIBLE else GONE
        preedit.textSize=style.size("text",16f)
        preedit.typeface=FontOverrides.fromSelection(resolved().fonts["text"]?.font.orEmpty())
        preedit.setTextColor(style.color("text",scope.colors.textColor))
        pager.configure(style.pagination=="bottom")
        (pager.root.parent as? android.view.ViewGroup)?.removeView(pager.root)
        if(style.pagination=="bottom") panel.addView(pager.root,LinearLayout.LayoutParams(-1,dp(style.arrowSize)))
        else header.addView(pager.root,LinearLayout.LayoutParams(dp(style.arrowSize),dp(style.arrowSize)))
        pager.update(Candidates.Paged(hasPrevPage=true,hasNextPage=true))
        items[0].update(CandidateProto("你好","ni hao","①"),true,resources.displayMetrics.widthPixels,1,false)
        items[1].update(CandidateProto("您好","nin hao","②"),false,resources.displayMetrics.widthPixels,1,false)
        ball.text=style.ballLabel;ball.textSize=style.ballSize*.5f
        ball.setTextColor(style.color("ball",scope.colors.candidateTextColor))
        ball.background=FloatingCandidateRenderer.surface(ball,style.ball,KeyEffects(),scope.colors.textBackColor,style.ballSize/2f,style.glassOpacity)
        ball.layoutParams=(ball.layoutParams as LayoutParams).apply { width=dp(style.ballSize);height=dp(style.ballSize) }
        FloatingCandidateRenderer.elevation(ball,style.ball.radius ?: style.ballSize/2f,style.elevation,style.shadowColor)
        blurConfig=SurfaceAppearanceConfig(keys=style.normal.copy(blur=style.normal.blur || style.highlighted.blur || style.pager.blur),bar=style.panel,blurRadius=style.blurRadius,glassOpacity=style.glassOpacity)
        BackdropBlur.invalidateBackground(panel)
        sizePanel()
        requestLayout()
    }
    private fun sizePanel() {
        if(width<=paddingLeft+paddingRight) return
        val available=width-paddingLeft-paddingRight
        panel.layoutParams=(panel.layoutParams as LayoutParams).apply { width=(available*style.maxWidthPercent/100).coerceAtLeast(1) }
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val available=(MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight).coerceAtLeast(1)
        // Measure with the final width on the first frame. Updating layout params from
        // onSizeChanged occurs after child measurement and can leave a zero-height preview.
        val params=panel.layoutParams as LayoutParams
        params.width=(available*style.maxWidthPercent/100).coerceAtLeast(1)
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
}
