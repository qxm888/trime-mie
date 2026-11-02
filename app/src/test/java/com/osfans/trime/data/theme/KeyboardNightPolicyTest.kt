/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KeyboardNightPolicyTest : FunSpec({
    test("explicit system mode wins over a stale configuration") {
        KeyboardNightPolicy.resolve(android.app.UiModeManager.MODE_NIGHT_YES, false) shouldBe true
        KeyboardNightPolicy.resolve(android.app.UiModeManager.MODE_NIGHT_NO, true) shouldBe false
    }
    test("automatic and scheduled modes use the current configuration") {
        KeyboardNightPolicy.resolve(android.app.UiModeManager.MODE_NIGHT_AUTO, true) shouldBe true
        KeyboardNightPolicy.resolve(android.app.UiModeManager.MODE_NIGHT_AUTO, false) shouldBe false
        KeyboardNightPolicy.resolve(android.app.UiModeManager.MODE_NIGHT_CUSTOM, true) shouldBe true
    }
})
