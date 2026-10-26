/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice
import com.osfans.trime.ime.voice.builtin.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
class ModelArchiveTest:FunSpec({
    test("complete model archive accepts expected nested files") {
        val file=Files.createTempFile("model-archive", ".zip").toFile()
        try{
            ZipOutputStream(file.outputStream()).use{out->out.putNextEntry(ZipEntry("model/nested/model.onnx"));out.write(byteArrayOf(1,2,3));out.closeEntry()}
            LocalAsrModels.completeArchive(file,LocalVariantSpec("a","a","a",listOf(LocalModelFileSpec("model.onnx",3,"")))) shouldBe true
            LocalAsrModels.completeArchive(file,LocalVariantSpec("a","a","a",listOf(LocalModelFileSpec("model.onnx",4,"")))) shouldBe false
        }finally{file.delete()}
    }
    test("truncated ZIP is never considered ready to install") {
        val file=Files.createTempFile("model-broken", ".zip").toFile()
        try{file.writeBytes(byteArrayOf(80,75,3,4));LocalAsrModels.completeArchive(file,LocalVariantSpec("a","a","a",listOf(LocalModelFileSpec("model.onnx",3,"")))) shouldBe false}finally{file.delete()}
    }
})
