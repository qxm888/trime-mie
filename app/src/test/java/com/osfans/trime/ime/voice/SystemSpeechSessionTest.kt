/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice

import com.osfans.trime.ime.voice.builtin.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class FakeSystemBackend : SystemSpeechBackend {
    lateinit var callbacks: AsrListener
    var stops = 0
    var cancels = 0
    var destroys = 0
    var failStart = false
    var failStop = false
    var failCancel = false
    override fun start(listener: AsrListener) { callbacks = listener; if (failStart) error("start") }
    override fun stop() { stops++; if (failStop) error("stop") }
    override fun cancel() { cancels++; if (failCancel) error("cancel") }
    override fun destroy() { destroys++ }
}
private class SpeechEvents : AsrListener {
    val finals = mutableListOf<String>()
    val partials = mutableListOf<String>()
    val errors = mutableListOf<String>()
    var ready = 0
    var ended = 0
    var level = 0f
    override fun onFinal(text: String) { finals += text }
    override fun onPartial(text: String) { partials += text }
    override fun onError(message: String) { errors += message }
    override fun onReady() { ready++ }
    override fun onCaptureEnded() { ended++ }
    override fun onLevel(level: Float) { this.level = level }
}

class SystemSpeechSessionTest : FunSpec({
    test("system provider is local without a downloadable model family") {
        AsrVendors.SYSTEM.local shouldBe true
        AsrVendors.SYSTEM.managedModel shouldBe false
        LocalAsrModels.family(AsrVendors.SYSTEM.id) shouldBe null
        AsrVendors.LOCAL_ALL.all { it.managedModel } shouldBe true
    }
    test("release before readiness waits for one final and destroys exactly once") {
        val backend = FakeSystemBackend(); val events = SpeechEvents()
        val engine = SystemSpeechSession(events) { backend }
        engine.capturesAudio shouldBe true
        engine.start(); engine.stop(); engine.stop()
        backend.callbacks.onReady()
        events.ready shouldBe 0
        backend.stops shouldBe 1
        backend.callbacks.onPartial("临时"); backend.callbacks.onFinal("最终")
        backend.callbacks.onFinal("重复"); backend.callbacks.onError("迟到")
        events.partials shouldBe listOf("临时")
        events.finals shouldBe listOf("最终")
        events.errors shouldBe emptyList()
        engine.isRunning shouldBe false
        engine.cancel(); backend.destroys shouldBe 1
    }
    test("cancel ignores late results and does not affect a later gesture") {
        val first = FakeSystemBackend(); val oldEvents = SpeechEvents()
        val old = SystemSpeechSession(oldEvents) { first }
        old.start(); old.cancel()
        val second = FakeSystemBackend(); val newEvents = SpeechEvents()
        val next = SystemSpeechSession(newEvents) { second }; next.start()
        first.callbacks.onPartial("旧"); first.callbacks.onFinal("旧"); first.callbacks.onReady()
        second.callbacks.onFinal("新")
        oldEvents.finals shouldBe emptyList(); oldEvents.partials shouldBe emptyList()
        oldEvents.ready shouldBe 0
        newEvents.finals shouldBe listOf("新")
        first.cancels shouldBe 1; first.destroys shouldBe 1
    }
    test("system endpoint ends recording once while final result remains pending") {
        val backend = FakeSystemBackend(); val events = SpeechEvents()
        val engine = SystemSpeechSession(events) { backend }; engine.start()
        backend.callbacks.onReady(); backend.callbacks.onLevel(2f)
        events.ready shouldBe 1; events.level shouldBe 1f
        backend.callbacks.onCaptureEnded(); backend.callbacks.onCaptureEnded()
        engine.stop(); backend.stops shouldBe 0
        events.ended shouldBe 1; engine.isRunning shouldBe true
        backend.callbacks.onFinal("完成"); events.finals shouldBe listOf("完成")
    }
    test("creation failure is terminal and does not retry another provider") {
        var attempts = 0; val events = SpeechEvents()
        val engine = SystemSpeechSession(events) { attempts++; error("unavailable") }
        engine.start(); engine.start(); engine.stop()
        attempts shouldBe 1; events.errors.size shouldBe 1
        engine.isRunning shouldBe false
    }
    test("start failure releases partially initialized recognizer") {
        val backend = FakeSystemBackend().apply { failStart = true }; val events = SpeechEvents()
        val engine = SystemSpeechSession(events) { backend }; engine.start()
        backend.destroys shouldBe 1; events.errors.size shouldBe 1
        backend.callbacks.onFinal("迟到"); events.finals shouldBe emptyList()
    }
    test("stop failure and platform error release microphone and report only once") {
        val backend = FakeSystemBackend().apply { failStop = true }; val events = SpeechEvents()
        val engine = SystemSpeechSession(events) { backend }; engine.start(); engine.stop()
        backend.callbacks.onError("重复")
        backend.destroys shouldBe 1; backend.cancels shouldBe 1
        events.errors.size shouldBe 1
    }
    test("destroy still happens if platform cancellation throws") {
        val backend = FakeSystemBackend().apply { failCancel = true }; val events = SpeechEvents()
        val engine = SystemSpeechSession(events) { backend }; engine.start(); engine.cancel()
        backend.destroys shouldBe 1; events.errors shouldBe emptyList()
    }
    test("canceled gesture cannot start recognition later") {
        var attempts = 0
        val engine = SystemSpeechSession(SpeechEvents()) { attempts++; FakeSystemBackend() }
        engine.cancel(); engine.start(); attempts shouldBe 0
    }
})
