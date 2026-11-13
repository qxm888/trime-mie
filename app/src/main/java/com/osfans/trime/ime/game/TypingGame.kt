/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.game

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.util.appContext
import java.io.File
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
            setOnDismissListener { view.stopLoop() }
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
        val active = gameView ?: return
        active.post { if (gameView === active) active.onCommit(text) }
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
            stopLoop()
            super.onDetachedFromWindow()
        }
        private val prefs = anchor.context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        private val unlocked get() = TypingGameProgress.restore(prefs.getInt("unlocked", 1), prefs.getInt("bestLevel", 1))
        private var startLevel = TypingGameProgress.selected(prefs.getInt("selectedLevel", unlocked), unlocked)
        private var levelPage = (startLevel - 1) / 10
        private val files = TypingGame.dictFiles()
        private val effects = TypingGameEffects(context, widthPx, heightPx)
        private val sounds = TypingGameSound(context, prefs.getBoolean("soundEnabled", true))
        private var loopActive = true
        private fun dp(value: Float) = value * resources.displayMetrics.density
        private val menuScale get() = minOf(widthPx / 360f, (heightPx - statusBarHeight) / 426f)
        private val menuLeft get() = (widthPx - 360f * menuScale) / 2
        private val menuTop get() = statusBarHeight + (heightPx - statusBarHeight - 426f * menuScale) / 2

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

        private val items = mutableListOf<FallingWord>()
        private var lastSpawn = 0L
        private var lastFrame = 0L

        private val bg = Paint().apply { color = Color.rgb(18, 24, 35) }
        private val hud = Paint().apply { color = Color.WHITE; textSize = 34f; isAntiAlias = true }
        private val hudSmall = Paint().apply { color = Color.argb(220, 200, 208, 220); textSize = 26f; isAntiAlias = true }
        private val itemPaint = TextPaint().apply { color = Color.WHITE; isAntiAlias = true; textAlign = Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(6f, 0f, 3f, Color.BLACK) }
        private val typedPaint = TextPaint().apply { color = Color.rgb(255, 208, 90); isAntiAlias = true; textAlign = Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(6f, 0f, 3f, Color.BLACK) }
        private val accent = Paint().apply { color = Color.rgb(224, 85, 96); isAntiAlias = true }
        private val buttonPaint = Paint().apply { color = Color.argb(70, 255, 255, 255); isAntiAlias = true }
        private val measure = Paint()

        private val pauseRect =android.graphics.RectF()
        private val exitRect = android.graphics.RectF()
        private val soundRect = android.graphics.RectF()
        private val menuRects = mutableListOf<Pair<android.graphics.RectF, String>>()

        init {
            reloadPools()
            updateHudRects()
        }

        private fun reloadPools() {
            val useExternal = externalEnabled && files.isNotEmpty()
            externalName = if (useExternal) files[menuPoolIndex.coerceIn(0, files.size - 1)].name else ""
            externalPool = if (useExternal) runCatching { TypingGamePools.fromFile(files[menuPoolIndex.coerceIn(0, files.size - 1)]) }.getOrNull() else null
        }

        private fun pools() = TypingGamePools.forLevel(level, externalPool)

        private fun startPlaying() {
            state = State.PLAYING
            startLevel = TypingGameProgress.selected(startLevel, unlocked)
            prefs.edit().putInt("selectedLevel", startLevel).putInt("unlocked", unlocked).apply()
            level = startLevel
            levelDone = 0; score = 0; lives = LIVES
            items.clear(); effects.reset(); banner = null; lastSpawn = 0L; lastFrame = 0L
            invalidate()
        }

        fun stopLoop() { loopActive = false; sounds.release() }

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
                text.length == 1 -> dp(24f)
                text.length <= 4 -> dp(20f)
                else -> (widthPx - dp(32f)) / text.length
            }.coerceIn(dp(11f), dp(24f))
            val w = itemPaint.measureText(text)
            val x = Random.nextFloat() * (widthPx - dp(24f) - w).coerceAtLeast(0f) + dp(12f) + w / 2f
            val duration = levelDuration().toFloat() / 1000f
            val spawnY = statusBarHeight + dp(76f)
            items.add(FallingWord(text, x, spawnY, (effects.floorY - spawnY).coerceAtLeast(dp(40f)) / duration, itemPaint.textSize))
        }

        fun onCommit(text: String) {
            if (state != State.PLAYING) return
            text.forEach { ch ->
                val v = ch.code
                if (v !in 0x3400..0x4DBF && v !in 0x4E00..0x9FFF && v !in 0xF900..0xFAFF) return@forEach
                val hit = items.filter { it.typed < it.text.length && it.text[it.typed] == ch }.maxByOrNull { it.y }
                if (hit == null) return@forEach
                hit.typed++
                effects.fire(hit, hit.typed >= hit.text.length)
                sounds.launch(effects.heartAttacks)
            }
            invalidate()
        }

        private fun destroy(hit: FallingWord) {
            if (!items.remove(hit)) return
            score += hit.text.length; levelDone += hit.text.length
            if (levelDone >= levelQuota()) {
                val reached = TypingGameProgress.cleared(unlocked, level)
                prefs.edit().putInt("unlocked", reached).apply()
                if (level < MAX_LEVEL) {
                    level++; levelDone = 0; saveRecord(level, score)
                    banner = "已解锁第 $level 关"; bannerUntil = android.os.SystemClock.uptimeMillis() + 1400
                } else {
                    saveRecord(MAX_LEVEL, score); state = State.OVER
                    banner = "全部通关！"; bannerUntil = android.os.SystemClock.uptimeMillis() + 1800
                }
            }
        }
        private fun toggleSound() {
            sounds.enabled = !sounds.enabled
            prefs.edit().putBoolean("soundEnabled", sounds.enabled).apply()
            invalidate()
        }
        private fun updateHudRects() {
            val top = statusBarHeight + dp(8f); val size = dp(32f)
            exitRect.set(widthPx - dp(40f), top, widthPx - dp(8f), top + size)
            pauseRect.set(exitRect.left - dp(38f), top, exitRect.left - dp(6f), top + size)
            soundRect.set(pauseRect.left - dp(70f), top, pauseRect.left - dp(6f), top + size)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            updateHudRects()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.TRANSPARENT)
            canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), bg)
            val now = android.os.SystemClock.uptimeMillis()
            if (state == State.PLAYING) {
                val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 100L)
                val bottom = effects.floorY
                val iterator = items.iterator()
                while (iterator.hasNext()) {
                    val item = iterator.next()
                    item.y += item.speed * dt / 1000f
                    if (item.typed < item.text.length && item.y - item.size > bottom) {
                        iterator.remove(); lives--; banner = "漏掉一个！"; bannerUntil = now + 700
                        if (lives <= 0) { state = State.OVER; saveRecord(level, score) }
                    }
                }
                effects.update(dt) { target, finishing ->
                    sounds.impact()
                    if (finishing) destroy(target)
                }
                if (state == State.PLAYING && items.size < levelConcurrent() && now - lastSpawn > spawnInterval()) { spawn(); lastSpawn = now }
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
            if (state != State.MENU) { effects.draw(canvas); drawHud(canvas, now) }
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
            if (loopActive && state == State.PLAYING) postInvalidateOnAnimation()
        }

        private fun drawHud(canvas: Canvas, now: Long) {
            hud.textAlign = Paint.Align.LEFT; hud.textSize = dp(13f)
            hudSmall.textSize = dp(11f)
            val top = statusBarHeight.toFloat()
            canvas.drawText("第 $level 关  $levelDone/${levelQuota()}", dp(10f), top + dp(25f), hud)
            canvas.drawText("分数 $score   ${"♥".repeat(lives.coerceAtLeast(0))}", dp(10f), top + dp(49f), hudSmall)
            for (rect in listOf(pauseRect, exitRect, soundRect)) canvas.drawRoundRect(rect, dp(8f), dp(8f), buttonPaint)
            hud.textAlign = Paint.Align.CENTER; hud.textSize = dp(16f)
            canvas.drawText(if (state == State.PAUSED) "▶" else "‖", pauseRect.centerX(), pauseRect.centerY() + dp(5f), hud)
            canvas.drawText("×", exitRect.centerX(), exitRect.centerY() + dp(5f), hud)
            hud.textSize = dp(11f)
            canvas.drawText(if (sounds.enabled) "音效开" else "音效关", soundRect.centerX(), soundRect.centerY() + dp(4f), hud)
            hud.textAlign = Paint.Align.LEFT; hud.textSize = 34f
        }

        private fun drawMenu(canvas: Canvas) {
            menuRects.clear()
            canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), bg)
            canvas.save(); canvas.translate(menuLeft, menuTop); canvas.scale(menuScale, menuScale)
            hud.textAlign = Paint.Align.CENTER; hudSmall.textAlign = Paint.Align.CENTER
            hud.textSize = 25f; hudSmall.textSize = 12f
            canvas.drawText("键灵打字 · 选关", 180f, 34f, hud)
            canvas.drawText("已解锁 $unlocked / 50  ·  最高分 ${bestScore()}", 180f, 61f, hudSmall)
            fun button(left: Float, top: Float, right: Float, bottom: Float, tag: String, label: String, active: Boolean = true, selected: Boolean = false) {
                val rect = android.graphics.RectF(left, top, right, bottom)
                buttonPaint.color = when { selected -> 0xff3f7488.toInt(); active -> 0x383a7492; else -> 0x186a8298 }
                canvas.drawRoundRect(rect, 9f, 9f, buttonPaint)
                hud.color = if (active) Color.WHITE else 0xff718195.toInt(); hud.textSize = 15f
                canvas.drawText(label, rect.centerX(), rect.centerY() + 5f, hud)
                if (active) menuRects.add(rect to tag)
            }
            button(20f, 82f, 65f, 120f, "page-", "◀", levelPage > 0)
            button(295f, 82f, 340f, 120f, "page+", "▶", levelPage < 4)
            canvas.drawText("第 ${levelPage * 10 + 1}—${levelPage * 10 + 10} 关", 180f, 106f, hudSmall)
            repeat(10) { index ->
                val number = levelPage * 10 + index + 1
                val x = 20f + (index % 5) * 65f; val y = 132f + (index / 5) * 55f
                val open = number <= unlocked
                button(x, y, x + 60f, y + 47f, "level:$number", if (open) "$number" else "$number 锁", open, number == startLevel)
            }
            canvas.drawText("通关即永久解锁下一关，可随时选关重玩", 180f, 254f, hudSmall)
            button(20f, 270f, 340f, 318f, "start", "开始 · 第 $startLevel 关", selected = true)
            val label = if (externalPool != null) "字库：$externalName" else "字库：默认常用字"
            // A single cycling control keeps even long external dictionary lists bounded.
            button(20f, 330f, 340f, 372f, "pool", android.text.TextUtils.ellipsize(label, TextPaint(hud).apply { textSize = 15f }, 290f, android.text.TextUtils.TruncateAt.END).toString())
            button(20f, 384f, 175f, 426f, "sound", if (sounds.enabled) "音效：开" else "音效：关")
            button(185f, 384f, 340f, 426f, "exit", "退出游戏")
            canvas.restore()
            hud.color = Color.WHITE; hud.textSize = 34f; hud.textAlign = Paint.Align.LEFT
            hudSmall.textAlign = Paint.Align.LEFT; buttonPaint.color = Color.argb(70, 255, 255, 255)
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
            if (state == State.PLAYING || state == State.PAUSED) {
                when {
                    soundRect.contains(x, y) -> toggleSound()
                    exitRect.contains(x, y) -> { saveRecord(level, score); TypingGame.stop() }
                    pauseRect.contains(x, y) || state == State.PAUSED -> {
                        state = if (state == State.PAUSED) State.PLAYING else State.PAUSED
                        lastFrame = 0L
                    }
                }
            } else if (state == State.OVER) {
                state = State.MENU; items.clear(); effects.reset(); levelPage = (startLevel - 1) / 10
            } else {
                val mx = (x - menuLeft) / menuScale; val my = (y - menuTop) / menuScale
                menuRects.firstOrNull { it.first.contains(mx, my) }?.let { (_, tag) ->
                    when (tag) {
                        "start" -> startPlaying()
                        "page-" -> levelPage = (levelPage - 1).coerceAtLeast(0)
                        "page+" -> levelPage = (levelPage + 1).coerceAtMost(4)
                        "sound" -> toggleSound()
                        "pool" -> {
                            if (files.isEmpty()) android.widget.Toast.makeText(context, "把 txt 字库放入 rime/typing_game 目录后再选择", android.widget.Toast.LENGTH_SHORT).show()
                            else {
                                if (!externalEnabled) { externalEnabled = true; menuPoolIndex = 0 }
                                else if (menuPoolIndex < files.lastIndex) menuPoolIndex++ else externalEnabled = false
                                prefs.edit().putBoolean("externalEnabled", externalEnabled).putInt("menuPoolIndex", menuPoolIndex).apply()
                                reloadPools()
                            }
                        }
                        "exit" -> TypingGame.stop()
                        else -> if (tag.startsWith("level:")) {
                            startLevel = TypingGameProgress.selected(tag.substringAfter(':').toInt(), unlocked)
                            prefs.edit().putInt("selectedLevel", startLevel).apply()
                        }
                    }
                }
            }
            invalidate()
            return true
        }
    }
}
