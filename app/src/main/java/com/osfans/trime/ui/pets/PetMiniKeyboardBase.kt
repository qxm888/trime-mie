/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import com.osfans.trime.data.pets.PetHouse
import com.osfans.trime.ime.keyboard.Keyboard

/** Contract shared by the existing hopping mini and the eldritch tentacle mini. */
@SuppressLint("ViewConstructor")
abstract class PetMiniKeyboardBase(context: Context) : View(context) {
    var onDrag: ((Float, Float) -> Unit)? = null
    var onDrop: (() -> Unit)? = null
    var onControls: (() -> Unit)? = null
    open val sceneWidth: Float get() = PetMiniScene.WIDTH
    open val sceneHeight: Float get() = PetMiniScene.HEIGHT
    abstract fun bind(value: PetHouse.Pet)
    abstract fun setKeyboard(keyboard: Keyboard?, force: Boolean = false)
    abstract fun press(index: Int)
    abstract fun release(index: Int, cancel: Boolean = false)
    abstract fun pressCode(code: Int, down: Boolean)
    abstract fun playInteraction(interaction: PetInteraction)
    abstract fun clearPresses()
}
