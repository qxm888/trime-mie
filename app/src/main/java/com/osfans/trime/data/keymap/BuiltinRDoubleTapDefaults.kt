/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import kotlinx.serialization.json.*

/** Remove only the retired stock R/date gesture, preserving every other double-tap action. */
internal object BuiltinRDoubleTapDefaults {
    private fun isDate(token: String?) = token?.let { BindingActions.resolve(it)?.command == "date" } == true
    private fun clean(bindings: Map<String,String>) = if(isDate(bindings["doubleClick"])) bindings - "doubleClick" else bindings
    fun upgrade(key: CustomKeyboards.CustomKey): CustomKeyboards.CustomKey {
        if(key.bindings["click"]?.lowercase() != "r" && key.identity != "char:r") return key
        val removed=isDate(key.bindings["doubleClick"]) || isDate(key.setBaseBindings?.get("doubleClick"))
        return key.copy(bindings=clean(key.bindings).toMutableMap(),setBaseBindings=key.setBaseBindings?.let(::clean),
            primaryBaseBindings=key.primaryBaseBindings?.let(::clean),
            setBindingOverrides=key.setBindingOverrides?.let { if(removed) it+"doubleClick" else it },
            primaryBindingOverrides=if(removed) key.primaryBindingOverrides+"doubleClick" else key.primaryBindingOverrides)
    }
    fun rules(bindings: Map<String,Map<String,String>>) = bindings.mapValues { (id,actions) -> if(id=="char:r") clean(actions) else actions }
    fun settings(values: JsonObject): JsonObject {
        val rules=values["setBindings"] as? JsonObject ?: return values
        val r=rules["char:r"] as? JsonObject ?: return values
        if(!isDate((r["doubleClick"] as? JsonPrimitive)?.contentOrNull)) return values
        return JsonObject(values+("setBindings" to JsonObject(rules+("char:r" to JsonObject(r-"doubleClick")))))
    }
}
