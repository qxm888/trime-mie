/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/**
 * The floating keyboard's width is stored as a share, and the share is taken against a reference
 * that survives a rotation. These tests pin that conversion, which is what keeps one setting from
 * meaning "fills a landscape screen" in one orientation and "a sliver" in the other.
 */
class FloatingKeyboardWidthTest :
    BehaviorSpec({
        // The device these came from: 1200x2670 at 520dpi (density 3.25).
        val portraitWindow = 1200
        val landscapeWindow = 2670
        val shortSide = portraitWindow
        val density = 3.25f

        fun physicalWidth(percent: Int, windowWidth: Int): Int = widthPercentForLayout(percent, shortSide, windowWidth) * windowWidth / 100

        Given("a width chosen while the phone is held upright") {
            Then("the keyboard keeps that physical width after a rotation") {
                val portrait = physicalWidth(86, portraitWindow)
                val landscape = physicalWidth(86, landscapeWindow)
                // Without the conversion the landscape width would be 86% of 2670 = 2296px, more
                // than twice the 1032px the user actually asked for. What is left is the rounding
                // of a percentage, so the two land within a few pixels of each other.
                abs(landscape - portrait) shouldBeLessThan 25
                (portrait > 900) shouldBe true
            }

            Then("the share shrinks as the window widens, since the pixels are fixed") {
                // Same pixels, smaller share of a bigger window.
                (
                    widthPercentForLayout(86, shortSide, landscapeWindow) <
                        widthPercentForLayout(86, shortSide, portraitWindow)
                    ) shouldBe true
            }
        }

        Given("a window whose size is not known yet") {
            Then("the keyboard is left full width rather than guessing a share") {
                widthPercentForLayout(86, referenceWidthPx = 0, windowWidthPx = 0) shouldBe 100
                widthPercentForLayout(86, referenceWidthPx = shortSide, windowWidthPx = 0) shouldBe 100
            }
        }

        Given("a width that would come out as a share of zero") {
            Then("it is floored at one percent, because zero collapses the surface") {
                // A reference far wider than the window: the exact share rounds below 1.
                widthPercentForLayout(1, referenceWidthPx = 100, windowWidthPx = 100_000) shouldBe 1
            }
        }

        Given("an absolute minimum width") {
            Then("it becomes a share of the short side, not of the window") {
                // 160dp at 3.25 density is 520px, which is ~43% of the 1200px short side.
                val share = minWidthPercentFor(
                    minWidthDp = FLOATING_KEYBOARD_MIN_WIDTH_DP,
                    referenceWidthPx = shortSide,
                    density = density,
                )
                (share in 40..45) shouldBe true
            }

            Then("it is a share that survives the rotation, so the floor is the same either way") {
                val share = minWidthPercentFor(
                    minWidthDp = FLOATING_KEYBOARD_MIN_WIDTH_DP,
                    referenceWidthPx = shortSide,
                    density = density,
                )
                // 43% of the 1200px short side, i.e. the ~520px the dp minimum asks for (the
                // percentage is what gets stored, so it cannot land on the exact pixel count).
                abs(share * shortSide / 100 - (160 * density).toInt()) shouldBeLessThan 15
            }

            Then("an unknown geometry falls back to the narrowest share there is") {
                minWidthPercentFor(FLOATING_KEYBOARD_MIN_WIDTH_DP, 0, density) shouldBe 1
                minWidthPercentFor(FLOATING_KEYBOARD_MIN_WIDTH_DP, shortSide, 0f) shouldBe 1
            }
        }

        Given("the floor the settings slider can represent") {
            Then("the drag handle never goes below it") {
                // Otherwise a width reached by dragging would be shown as something else in
                // settings, and the two would disagree about what the keyboard's size is.
                (
                    minWidthPercentFor(FLOATING_KEYBOARD_MIN_WIDTH_DP, shortSide, density) >=
                        MIN_FLOATING_KEYBOARD_WIDTH_PERCENT
                    ) shouldBe true
            }
        }

        // Where the card may sit horizontally. The margin is dp(8) = 26px on this device, and the
        // failure this pins is the default width: a card as wide as the screen has no position
        // that keeps both margins, and clamping it to the left one pushed its right edge off the
        // screen (the stored offset was exactly 26px, the margin itself).
        Given("a card that fits inside the margins") {
            val margin = 26f
            val visualWidth = physicalWidth(60, portraitWindow).toFloat()
            val cardLeft = (portraitWindow - visualWidth).toInt() / 2

            Then("a request inside the range is honoured") {
                floatingTranslationX(
                    requestedX = 40f,
                    cardLeft = cardLeft,
                    visualWidth = visualWidth,
                    windowWidth = portraitWindow,
                    margin = margin,
                ) shouldBe 40f
            }

            Then("a drag past either edge stops at the margin") {
                floatingTranslationX(
                    requestedX = -9999f,
                    cardLeft = cardLeft,
                    visualWidth = visualWidth,
                    windowWidth = portraitWindow,
                    margin = margin,
                ) shouldBe margin - cardLeft
                floatingTranslationX(
                    requestedX = 9999f,
                    cardLeft = cardLeft,
                    visualWidth = visualWidth,
                    windowWidth = portraitWindow,
                    margin = margin,
                ) shouldBe (portraitWindow - margin - cardLeft - visualWidth)
            }
        }

        Given("a card as wide as the screen, which is the default width") {
            val margin = 26f
            val visualWidth = portraitWindow.toFloat()

            Then("it sits flush with the left edge instead of hanging off the right one") {
                floatingTranslationX(
                    requestedX = 0f,
                    cardLeft = 0,
                    visualWidth = visualWidth,
                    windowWidth = portraitWindow,
                    margin = margin,
                ) shouldBe 0f
            }

            Then("a stored or dragged offset cannot push it off the screen either") {
                // 26px is what the old clamp produced for itself, and it was persisted that way.
                floatingTranslationX(
                    requestedX = margin,
                    cardLeft = 0,
                    visualWidth = visualWidth,
                    windowWidth = portraitWindow,
                    margin = margin,
                ) shouldBe 0f
            }
        }

        Given("a card that is wider than the window allows for margins but still fits on screen") {
            Then("it is still placed flush, so the right edge stays on screen") {
                val visualWidth = (portraitWindow - 20).toFloat()
                val resolved = floatingTranslationX(
                    requestedX = 0f,
                    cardLeft = 10,
                    visualWidth = visualWidth,
                    windowWidth = portraitWindow,
                    margin = 26f,
                )
                resolved shouldBe 0f
                (resolved + 10 + visualWidth <= portraitWindow) shouldBe true
            }
        }
    })
