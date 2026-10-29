/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.bar

/** Floating mode temporarily restores its attached bar without rewriting the user's preference. */
object InputBarVisibility {
    fun hidden(preferHidden:Boolean,floating:Boolean,oneHand:Boolean=false) = preferHidden && (!floating || oneHand)
}
