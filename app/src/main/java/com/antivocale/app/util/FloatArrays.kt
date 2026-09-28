package com.antivocale.app.util

/**
 * TASK-690: the one N-part FloatArray concatenation: one output allocation,
 * parts copied in order (the vararg form adds the parameter array itself).
 * Callers with extra semantics keep their own loops: partial accumulator
 * consumption (AudioPreprocessor.mergeAccumulated), capped range slicing
 * from one source (SpeakerNamer.concatSlices), and the 2-part rolling
 * append (SincStreamResampler.appendToPending, hot path; a vararg call
 * would add an allocation for zero gain).
 */
fun concatFloatArrays(vararg parts: FloatArray): FloatArray =
    concatFloatArrays(parts.asList())

fun concatFloatArrays(parts: List<FloatArray>): FloatArray {
    val out = FloatArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        System.arraycopy(part, 0, out, offset, part.size)
        offset += part.size
    }
    return out
}
