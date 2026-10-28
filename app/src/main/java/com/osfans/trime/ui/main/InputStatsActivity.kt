/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.osfans.trime.R
import com.osfans.trime.data.stats.InputStats
import com.osfans.trime.data.stats.InputStats.PeriodStats
import com.osfans.trime.data.stats.InputStats.Stats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Native input statistics dashboard (day / week / month / year, career level,
 * speed, keystrokes, word/code length distributions and the keyboard heatmap),
 * tracked per rime scheme — switch schemes with the chip row.
 *
 * The data engine is [InputStats], fed from the rime session hooks. This
 * activity only renders a snapshot taken in [onResume].
 */
class InputStatsActivity : AppCompatActivity() {
    private var currentPeriod = Period.DAY
    private var currentSchema: String = ""

    private var lastSnapshot: Stats? = null

    private lateinit var realmView: TextView
    private lateinit var careerLevelView: TextView
    private lateinit var careerTitleView: TextView
    private lateinit var expBar: android.widget.ProgressBar
    private lateinit var expText: TextView
    private lateinit var totalCharsView: TextView
    private lateinit var attrSpeedView: TextView
    private lateinit var attrAccuracyView: TextView
    private lateinit var currentSchemaView: TextView
    private lateinit var schemaGroup: com.google.android.material.chip.ChipGroup
    private lateinit var overviewTitle: TextView
    private lateinit var commitCountView: TextView
    private lateinit var charCountView: TextView
    private lateinit var keyCountView: TextView
    private lateinit var errRatioView: TextView
    private lateinit var fastestSpdView: TextView
    private lateinit var avgSpdView: TextView
    private lateinit var fastestDetail: TextView
    private lateinit var avgDetail: TextView
    private lateinit var wordRows: LinearLayout
    private lateinit var codeRows: LinearLayout
    private lateinit var heatGrid: LinearLayout

    private enum class Period { DAY, WEEK, MONTH, YEAR }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_stats)

        val toolbar = findViewById<MaterialToolbar>(R.id.stats_toolbar)
        // 状态栏 padding 要打在外层 AppBarLayout（wrap_content）上：
        // 打在固定高度 actionBarSize 的 toolbar 上会把标题下半截挤出屏幕
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.stats_app_bar)) { v, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            windowInsets
        }
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.input_stats)
        toolbar.menu.add(0, 2112, 0, "备份与恢复")
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == 2112) startActivity(Intent(this, PersonalDataActivity::class.java)) else onOptionSelected(item.itemId)
            true
        }

        realmView = findViewById(R.id.stats_realm)
        careerLevelView = findViewById(R.id.stats_career_level)
        careerTitleView = findViewById(R.id.stats_career_title)
        expBar = findViewById(R.id.stats_exp_bar)
        expText = findViewById(R.id.stats_exp_text)
        totalCharsView = findViewById(R.id.stats_total_chars)
        attrSpeedView = findViewById(R.id.stats_attr_speed)
        attrAccuracyView = findViewById(R.id.stats_attr_accuracy)
        currentSchemaView = findViewById(R.id.stats_current_schema)
        schemaGroup = findViewById(R.id.stats_schema_group)
        overviewTitle = findViewById(R.id.stats_overview_title)
        commitCountView = findViewById(R.id.stats_commit_count)
        charCountView = findViewById(R.id.stats_char_count)
        keyCountView = findViewById(R.id.stats_key_count)
        errRatioView = findViewById(R.id.stats_err_ratio)
        fastestSpdView = findViewById(R.id.stats_fastest_spd)
        avgSpdView = findViewById(R.id.stats_avg_spd)
        fastestDetail = findViewById(R.id.stats_fastest_detail)
        avgDetail = findViewById(R.id.stats_avg_detail)
        wordRows = findViewById(R.id.stats_word_rows)
        codeRows = findViewById(R.id.stats_code_rows)
        heatGrid = findViewById(R.id.stats_heat_grid)

        val toggle = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.stats_period_toggle)
        toggle.check(
            when (currentPeriod) {
                Period.DAY -> R.id.stats_period_day
                Period.WEEK -> R.id.stats_period_week
                Period.MONTH -> R.id.stats_period_month
                Period.YEAR -> R.id.stats_period_year
            },
        )
        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentPeriod = when (checkedId) {
                R.id.stats_period_day -> Period.DAY
                R.id.stats_period_week -> Period.WEEK
                R.id.stats_period_month -> Period.MONTH
                else -> Period.YEAR
            }
            renderPeriod()
            renderHeat()
        }
    }

    override fun onResume() {
        super.onResume()
        currentSchema = InputStats.activeSchemaId
        reload()
    }

    override fun onPause() {
        super.onPause()
        InputStats.saveSoon()
    }

    private fun reload() {
        lastSnapshot = InputStats.read(currentSchema) { it }
        renderSchemas()
        renderAll()
    }

    private fun onOptionSelected(id: Int) {
        when (id) {
            R.id.action_share -> showShareDialog()

            R.id.action_clear_all ->
                confirm(getString(R.string.stats_clear_all_confirm)) {
                    InputStats.clearPeriodStats(currentSchema)
                }

            R.id.action_clear_fastest ->
                confirm(getString(R.string.stats_clear_fastest_confirm)) {
                    InputStats.clearFastest(currentSchema)
                }
        }
        reload()
    }

    private fun confirm(message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stats_clear)
            .setMessage(message)
            .setPositiveButton(R.string.ok) { _, _ -> action() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ----------------------------------------------------------------
    // scheme selector
    // ----------------------------------------------------------------

    private fun renderSchemas() {
        val schemas = InputStats.schemaList()
        currentSchemaView.text = getString(R.string.stats_current_schema, schemaDisplayName(schemas, currentSchema))
        schemaGroup.removeAllViews()
        schemas.forEach { (id, name) ->
            val chip =
                Chip(this).apply {
                    text = name
                    isCheckable = true
                    isChecked = id == currentSchema
                    isCloseIconVisible = false
                    setOnClickListener {
                        currentSchema = id
                        InputStats.activeSchemaId = id
                        reload()
                    }
                }
            schemaGroup.addView(chip)
        }
        schemaGroup.visibility = if (schemas.size > 1) View.VISIBLE else View.GONE
    }

    private fun schemaDisplayName(
        schemas: List<Pair<String, String>>,
        id: String,
    ): String = schemas.firstOrNull { it.first == id }?.second ?: id

    // ----------------------------------------------------------------
    // rendering
    // ----------------------------------------------------------------

    private fun renderAll() {
        val snapshot = lastSnapshot ?: return

        // career card
        val career = InputStats.careerLevel(snapshot.careerTotal)
        realmView.text = InputStats.realm(snapshot.careerTotal)
        careerLevelView.text = "Lv.${career.lv}"
        careerTitleView.text = career.title
        expBar.max = career.expMax.coerceAtLeast(1).toInt()
        expBar.progress = career.expCur.coerceAtLeast(0).toInt()
        expText.text = getString(R.string.stats_exp_format, career.expCur, career.expMax)
        totalCharsView.text = snapshot.careerTotal.toString()

        val daily = snapshot.daily
        val (wordRatio, _) = distributionOf(daily)
        val errCntDaily = daily.keyTouchCnt - daily.codeLenNoSpace
        val errRatioDaily = if (daily.keyTouchCnt > 0) 100.0 * errCntDaily / daily.keyTouchCnt else 0.0
        todayStateViewText(
            InputStats.todayState(
                daily.avgSpd,
                errRatioDaily,
                wordRatio.getOrElse(2) { 0.0 },
                daily.fastest.spd,
            ),
        )
        attrSpeedView.text = InputStats.attrSpeed(daily.avgSpd)
        attrAccuracyView.text = InputStats.attrAccuracy(errRatioDaily)

        renderPeriod()
        renderHeat()
    }

    /** 今日状态挂在方案行下方，随 renderAll 更新。 */
    private fun todayStateViewText(state: String) {
        currentSchemaView.text =
            getString(
                R.string.stats_current_schema,
                schemaDisplayName(InputStats.schemaList(), currentSchema),
            ) + "  ·  " + state
    }

    private fun periodOf(snapshot: Stats): PeriodStats = when (currentPeriod) {
        Period.DAY -> snapshot.daily
        Period.WEEK -> snapshot.weekly
        Period.MONTH -> snapshot.monthly
        Period.YEAR -> snapshot.yearly
    }

    private fun renderPeriod() {
        val snapshot = lastSnapshot ?: return
        val period = periodOf(snapshot)

        val periodName = when (currentPeriod) {
            Period.DAY -> getString(R.string.stats_period_day)
            Period.WEEK -> getString(R.string.stats_period_week)
            Period.MONTH -> getString(R.string.stats_period_month)
            Period.YEAR -> getString(R.string.stats_period_year)
        }
        overviewTitle.text = periodName
        val empty = period.count == 0
        commitCountView.text = if (empty) "—" else period.count.toString()
        charCountView.text = if (empty) "—" else period.length.toString()
        keyCountView.text = if (empty) "—" else period.keyTouchCnt.toString()
        val errCnt = (period.keyTouchCnt - period.codeLenNoSpace).coerceAtLeast(0)
        val errRatio = if (period.keyTouchCnt > 0) 100.0 * errCnt / period.keyTouchCnt else 0.0
        errRatioView.text = if (empty) "—" else getString(R.string.stats_percent_format, errRatio)

        val avgCodeLen = period.avgSpeedCodeLen
        val avgKeySpd = period.avgSpd * avgCodeLen / 60.0
        val hasAverage = period.avgSpd > 0.0
        val hasFastest = period.fastest.spd > 0.0
        fastestSpdView.text = if (hasFastest) getString(R.string.stats_speed_format, period.fastest.spd) else "—"
        avgSpdView.text = if (hasAverage) getString(R.string.stats_speed_format, period.avgSpd) else "—"
        fastestDetail.text = if (hasFastest) getString(R.string.stats_speed_detail, period.fastest.keySpd, period.fastest.avgCodeLen) else "—"
        avgDetail.text = if (hasAverage) getString(R.string.stats_speed_detail, avgKeySpd, avgCodeLen) else "—"

        renderDistribution(wordRows, distributionOf(period), R.string.stats_word_one, R.string.stats_word_two, R.string.stats_word_many)
        renderCodeRows(period)
    }

    /** Ratios of single / double / multi char words, plus an "empty" flag. */
    private fun distributionOf(period: PeriodStats): Pair<List<Double>, Boolean> {
        val totalChars =
            period.lengths.entries.sumOf { (k, v) -> (k.toIntOrNull() ?: 0).toLong() * v }
        if (totalChars <= 0) return Pair(listOf(0.0, 0.0, 0.0), true)
        val t = totalChars.toDouble()
        val one = 100.0 * (period.lengths["1"]?.toInt() ?: 0) / t
        val two = 100.0 * (period.lengths["2"]?.toInt() ?: 0) * 2 / t
        val many = (100.0 - one - two).coerceAtLeast(0.0)
        return Pair(listOf(one, two, many), false)
    }

    private fun renderDistribution(
        container: LinearLayout,
        dist: Pair<List<Double>, Boolean>,
        labelOne: Int,
        labelTwo: Int,
        labelMany: Int,
    ) {
        container.removeAllViews()
        val labels = listOf(getString(labelOne), getString(labelTwo), getString(labelMany))
        val colors = listOf(
            androidx.appcompat.R.attr.colorPrimary,
            com.google.android.material.R.attr.colorTertiary,
            com.google.android.material.R.attr.colorSecondary,
        )
        dist.first.forEachIndexed { i, ratio ->
            container.addView(
                progressRow(labels[i], ratio, colors[i]),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        if (dist.second) {
            container.addView(emptyRow())
        }
    }

    private fun renderCodeRows(period: PeriodStats) {
        codeRows.removeAllViews()
        val entries = period.codeLengths.entries.sortedByDescending { it.value }
        if (entries.isEmpty()) {
            codeRows.addView(emptyRow())
            return
        }
        val totalCnt = entries.sumOf { it.value }.coerceAtLeast(1)
        val top = entries.take(3)
        var restRatio = 100.0
        val colors = listOf(
            androidx.appcompat.R.attr.colorPrimary,
            com.google.android.material.R.attr.colorTertiary,
            com.google.android.material.R.attr.colorSecondary,
        )
        top.forEachIndexed { i, (len, cnt) ->
            val ratio = 100.0 * cnt / totalCnt
            restRatio -= ratio
            codeRows.addView(
                progressRow(
                    getString(R.string.stats_code_n_keys, len.toIntOrNull() ?: 0),
                    ratio,
                    colors[i],
                ),
            )
        }
        if (restRatio > 0.5) {
            codeRows.addView(progressRow(getString(R.string.stats_code_other), restRatio, com.google.android.material.R.attr.colorOutline))
        }
    }

    // ----------------------------------------------------------------
    // keyboard heatmap (键盘热力)
    // ----------------------------------------------------------------

    private fun renderHeat() {
        heatGrid.removeAllViews()
        val snapshot = lastSnapshot ?: return
        val counts = periodOf(snapshot).keyCounts
        // 布局来自输入法实际构建的键盘（recordLayout），与计数同源同形
        val layout = InputStats.lastLayoutGeometry()
        if (layout.isEmpty()) {
            heatGrid.addView(emptyRow())
            return
        }

        val maxCount = (counts.values.maxOrNull() ?: 1L).coerceAtLeast(1L)
        val totalCount = counts.values.sum()
        val extent = layout.flatten().maxOfOrNull { it.x + it.width }?.coerceAtLeast(1f) ?: 1f
        layout.forEach { cells ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val rowHeight = ((cells.maxOfOrNull { it.height } ?: 60f) * 0.7f).toInt().coerceIn(24, 112)
            var cursor = 0f
            cells.forEach { cell ->
                val gap = cell.x - cursor
                if (gap > 0.001f) row.addView(android.view.View(this), LinearLayout.LayoutParams(0, dp(rowHeight), gap))
                row.addView(heatCell(cell.label, counts[cell.label] ?: 0L, totalCount, maxCount),
                    LinearLayout.LayoutParams(0, dp(rowHeight), cell.width.coerceAtLeast(0.001f)).apply {
                        setMargins(dp(1), dp(1), dp(1), dp(1))
                    })
                cursor = cell.x + cell.width
            }
            if (extent - cursor > 0.001f) row.addView(android.view.View(this), LinearLayout.LayoutParams(0, dp(rowHeight), extent - cursor))
            heatGrid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
    }

    private fun heatCell(
        label: String,
        count: Long,
        totalCount: Long,
        maxCount: Long,
    ): TextView {
        // 热度 0..1 → 透明度 0.04..0.9 的主色块；格内显示按键名 + 占总击键的百分比
        val heat = count.toDouble() / maxCount.toDouble()
        val alpha = (0.04 + heat * 0.86).coerceIn(0.04, 0.9)
        val base = MaterialThemeColor.primary(this)
        val color =
            android.graphics.Color.argb(
                (alpha * 255).toInt(),
                android.graphics.Color.red(base),
                android.graphics.Color.green(base),
                android.graphics.Color.blue(base),
            )
        val percent =
            if (totalCount <= 0L || count <= 0L) {
                "0%"
            } else {
                val p = 100.0 * count / totalCount
                if (p < 0.1) "<0.1%" else String.format(Locale.US, if (p >= 10.0) "%.0f%%" else "%.1f%%", p)
            }
        return TextView(this).apply {
            text = if (percent.isEmpty()) label else "$label\n$percent"
            gravity = Gravity.CENTER
            textSize = 11f
            maxLines = 2
            includeFontPadding = false
            setTypeface(typeface, if (heat > 0.66) Typeface.BOLD else Typeface.NORMAL)
            background =
                GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(color)
                }
            setTextColor(
                ContextCompat.getColor(
                    this@InputStatsActivity,
                    if (heat > 0.5) R.color.m3_on_primary else R.color.framework_text_primary,
                ),
            )
        }
    }

    private object MaterialThemeColor {
        fun primary(context: Context): Int =
            com.google.android.material.color.MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, "heat")
    }

    // ----------------------------------------------------------------
    // share (短版/长版报告)
    // ----------------------------------------------------------------

    private fun showShareDialog() {
        lastSnapshot = InputStats.read(currentSchema) { it }
        val styles = arrayOf(getString(R.string.stats_share_short), getString(R.string.stats_share_long), "分享统计图片（含热力图）")
        var choice = 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stats_share)
            .setSingleChoiceItems(styles, 0) { _, which -> choice = which }
            .setPositiveButton(R.string.share) { _, _ ->
                if (choice == 2) shareReportImages() else dispatchReport(buildReport(choice == 1), share = true)
            }
            .setNeutralButton(R.string.stats_copy) { _, _ -> dispatchReport(buildReport(choice != 0), share = false) }
            .show()
    }

    private fun shareReportImages() {
        val scroll = findViewById<androidx.core.widget.NestedScrollView>(R.id.stats_scroll)
        StatsImageShare.share(this, scroll.getChildAt(0))
    }

    private fun dispatchReport(
        text: String,
        share: Boolean,
    ) {
        if (share) {
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
            startActivity(Intent.createChooser(intent, getString(R.string.stats_share)))
        } else {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.input_stats), text))
            Toast.makeText(this, R.string.stats_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun textBar(
        label: String,
        ratio: Double,
    ): String {
        val n = (ratio / 10 + 0.5).toInt().coerceIn(0, 10)
        return "█".repeat(n) + "░".repeat(10 - n) + label + (ratio + 0.5).toInt() + "%"
    }

    private fun codeDistributionRows(period: PeriodStats): List<Pair<String, Double>> {
        val entries = period.codeLengths.entries.sortedByDescending { it.value }
        if (entries.isEmpty()) return emptyList()
        val totalCnt = entries.sumOf { it.value }.coerceAtLeast(1)
        val rows = entries.take(3).map { (len, cnt) ->
            getString(R.string.stats_code_n_keys, len.toIntOrNull() ?: 0) to 100.0 * cnt / totalCnt
        }.toMutableList()
        val rest = 100.0 - rows.sumOf { it.second }
        if (rest > 0.5) rows.add(getString(R.string.stats_code_other) to rest)
        return rows
    }

    private fun buildReport(long: Boolean): String {
        val snapshot = lastSnapshot ?: return getString(R.string.stats_no_data)
        val period = periodOf(snapshot)
        val periodLabel =
            when (currentPeriod) {
                Period.DAY -> getString(R.string.stats_period_day)
                Period.WEEK -> getString(R.string.stats_period_week)
                Period.MONTH -> getString(R.string.stats_period_month)
                Period.YEAR -> getString(R.string.stats_period_year)
            }
        if (period.count == 0) return getString(R.string.stats_report_empty, periodLabel)

        val (wordRatio, _) = distributionOf(period)
        val errCnt = (period.keyTouchCnt - period.codeLenNoSpace).coerceAtLeast(0)
        val errRatio = if (period.keyTouchCnt > 0) 100.0 * errCnt / period.keyTouchCnt else 0.0
        val avgCodeLen = period.avgSpeedCodeLen
        val avgKeySpd = period.avgSpd * avgCodeLen / 60.0
        val fastest = period.fastest
        val displayFastestSpd = fastest.spd
        val state = InputStats.todayState(period.avgSpd, errRatio, wordRatio.getOrElse(2) { 0.0 }, displayFastestSpd)
        val career = InputStats.careerLevel(snapshot.careerTotal)
        val rankShort = career.title.substringAfter(' ')

        val lines = mutableListOf<String>()
        lines.add(getString(R.string.stats_report_title, periodLabel))
        lines.add(getString(R.string.stats_report_commits, period.count, period.length))
        if (long) {
            lines.add("📅 " + SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date()))
        }
        val displayFastestKey = fastest.keySpd
        val displayFastestCl = fastest.avgCodeLen
        lines.add(getString(R.string.stats_report_speed, "⚡", displayFastestSpd, displayFastestKey, displayFastestCl))
        lines.add(getString(R.string.stats_report_speed, "💫", period.avgSpd, avgKeySpd, avgCodeLen))
        lines.add(getString(R.string.stats_report_err, errCnt, errRatio))
        lines.add(getString(R.string.stats_word_length))
        lines.add(textBar(getString(R.string.stats_word_one), wordRatio.getOrElse(0) { 0.0 }))
        lines.add(textBar(getString(R.string.stats_word_two), wordRatio.getOrElse(1) { 0.0 }))
        lines.add(textBar(getString(R.string.stats_word_many), wordRatio.getOrElse(2) { 0.0 }))
        lines.add(getString(R.string.stats_code_length))
        codeDistributionRows(period).forEach { (label, ratio) -> lines.add(textBar(label, ratio)) }
        lines.add(getString(R.string.stats_report_state, state, rankShort, career.lv))
        lines.add(getString(R.string.stats_report_footer, schemaDisplayName(InputStats.schemaList(), currentSchema)))

        if (long) {
            val expN = (career.expCur.toDouble() / career.expMax.coerceAtLeast(1) * 10 + 0.5).toInt().coerceIn(0, 10)
            lines.add(getString(R.string.stats_report_career, InputStats.realm(snapshot.careerTotal), career.title, career.lv, "█".repeat(expN) + "░".repeat(10 - expN), career.expCur, career.expMax))
            lines.add(
                getString(
                    R.string.stats_report_attrs,
                    InputStats.attrSpeed(period.avgSpd),
                    InputStats.attrAccuracy(errRatio),
                ),
            )
            lines.add(getString(R.string.stats_report_total, snapshot.careerTotal))
        }
        return lines.joinToString("\n")
    }

    // ----------------------------------------------------------------
    // shared row builders
    // ----------------------------------------------------------------

    private fun emptyRow(): TextView =
        TextView(this).apply {
            text = getString(R.string.stats_no_data)
            androidx.core.widget.TextViewCompat.setTextAppearance(this, android.R.style.TextAppearance_Material_Body2)
            setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }

    private fun progressRow(
        label: String,
        ratio: Double,
        colorAttr: Int,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padV = dp(4)
            setPadding(0, padV, 0, padV)

            val head = LinearLayout(this@InputStatsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(
                TextView(this@InputStatsActivity).apply {
                    text = label
                    androidx.core.widget.TextViewCompat.setTextAppearance(this, androidx.appcompat.R.style.TextAppearance_AppCompat_Body1)
                    setTextColor(MaterialColorsCompat.onSurfaceVariant(context))
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            head.addView(
                TextView(this@InputStatsActivity).apply {
                    text = getString(R.string.stats_percent_format, ratio)
                    androidx.core.widget.TextViewCompat.setTextAppearance(this, androidx.appcompat.R.style.TextAppearance_AppCompat_Body1)
                    setTextColor(MaterialColorsCompat.onSurfaceVariant(context))
                },
            )

            addView(head)
            addView(
                LinearProgressIndicator(this@InputStatsActivity).apply {
                    progress = ratio.toInt().coerceIn(0, 100)
                    trackCornerRadius = dp(4)
                    setIndicatorColor(MaterialColorsCompat.themed(context, colorAttr))
                    trackColor = MaterialColorsCompat.themed(context, com.google.android.material.R.attr.colorSurfaceVariant)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply {
                        topMargin = dp(2)
                    }
                },
            )
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private object MaterialColorsCompat {
        fun themed(context: Context, attr: Int): Int = com.google.android.material.color.MaterialColors.getColor(context, attr, "stats")
        fun onSurfaceVariant(context: Context): Int = themed(context, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, InputStatsActivity::class.java)
            if (context !is AppCompatActivity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
