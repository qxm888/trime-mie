/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.popup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
class PopupChoicesTest : FunSpec({
    test("all configured choices fit without inserting a trigger or dropping the ninth item") {
        (1..64).forEach { n -> (PopupChoices.rows(n)*PopupChoices.columns(n)>=n) shouldBe true }
        PopupChoices.rows(8) shouldBe 2;PopupChoices.columns(8) shouldBe 4
        PopupChoices.columns(9) shouldBe 5
    }
    test("cell centers map to their own visual row") {
        PopupChoices.rowAt(25f,50,2) shouldBe 1
        PopupChoices.rowAt(75f,50,2) shouldBe 0
        PopupChoices.rowAt(125f,50,3) shouldBe 0
    }
    test("named action labels stay short and emoji graphemes stay intact") {
        PopupChoices.compactActionLabel("部署") shouldBe "部"
        PopupChoices.compactActionLabel("刷新主题") shouldBe "刷"
        listOf("👩🏽‍💻","❤️","🇨🇳","💕").forEach { PopupChoices.compactActionLabel(it+"动作") shouldBe it }
    }
    test("key sequence popups never expose placeholders") {
        fun visible(raw: String) = raw.replace(PopupChoices.BRACED, "")
        visible("[]{Left}") shouldBe "[]"
        visible("\"\"{Left}") shouldBe "\"\""
        visible("«»{Left}") shouldBe "«»"
        visible("(){Left}") shouldBe "()"
        visible("{}{Left}") shouldBe "{}"
        visible("{Left}") shouldBe ""
        visible("，") shouldBe "，"
        visible("😀") shouldBe "😀"
    }
})
