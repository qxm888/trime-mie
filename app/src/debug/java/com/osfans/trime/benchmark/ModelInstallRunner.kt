/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.osfans.trime.ui.main.VoiceSettingsActivity
import com.osfans.trime.ime.voice.builtin.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelInstallRunner:Instrumentation() {
    private var sourceOverride = -1
    override fun onCreate(args:Bundle?){super.onCreate(args);sourceOverride=args?.getString("source")?.toIntOrNull()?:-1;start()}
    override fun onStart(){
        val result=Bundle();var activity:Activity?=null
        try{
            val family="qwen3_asr";val variant="qwen3-0.6b-int8"
            val model=LocalAsrModels.family(family)!!.variant(variant)!!
            val cached=java.io.File(targetContext.cacheDir,"asr_model_${family}_${variant}.zip")
            sendStatus(0,Bundle().apply{putString("archive","reuse if present; otherwise download into protected internal directory")})
            val source = if (sourceOverride >= 0) sourceOverride else kotlinx.coroutines.runBlocking {
                LocalAsrModels.SOURCES.indices.map { index -> async(Dispatchers.IO) {
                    index to LocalAsrModels.measureSourceLatency(index,family,variant)
                } }.awaitAll().filter { it.second != null }.minByOrNull { it.second!! }?.first ?: AsrSettings.downloadSource
            }
            sendStatus(0,Bundle().apply{putString("source",LocalAsrModels.SOURCES[source].first)})
            activity=startActivitySync(Intent(targetContext,VoiceSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val status=VoiceSettingsActivity::class.java.getDeclaredField("operationStatus").apply{isAccessible=true}
            val job=VoiceSettingsActivity::class.java.getDeclaredField("downloadJob").apply{isAccessible=true}
            runOnMainSync{
                VoiceSettingsActivity::class.java.getDeclaredMethod("startDownload",String::class.java,String::class.java,Int::class.javaPrimitiveType)
                    .apply{isAccessible=true}.invoke(activity,family,variant,source)
            }
            val phases=linkedSetOf<String>();var finished=false
            repeat(12000){
                if(!finished){
                    var current="";var done=false
                    runOnMainSync{current=status.get(activity).toString();done=job.get(activity)==null}
                    if(phases.add(current))sendStatus(0,Bundle().apply{putString("install_stage",current)})
                    if(done)finished=true else SystemClock.sleep(100)
                }
            }
            check(finished){"Model install timed out"}
            check(LocalAsrModels.variantComplete(targetContext,family,model)){"No complete model after Activity operation: $phases"}
            check(AsrSettings.selectedVendor==family && AsrSettings.localVariant(family)==variant){"Installed model was not selected"}
            check(phases.any{it.contains("解压")} && phases.any{it.contains("校验")}){"Missing visible extraction/verification phases: $phases"}
            result.putString("installation","download/complete archive -> visible extraction/SHA256 -> installed and automatically selected")
            // Exercise real local engine on generated silence, without AudioRecord or speech upload.
            val latch=CountDownLatch(1);var failure:String?=null
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val engine=AsrEngineFactory.create(scope,object:AsrListener{
                override fun onFinal(text:String){latch.countDown()}
                override fun onError(message:String){failure=message;latch.countDown()}
            })!!
            try{
                engine.start();engine.feed(ByteArray(16000));engine.stop()
                check(latch.await(120,TimeUnit.SECONDS)){"Local engine timed out loading/decoding"}
                check(failure==null){failure.orEmpty()}
                result.putString("native_engine","Qwen3 0.6B int8 loads and finishes a generated-silence decode; no microphone")
            }finally{engine.cancel();scope.cancel()}
            result.putString("result","PASS")
        }catch(t:Throwable){result.putString("result","FAIL: "+t.stackTraceToString())}
        finally{activity?.let{runOnMainSync{it.finish()}}}
        finish(Activity.RESULT_OK,result)
    }
}
