/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*

class ThemeSelectionGateTest : FunSpec({
    test("background refresh waits until selected mode has committed") {
        val gate = ThemeSelectionGate()
        val ticket = gate.begin()
        coroutineScope {
            val refresh = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitSelection(); "ready" }
            refresh.isCompleted shouldBe false
            gate.finish(ticket)
            refresh.await() shouldBe "ready"
        }
    }
    test("older completion does not release refresh before newer selection finishes") {
        val gate = ThemeSelectionGate()
        val old = gate.begin(); val latest = gate.begin()
        coroutineScope {
            val refresh = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitSelection() }
            gate.finish(old); yield()
            refresh.isCompleted shouldBe false
            gate.finish(latest)
            refresh.await()
        }
    }
})
