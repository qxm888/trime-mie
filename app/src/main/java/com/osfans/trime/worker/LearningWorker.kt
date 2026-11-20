/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
package com.osfans.trime.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.osfans.trime.data.learning.CommitLearningLog
import com.osfans.trime.data.learning.LearningAggregator
import com.osfans.trime.data.learning.SmartLearningSettings
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * 词库自学习的后台批处理：消费上屏日志，聚合词频、导入用户词典、更新分词语料，
 * 然后触发一次 Rime deploy 让导入的词条与调频生效。
 *
 * 用户正在打字时跳过 deploy（导入产物已在 userdb 中，下一轮部署自然生效），
 * 其余聚合步骤都在私有文件上完成，与输入会话互不干扰。
 */
class LearningWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        if (!SmartLearningSettings.learningEnabled) return Result.success()
        return try {
            val result = LearningAggregator.runOnce()
            if (result == null) {
                Timber.i("Learning worker: nothing to aggregate")
                return Result.success()
            }
            if (CommitLearningLog.isRecentlyCommitted()) {
                Timber.i("Learning worker: user active, deploy deferred")
                return Result.success()
            }
            val rime = com.osfans.trime.daemon.RimeDaemon.createSession(javaClass.name)
            try {
                rime.runOnReady { deploy() }
            } finally {
                com.osfans.trime.daemon.RimeDaemon.destroySession(javaClass.name)
            }
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Learning worker failed")
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_LEARNING_KEY = "periodic_smart_learning"

        private val constraints =
            Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

        /** 按学习开关排定或取消周期任务；学习关闭时保持队列为空。 */
        fun start(context: Context) {
            if (!SmartLearningSettings.learningEnabled) {
                if (com.osfans.trime.util.BootStorage.canScheduleWork(context) &&
                    WorkManager.isInitialized()
                ) {
                    WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_LEARNING_KEY)
                }
                return
            }
            if (!com.osfans.trime.util.BootStorage.canScheduleWork(context)) return
            if (!WorkManager.isInitialized()) {
                WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
            }
            val request = PeriodicWorkRequestBuilder<LearningWorker>(
                LEARN_INTERVAL_HOURS,
                TimeUnit.HOURS,
                10,
                TimeUnit.MINUTES,
            ).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_LEARNING_KEY,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Timber.i("LearningWorker scheduled")
        }

        /** 设置页「立即学习」入口：同步跑一轮聚合并部署。 */
        suspend fun runOnceNow(): LearningAggregator.Result? = LearningAggregator.runOnce()

        private const val LEARN_INTERVAL_HOURS = 6L
    }
}
