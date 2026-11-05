/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.update

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AppUpdaterTest : FunSpec({
    test("parseVersion extracts numeric components ignoring prefixes and suffixes") {
        AppUpdater.parseVersion("v3.3.8") shouldBe listOf(3, 3, 8)
        AppUpdater.parseVersion("V3.2.5-2") shouldBe listOf(3, 2, 5, 2)
        AppUpdater.parseVersion("3.10.0") shouldBe listOf(3, 10, 0)
    }

    test("isNewer compares component-wise") {
        AppUpdater.isNewer("v3.3.9", "V3.3.8") shouldBe true
        AppUpdater.isNewer("v3.3.0", "V3.3.9") shouldBe false
        AppUpdater.isNewer("v3.3.9", "V3.3.9") shouldBe false
        AppUpdater.isNewer("v3.10.0", "v3.9.9") shouldBe true
        AppUpdater.isNewer("v4.0", "v3.9.9") shouldBe true
    }

    test("missing components count as zero") {
        AppUpdater.isNewer("v3.3", "v3.3.0") shouldBe false
        AppUpdater.isNewer("v3.3.1", "v3.3") shouldBe true
    }
})
