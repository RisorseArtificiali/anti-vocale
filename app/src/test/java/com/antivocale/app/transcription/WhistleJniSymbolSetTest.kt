package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * TASK-853 review F5: whistle_jni.cpp (arm) and needle_stubs.cpp (x86_64)
 * must export the IDENTICAL JNI symbol set. A symbol in one and not the
 * other compiles clean everywhere and fails only at first call on the
 * stub ABI, where loadLibrary succeeds - invisible to every build check.
 * This pin reads both files and fails the suite on drift.
 */
class WhistleJniSymbolSetTest {

    private fun exportedSymbols(file: File): Set<String> =
        file.readText()
            .split("\n")
            .mapNotNull { line ->
                JNI_SYMBOL.matchEntire(line.trim())?.groupValues?.get(1)
            }
            .toSet()

    @Test
    fun `both bridge sources export the identical JNI symbol set`() {
        val cppDir = File("src/main/cpp")
        val real = exportedSymbols(File(cppDir, "whistle_jni.cpp"))
        val stub = exportedSymbols(File(cppDir, "needle_stubs.cpp"))
        assertEquals(
            "the arm bridge and the x86_64 stub must export the same " +
                "Java_com_antivocale_app_transcription_WhistleNative_* set " +
                "(a missing stub symbol fails only at first call on x86_64)",
            real, stub)
    }

    @Test
    fun `every exported symbol has a Kotlin declaration or is a documented forward`() {
        // The streaming trio is the documented forward: exported now for
        // slice 2, undeclared in Kotlin until then (the seam KDoc owns it).
        val declared = setOf(
            "loadNative", "loadFromFileNative", "isSpeechLoadedNative",
            "transcribeNative", "lastErrorNative")
        val forward = setOf("streamProcessNative", "streamStopNative", "resetNative")
        val kotlin = File("src/main/java/com/antivocale/app/transcription/WhistleNative.kt")
            .readText()
        declared.forEach { name ->
            assertEquals(
                "$name is exported but not declared in WhistleNative (or it " +
                    "moved to the forward set without updating this pin)",
                true, Regex("external fun $name[(:]").containsMatchIn(kotlin))
        }
        val cppDir = File("src/main/cpp")
        val exported = exportedSymbols(File(cppDir, "whistle_jni.cpp"))
        assertEquals(
            "exported set must be exactly declared + documented forward",
            declared + forward, exported)
    }

    private companion object {
        val JNI_SYMBOL = Regex(
            "Java_com_antivocale_app_transcription_WhistleNative_(\\w+)\\(")
    }
}
