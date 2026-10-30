/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.db.CategoryHelper
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.KeyBindingOverrides
import com.osfans.trime.data.keymap.ToolbarOverrides
import com.osfans.trime.data.stats.InputStats
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.receiver.RimeIntentReceiver
import com.osfans.trime.ui.main.LogActivity
import com.osfans.trime.util.isNightMode
import com.osfans.trime.worker.BackgroundSyncWork
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import splitties.systemservices.userManager
import timber.log.Timber
import java.io.File
import kotlin.system.exitProcess

/**
 * Custom Application class.
 * Application class will only be created once when the app run,
 * so you can init a "global" class here, whose methods serve other
 * classes everywhere.
 */
class TrimeApplication : Application() {
    val coroutineScope = MainScope() + CoroutineName("TrimeApplication")

    private val rimeIntentReceiver = RimeIntentReceiver()

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_UNLOCKED) return
            if (!isDirectBootMode) return
            Timber.d("Device unlocked, app will exit now and restart to normal mode")
            com.osfans.trime.util.DiagnosticLog.event("boot","unlocked_restart")
            try {
                RimeDaemon.getFirstSessionOrNull()?.also { RimeDaemon.stopRime() }
            } catch(error:Exception) { Timber.w(error,"Could not stop locked-boot Rime") }
            finally { exitProcess(0) }
        }
    }

    private fun registerBroadcastReceiver() {
        val intentFilter =
            IntentFilter().apply {
                addAction(RimeIntentReceiver.ACTION_DEPLOY)
                addAction(RimeIntentReceiver.ACTION_SYNC_USER_DATA)
            }
        ContextCompat.registerReceiver(
            this,
            rimeIntentReceiver,
            intentFilter,
            PERMISSION_TEST_INPUT_METHOD,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    var isDirectBootMode = false
        private set

    val directBootAwareContext: Context
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isDirectBootMode) {
            createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }

    val externalFilesDir: File
        get() = with(directBootAwareContext) {
            if (isDirectBootMode) filesDir else getExternalFilesDir(null) ?: filesDir
        }

    override fun onCreate() {
        super.onCreate()
        val process=if(Build.VERSION.SDK_INT>=28) Application.getProcessName() else
            (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).runningAppProcesses.orEmpty().firstOrNull { it.pid==Process.myPid() }?.processName.orEmpty()
        if(process.endsWith(":mie_restore")) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !userManager.isUserUnlocked) {
            isDirectBootMode = true
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        val ctx = directBootAwareContext

        val originalHandler=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { com.osfans.trime.util.CrashJournal.record(ctx,thread,error) }
            Log.e("MieCrash","Uncaught exception in ${thread.name}",error)
            if(BuildConfig.DEBUG) {
                originalHandler?.uncaughtException(thread,error)
                exitProcess(10)
            }
            try {
                val sharedPrefs=PreferenceManager.getDefaultSharedPreferences(ctx)
                val crashTime=System.currentTimeMillis()
                val lastCrashTime=sharedPrefs.getLong("last_crash_time",-1L)
                sharedPrefs.edit(commit=true) { putLong("last_crash_time",crashTime) }
                // Locked boot must not launch settings activities or recursively crash.
                if(!isDirectBootMode && !com.osfans.trime.util.BootStorage.locked(this) && crashTime-lastCrashTime>10_000L) {
                    startActivity(Intent(ctx,LogActivity::class.java).apply {
                        flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        putExtra(LogActivity.FROM_CRASH,true)
                        putExtra(LogActivity.CRASH_STACK_TRACE,error.stackTraceToString().take(MAX_STACKTRACE_SIZE))
                    })
                }
            } catch(secondary:Exception) { Log.e("MieCrash","Could not show crash page",secondary) }
            finally { exitProcess(10) }
        }
        instance = this
        registerActivityLifecycleCallbacks(com.osfans.trime.ui.main.SettingsTestInput.callbacks())
        try {
            if (BuildConfig.DEBUG) {
                Timber.plant(
                    object : Timber.DebugTree() {
                        override fun createStackElementTag(element: StackTraceElement): String = "${super.createStackElementTag(element)}|${element.fileName}:${element.lineNumber}"

                        override fun log(
                            priority: Int,
                            tag: String?,
                            message: String,
                            t: Throwable?,
                        ) {
                            super.log(
                                priority,
                                "[${Thread.currentThread().name}] ${tag?.substringBefore('|')}",
                                "${tag?.substringAfter('|')}] $message",
                                t,
                            )
                        }
                    },
                )
            } else {
                Timber.plant(
                    object : Timber.Tree() {
                        override fun log(
                            priority: Int,
                            tag: String?,
                            message: String,
                            t: Throwable?,
                        ) {
                            if (priority < Log.INFO) return
                            Log.println(priority, "[${Thread.currentThread().name}]", message)
                        }
                    },
                )
            }
            if(!isDirectBootMode) runCatching { com.osfans.trime.data.backup.FullSettingsBackup.applyPending(ctx) }
                .onFailure { Timber.e(it,"Pending settings restore could not complete") }
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
            com.osfans.trime.util.DiagnosticLog.event("app", "start", "version=${BuildConfig.VERSION_NAME} api=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.firstOrNull()} directBoot=$isDirectBootMode deviceProtected=${com.osfans.trime.util.BootStorage.deviceProtected(ctx)}")

            Timber.d("isDirectBootMode=$isDirectBootMode")

            // Freeze the existing one-hand width when first separating it from floating mode.
            if (!sharedPreferences.contains("one_hand_default_width")) {
                val width = sharedPreferences.getInt("floating_keyboard_width", 80).coerceIn(40, 100)
                sharedPreferences.edit().putInt("one_hand_default_width", width)
                    .putInt("one_hand_width", width).apply()
            }
            val appPrefs = AppPrefs.initDefault(sharedPreferences)
            // Enable the newly requested phone landscape policy once on upgrade.
            if (!sharedPreferences.getBoolean("mie14_landscape_policy", false)) {
                sharedPreferences.edit().putBoolean("mie14_landscape_policy", true)
                    .putBoolean(AppPrefs.Keyboard.FLOATING_KEYBOARD_AUTO_LANDSCAPE, true).apply()
            }
            // record last pid for crash logs
            appPrefs.internal.pid.apply {
                val currentPid = Process.myPid()
                lastPid = getValue()
                Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
                setValue(currentPid)
            }
            ClipboardHelper.init(ctx)
            com.osfans.trime.data.sync.ClipboardSync.init(ctx)
            CollectionHelper.init(ctx)
            CategoryHelper.init(ctx)
            InputStats.init(ctx)
            KeyBindingOverrides.init(ctx)
            CustomKeyboards.init(ctx)
            ToolbarOverrides.init(ctx)
            com.osfans.trime.data.theme.FontOverrides.init(ctx)
            // 键盘/字体存储就绪后，把 rime 目录里旧位置的自有文件搬进 .mie 并改写引用路径。
            if (!isDirectBootMode) com.osfans.trime.core.data.MieUserDirectory.migrate(ctx)
            com.osfans.trime.ime.voice.VoiceSettings.init(ctx)
            com.osfans.trime.ime.voice.builtin.AsrSettings.init(ctx)
            com.osfans.trime.ime.voice.builtin.AsrContextHolder.init(ctx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isDirectBootMode) {
                AppPrefs.defaultInstance().syncToDeviceEncryptedStorage()
                ThemeManager.syncToDeviceEncryptedStorage()
            }
            registerBroadcastReceiver()
            startWorkManager()
            com.osfans.trime.util.DiagnosticLog.event("app", "ready")
        } catch (e: Exception) {
            Timber.e(e, "Application initialization failed")
            runCatching { com.osfans.trime.util.CrashJournal.record(ctx,Thread.currentThread(),e,"initialization") }
            com.osfans.trime.util.DiagnosticLog.event("app", "init_failed", "type=${e.javaClass.simpleName} at=${e.stackTrace.firstOrNull()}")
            return
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) com.osfans.trime.data.theme.BitmapAssets.trim()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            ColorManager.onSystemNightModeChange(newConfig.isNightMode())
        } catch (e: Exception) {
            Timber.w(e, "Something wrong on configuration changed")
        }
    }

    private fun startWorkManager() {
        val context=directBootAwareContext
        if(!com.osfans.trime.util.BootStorage.canScheduleWork(context)) {
            com.osfans.trime.util.DiagnosticLog.event("boot","work_deferred","locked=$isDirectBootMode")
            return
        }
        coroutineScope.launch {
            try { BackgroundSyncWork.start(context) }
            catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(error:Exception) {
                Timber.e(error,"Background sync scheduling failed; IME remains available")
                com.osfans.trime.util.DiagnosticLog.event("boot","work_schedule_failed","type=${error.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private var instance: TrimeApplication? = null
        private var lastPid: Int? = null

        fun getInstance() = instance ?: throw IllegalStateException("Trime application is not created!")

        fun getLastPid() = lastPid

        private const val MAX_STACKTRACE_SIZE = 128000

        /**
         * This permission is requested by com.android.shell, makes it possible to start
         * deploy from `adb shell am` command:
         * ```sh
         * adb shell am broadcast -a com.osfans.trime.action.DEPLOY
         * ```
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/packages/Shell/AndroidManifest.xml#67
         *
         * other candidate: android.permission.TEST_INPUT_METHOD requires Android 14
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/packages/Shell/AndroidManifest.xml#628
         */
        const val PERMISSION_TEST_INPUT_METHOD = "android.permission.READ_INPUT_STATE"
    }
}
