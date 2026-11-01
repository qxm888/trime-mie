/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.bar

import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode

/** Toolbar hiding is independent of candidate mode; floating keyboards keep it. */
object InputBarVisibility {
    fun candidatesFloating(): Boolean =
        AppPrefs.defaultInstance().candidates.mode.getValue() != PopupCandidatesMode.DISABLED

    fun hidden(preferHidden: Boolean, floatingKeyboard: Boolean =
        com.osfans.trime.ime.keyboard.KeyboardPrefs.run { com.osfans.trime.util.appContext.isFloatingEffective() }) =
        preferHidden && !floatingKeyboard
}
