/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.game

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.util.appContext
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlin.random.Random

/** 打字游戏：字符从屏幕上方落下，输入命中的字即消除；关卡提升速度、配额与字池。 */
object TypingGame {
    private const val PREFS = "typing_game"
    private const val DICT_DIR = "typing_game"
    private const val MAX_LEVEL = 50
    private const val LIVES = 3

    private var window: PopupWindow? = null
    private var gameView: GameView? = null
    private var service: android.inputmethodservice.InputMethodService? = null

    val isRunning: Boolean get() = window?.isShowing == true

    fun dictDir(): File = File(DataManager.userDataDir, DICT_DIR).apply { mkdirs() }
    fun dictFiles(): List<File> = dictDir().listFiles().orEmpty()
        .filter { it.isFile && it.extension.lowercase() == "txt" }.sortedBy { it.name }

    /** 由键灵弹出菜单调用：记录服务，区域取迷你键盘上方整屏。 */
    fun attach(service: android.inputmethodservice.InputMethodService, anchor: View) {
        this.service = service
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val dm = anchor.resources.displayMetrics
        pendingWidth = dm.widthPixels
        pendingHeight = (loc[1] - statusBarHeight(anchor)).coerceAtLeast(dm.heightPixels / 3)
        android.util.Log.d("TypingGame", "attach w=$pendingWidth h=$pendingHeight miniTop=${loc[1]}")
    }

    private var pendingWidth = 0
    private var pendingHeight = 0

    fun toggle() {
        if (isRunning) stop() else start()
    }

    fun start() {
        stop()
        val svc = service ?: return
        val anchor = svc.window?.window?.decorView ?: return
        val width = pendingWidth.takeIf { it > 0 } ?: anchor.resources.displayMetrics.widthPixels
        val height = pendingHeight.takeIf { it > 0 } ?: (anchor.height.coerceAtLeast(400))
        val view = GameView(anchor, width, height, statusBarHeight(anchor))
        gameView = view
        window = PopupWindow(view, width, height, false).apply {
            isTouchable = true
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            animationStyle = 0
            setOnDismissListener { android.util.Log.d("TypingGame", "popup dismissed") }
            showAtLocation(anchor, Gravity.TOP or Gravity.START, 0, 0)
        }
        android.util.Log.d("TypingGame", "start w=$width h=$height")
    }

    fun stop() {
        gameView?.stopLoop()
        gameView = null
        window?.dismiss()
        window = null
    }

    fun onCommit(text: String) {
        if (!isRunning) return
        gameView?.post { gameView?.onCommit(text) }
    }

    private fun statusBarHeight(anchor: View): Int {
        val id = anchor.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) anchor.resources.getDimensionPixelSize(id) else 0
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    fun bestLevel(): Int = prefs().getInt("bestLevel", 1)
    fun bestScore(): Int = prefs().getInt("bestScore", 0)
    fun saveRecord(level: Int, score: Int) {
        prefs().edit()
            .putInt("bestLevel", maxOf(bestLevel(), level))
            .putInt("bestScore", maxOf(bestScore(), score))
            .apply()
    }

    @SuppressLint("ViewConstructor")
    class GameView(anchor: View, private val widthPx: Int, private val heightPx: Int, private val statusBarHeight: Int) : View(anchor.context) {
        init {
            android.util.Log.d("TypingGame", "GameView created ${widthPx}x$heightPx")
        }
        override fun onDetachedFromWindow() {
            android.util.Log.d("TypingGame", "GameView detached")
            super.onDetachedFromWindow()
        }
        private val prefs = anchor.context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        private val unlocked get() = prefs.getInt("unlocked", 1).coerceIn(1, MAX_LEVEL)
        private var startLevel = unlocked.coerceAtMost(10)

        private var externalPool: TypingGamePools.Pools? = null
        private var externalName = ""
        private var externalEnabled = prefs.getBoolean("externalEnabled", false)
        private var menuPoolIndex = prefs.getInt("menuPoolIndex", 0)

        private enum class State { MENU, PLAYING, PAUSED, OVER }
        private var state = State.MENU
        private var level = startLevel
        private var levelDone = 0
        private var score = 0
        private var lives = LIVES
        private var banner: String? = null
        private var bannerUntil = 0L

        private data class Item(val text: String, val x: Float, var y: Float, val speed: Float, val size: Float, var typed: Int = 0)
        private val items = mutableListOf<Item>()
        private var lastSpawn = 0L
        private var lastFrame = 0L

        private val bg = Paint().apply { color = Color.argb(216, 12, 16, 24) }
        private val hud = Paint().apply { color = Color.WHITE; textSize = 34f; isAntiAlias = true }
        private val hudSmall = Paint().apply { color = Color.argb(220, 200, 208, 220); textSize = 26f; isAntiAlias = true }
        private val itemPaint = TextPaint().apply { color = Color.WHITE; isAntiAlias = true; textAlign = Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(6f, 0f, 3f, Color.BLACK) }
        private val typedPaint = TextPaint().apply { color = Color.rgb(255, 208, 90); isAntiAlias = true; textAlign = Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(6f, 0f, 3f, Color.BLACK) }
        private val accent = Paint().apply { color = Color.rgb(224, 85, 96); isAntiAlias = true }
        private val buttonPaint = Paint().apply { color = Color.argb(70, 255, 255, 255); isAntiAlias = true }
        private val measure = Paint()

        private val pauseRect =android.graphics.RectF()
        private val exitRect = android.graphics.RectF()
        private val menuRects = mutableListOf<Pair<android.graphics.RectF, String>>()

        init {
            reloadPools()
            updateHudRects()
        }

        private fun reloadPools() {
            val files = TypingGame.dictFiles()
            val useExternal = externalEnabled && files.isNotEmpty()
            externalName = if (useExternal) files[menuPoolIndex.coerceIn(0, files.size - 1)].name else ""
            externalPool = if (useExternal) runCatching { TypingGamePools.fromFile(files[menuPoolIndex.coerceIn(0, files.size - 1)]) }.getOrNull() else null
        }

        private fun pools() = TypingGamePools.forLevel(level, externalPool)

        private fun startPlaying() {
            state = State.PLAYING
            level = startLevel.coerceIn(1, MAX_LEVEL)
            levelDone = 0; score = 0; lives = LIVES
            items.clear(); banner = null; lastSpawn = 0L
        }

        fun stopLoop() {}

        init {
            postInvalidateOnAnimation()
        }

        private fun levelDuration() = (9000L - (level - 1) * 130L).coerceAtLeast(2800L)
        private fun levelQuota() = 8 + 2 * level
        private fun levelConcurrent() = min(4, 1 + (level - 1) / 8)
        private fun spawnInterval() = (1400f - level * 15f).coerceAtLeast(500f)

        private fun spawn() {
            val pools = pools()
            val useWord = pools.words.isNotEmpty() && Random.nextFloat() < (if (level >= 46) 0.4f else 0.3f)
            val useSentence = pools.sentences.isNotEmpty() && level >= 46 && Random.nextFloat() < 0.15f
            val text = when {
                useSentence -> pools.sentences.random()
                useWord -> pools.words.random()
                else -> pools.chars.random()
            }
            itemPaint.textSize = when {
                text.length == 1 -> 64f
                text.length <= 4 -> 44f
                else -> (widthPx - 60f) / text.length * 1.6f
            }.coerceIn(22f, 64f)
            val w = itemPaint.measureText(text)
            val x = Random.nextFloat() * (widthPx - 40f - w) + 20f + w / 2f
            val duration = levelDuration().toFloat() / 1000f
            items.add(Item(text, x, -20f, heightPx.toFloat() / duration, itemPaint.textSize))
        }

        fun onCommit(text: String) {
            if (state != State.PLAYING) return
            text.forEach { ch ->
                val v = ch.code
                if (v !in 0x3400..0x4DBF && v !in 0x4E00..0x9FFF && v !in 0xF900..0xFAFF) return@forEach
                val hit = items.filter { it.typed < it.text.length && it.text[it.typed] == ch }.maxByOrNull { it.y }
                if (hit == null) return@forEach
                hit.typed++
                if (hit.typed >= hit.text.length) {
                    items.remove(hit)
                    score += hit.text.length
                    levelDone += hit.text.length
                    if (levelDone >= levelQuota() && level < MAX_LEVEL) {
                        level++
                        levelDone = 0
                        saveRecord(level, score)
                        banner = "第 ${level - 1} 关完成 → 第 $level 关"
                        bannerUntil = System.currentTimeMillis() + 1600
                    } else if (level >= MAX_LEVEL && levelDone >= levelQuota()) {
                        saveRecord(MAX_LEVEL, score)
                        state = State.OVER
                    }
                }
            }
            invalidate()
        }

        private fun updateHudRects() {
            pauseRect.set(widthPx - 150f, statusBarHeight + 12f, widthPx - 84f, statusBarHeight + 62f)
            exitRect.set(widthPx - 76f, statusBarHeight + 12f, widthPx - 12f, statusBarHeight + 62f)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            updateHudRects()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.TRANSPARENT)
            canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), bg)
            val now = System.currentTimeMillis()
            if (state == State.PLAYING) {
                val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 100L)
                val bottom = heightPx - 8f
                val iterator = items.iterator()
                while (iterator.hasNext()) {
                    val item = iterator.next()
                    item.y += item.speed * dt / 1000f
                    if (item.y - item.size > bottom) {
                        iterator.remove(); lives--; banner = "漏掉一个！"; bannerUntil = now + 700
                        if (lives <= 0) { state = State.OVER; saveRecord(level, score) }
                    }
                }
                if (items.size < levelConcurrent() && now - lastSpawn > spawnInterval()) { spawn(); lastSpawn = now }
            }
            lastFrame = now
            items.forEach { item ->
                itemPaint.textSize = item.size
                val done = item.text.take(item.typed)
                val rest = item.text.drop(item.typed)
                val doneW = if (item.typed > 0) itemPaint.measureText(done) else 0f
                val startX = item.x - itemPaint.measureText(item.text) / 2f
                if (item.typed > 0) {
                    typedPaint.textSize = item.size
                    canvas.drawText(done, startX + doneW / 2f, item.y, typedPaint)
                }
                itemPaint.color = Color.WHITE
                canvas.drawText(rest, startX + doneW + itemPaint.measureText(rest) / 2f, item.y, itemPaint)
            }
            drawHud(canvas, now)
            if (banner != null && now < bannerUntil) {
                canvas.drawRect(0f, heightPx / 2f - 60f, widthPx.toFloat(), heightPx / 2f + 60f, buttonPaint)
                hud.textAlign = Paint.Align.CENTER
                canvas.drawText(banner!!, widthPx / 2f, heightPx / 2f + 12f, hud)
                hud.textAlign = Paint.Align.LEFT
            } else banner = null
            when (state) {
                State.MENU -> drawMenu(canvas)
                State.PAUSED -> drawCenterOverlay(canvas, "已暂停", "点击画面继续")
                State.OVER -> drawCenterOverlay(canvas, "游戏结束 · 第 $level 关", "分数 $score · 点击画面返回菜单")
                else -> {}
            }
            if (state == State.PLAYING) postInvalidateOnAnimation() else postInvalidateDelayed(100)
        }

        private fun drawHud(canvas: Canvas, now: Long) {
            hud.textAlign = Paint.Align.LEFT
            val top = statusBarHeight.toFloat()
            canvas.drawText("第 $level 关  完成 $levelDone/${levelQuota()}  分数 $score", 20f, top + 42f, hud)
            canvas.drawText("生命 ${"♥".repeat(lives.coerceAtLeast(0))}", 20f, top + 78f, hudSmall)
            canvas.drawRoundRect(pauseRect, 10f, 10f, buttonPaint)
            canvas.drawRoundRect(exitRect, 10f, 10f, buttonPaint)
            hud.textAlign = Paint.Align.CENTER
            canvas.drawText(if (state == State.PAUSED) "▶" else "‖", pauseRect.centerX(), pauseRect.centerY() + 12f, hud)
            canvas.drawText("×", exitRect.centerX(), exitRect.centerY() + 12f, hud)
            hud.textAlign = Paint.Align.LEFT
        }

        private fun drawMenu(canvas: Canvas) {
            menuRects.clear()
            val cx = widthPx / 2f
            hud.textAlign = Paint.Align.CENTER
            canvas.drawText("打字游戏", cx, 120f, hud.apply { textSize = 52f })
            hud.textSize = 34f
            var y = 200f
            canvas.drawText("最高关卡 ${prefs.getInt("unlocked", 1)} · 最高分 ${prefs.getInt("bestScore", 0)}", cx, y, hudSmall); y += 70f
            menuRects.add(android.graphics.RectF(60f, y, widthPx - 60f, y + 70f) to "start")
            canvas.drawRoundRect(menuRects.last().first, 14f, 14f, buttonPaint)
            canvas.drawText("开始 · 第 $startLevel 关", cx, y + 47f, hud); y += 110f
            menuRects.add(android.graphics.RectF(60f, y, 240f, y + 70f) to "level-")
            canvas.drawRoundRect(menuRects.last().first, 14f, 14f, buttonPaint); canvas.drawText("◀", 150f, y + 47f, hud)
            menuRects.add(android.graphics.RectF(widthPx - 240f, y, widthPx - 60f, y + 70f) to "level+")
            canvas.drawRoundRect(menuRects.last().first, 14f, 14f, buttonPaint); canvas.drawText("▶", widthPx - 150f, y + 47f, hud)
            canvas.drawText("起始关卡", cx, y + 47f, hudSmall); y += 110f
            val files = TypingGame.dictFiles()
            val poolLabel = when {
                externalPool != null -> "外部字库 · $externalName"
                files.isNotEmpty() -> "默认字库（点此切换）"
                else -> "默认常用字库"
            }
            menuRects.add(android.graphics.RectF(60f, y, widthPx - 60f, y + 70f) to "pool")
            canvas.drawRoundRect(menuRects.last().first, 14f, 14f, buttonPaint)
            canvas.drawText("字库：$poolLabel", cx, y + 47f, hudSmall); y += 110f
            if (files.isNotEmpty()) {
                files.forEachIndexed { index, file ->
                    menuRects.add(android.graphics.RectF(60f, y, widthPx - 60f, y + 60f) to "dict:$index")
                    canvas.drawText("${if (menuPoolIndex == index && externalEnabled) "▶" else "　"}${file.name}", 80f, y + 40f, hudSmall)
                    y += 70f
                }
                canvas.drawText("把 txt 字库放入 rime/typing_game 目录即可出现在这里", cx, y + 10f, hudSmall); y += 60f
            }
            menuRects.add(android.graphics.RectF(60f, y, widthPx - 60f, y + 70f) to "exit")
            canvas.drawRoundRect(menuRects.last().first, 14f, 14f, buttonPaint)
            canvas.drawText("退出游戏", cx, y + 47f, hud)
            hud.textSize = 34f
            hud.textAlign = Paint.Align.LEFT
        }

        private fun drawCenterOverlay(canvas: Canvas, title: String, sub: String) {
            canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), Paint().apply { color = Color.argb(150, 0, 0, 0) })
            hud.textAlign = Paint.Align.CENTER
            canvas.drawText(title, widthPx / 2f, heightPx / 2f - 20f, hud.apply { textSize = 46f })
            canvas.drawText(sub, widthPx / 2f, heightPx / 2f + 40f, hudSmall)
            hud.apply { textSize = 34f; textAlign = Paint.Align.LEFT }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val x = event.x; val y = event.y
            when (state) {
                State.PLAYING -> {
                    when {
                        pauseRect.contains(x, y) -> state = State.PAUSED
                        exitRect.contains(x, y) -> { saveRecord(level, score); TypingGame.stop() }
                    }
                }
                State.PAUSED -> state = State.PLAYING
                State.OVER -> { state = State.MENU; items.clear() }
                State.MENU -> {
                    menuRects.firstOrNull { it.first.contains(x, y) }?.let { (_, tag) ->
                        when (tag) {
                            "start" -> startPlaying()
                            "level-" -> startLevel = (startLevel - 1).coerceIn(1, unlocked)
                            "level+" -> startLevel = (startLevel + 1).coerceIn(1, unlocked)
                            "pool" -> {
                                val files = TypingGame.dictFiles()
                                if (files.isEmpty()) android.widget.Toast.makeText(context, "把 txt 字库放入 rime/typing_game 目录后再选择", android.widget.Toast.LENGTH_SHORT).show()
                                else {
                                    externalEnabled = !externalEnabled
                                    prefs.edit().putBoolean("externalEnabled", externalEnabled).apply()
                                    reloadPools()
                                }
                            }
                            "exit" -> { saveRecord(level, score); TypingGame.stop() }
                            else -> if (tag.startsWith("dict:")) {
                                menuPoolIndex = tag.removePrefix("dict:").toInt()
                                externalEnabled = true
                                prefs.edit().putInt("menuPoolIndex", menuPoolIndex).putBoolean("externalEnabled", true).apply()
                                reloadPools()
                            }
                        }
                        invalidate()
                    }
                }
            }
            return true
        }
    }
}
