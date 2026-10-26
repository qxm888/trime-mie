/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice

import com.osfans.trime.ime.voice.builtin.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SpeechFakeStream {
    var samples=0
    var rate=0
    var released=false
    fun acceptWaveform(pcm:FloatArray,sampleRate:Int){samples=pcm.size;rate=sampleRate}
    fun release(){released=true}
}
class SpeechFakeResult(val text:String)
class SpeechFakeRecognizer {
    var decoded=false
    var released=false
    fun createStream()=SpeechFakeStream()
    fun decode(stream:SpeechFakeStream){decoded=stream.samples>0}
    fun getResult(stream:SpeechFakeStream)=SpeechFakeResult(if(decoded && stream.rate==16000) "测试语音" else "")
    fun release(){released=true}
}
class BuiltinSpeechTest:FunSpec({
    test("offline reflection resolves methods by stream class") {
        val native=SpeechFakeRecognizer()
        val recognizer=ReflectiveRecognizer(native,SpeechFakeRecognizer::class.java)
        val stream=recognizer.createStream()
        stream.acceptWaveform(floatArrayOf(0.2f,0.3f),16000)
        recognizer.decode(stream) shouldBe "测试语音"
        stream.release();recognizer.release()
        native.released shouldBe true
        (stream.instance as SpeechFakeStream).released shouldBe true
    }
    test("local vendor families match downloadable models") {
        AsrVendors.LOCAL_ALL.map{it.id}.toSet() shouldBe LocalAsrModels.FAMILIES.map{it.key}.toSet()
        AsrVendors.ALL.map{it.id}.distinct().size shouldBe AsrVendors.ALL.size
    }
    test("all model files specify positive sizes and SHA256") {
        LocalAsrModels.FAMILIES.flatMap{it.variants}.flatMap{it.files}.forEach {
            (it.sizeBytes>0) shouldBe true
            it.sha256.matches(Regex("[a-f0-9]{64}")) shouldBe true
        }
    }
    test("four sources preserve official model asset URL") {
        LocalAsrModels.SOURCES.size shouldBe 4
        val official=LocalAsrModels.zipUrl("sensevoice","small-int8",0)
        official.startsWith("https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/") shouldBe true
        (1..3).forEach { i -> LocalAsrModels.zipUrl("sensevoice","small-int8",i) shouldBe LocalAsrModels.SOURCES[i].second+"/"+official }
    }
})
