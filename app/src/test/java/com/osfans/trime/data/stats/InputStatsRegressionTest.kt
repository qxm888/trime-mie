/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.stats
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.random.Random

class InputStatsRegressionTest : FunSpec({
    test("snapshot mutations cannot change stored statistics") {
        val original = InputStats.Stats().apply { daily.avgGaps += 12.0; daily.avgCnts += 10; daily.avgCodeLens += 25; daily.keyCounts["a"] = 7 }
        val snapshot = InputStats.run { original.snapshot() }
        snapshot.daily.avgCnts[0] = 999
        snapshot.daily.keyCounts["a"] = 999
        snapshot.daily.fastest.spd = 999.0
        original.daily.avgCnts[0] shouldBe 10
        original.daily.keyCounts["a"] shouldBe 7L
        original.daily.fastest.spd shouldBe 0.0
    }
    test("incremental fastest equals the full historical endpoint scan") {
        val update = InputStats::class.java.getDeclaredMethod("updateFastestLocked", InputStats.Stats::class.java).apply { isAccessible = true }
        val stats = InputStats.Stats()
        val random = Random(867)
        var expected = 0.0
        repeat(500) {
            val gap = 1.0 + random.nextDouble() * 18
            val count = random.nextInt(1, 12)
            for (p in listOf(stats.daily, stats.weekly, stats.monthly, stats.yearly)) { p.avgGaps += gap; p.avgCnts += count; p.avgCodeLens += count * 3 }
            var duration = 0.0; var chars = 0
            for (i in stats.daily.avgGaps.lastIndex downTo 0) {
                duration += stats.daily.avgGaps[i]; chars += stats.daily.avgCnts[i]
                if (duration >= 10.0) break
            }
            if (duration >= 10.0) expected = maxOf(expected, chars / duration * 60)
            update.invoke(InputStats, stats)
            (abs(stats.daily.fastest.spd - expected) < 0.000001) shouldBe true
            stats.weekly.fastest.spd shouldBe stats.daily.fastest.spd
        }
    }
    test("weighted average does not average individual segment speeds") {
        val p = InputStats.PeriodStats(avgGaps = mutableListOf(10.0, 50.0), avgCnts = mutableListOf(10, 10), avgCodeLens = mutableListOf(20, 40))
        p.avgSpd shouldBe 20.0
        p.avgSpeedCodeLen shouldBe 3.0
    }
})
