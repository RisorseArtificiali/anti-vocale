package com.antivocale.app.data.local

import com.antivocale.app.transcription.TimedSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimedSegmentsConverterTest {

    @Test
    fun emptyList_serializesToNull() {
        assertNull(TimedSegmentsConverter.toJson(emptyList()))
    }

    @Test
    fun roundTrip_preservesSegments() {
        val segments = listOf(
            TimedSegment(startMs = 0, endMs = 4_000, text = "ciao mondo"),
            TimedSegment(startMs = 4_000, endMs = 12_500, text = "seconda parte \"citata\","),
        )
        val json = TimedSegmentsConverter.toJson(segments)!!
        assertEquals(segments, TimedSegmentsConverter.fromJson(json))
    }

    @Test
    fun nullAndBlankJson_decodeToEmpty() {
        assertEquals(emptyList<TimedSegment>(), TimedSegmentsConverter.fromJson(null))
        assertEquals(emptyList<TimedSegment>(), TimedSegmentsConverter.fromJson(""))
    }

    @Test
    fun malformedJson_decodeToEmpty() {
        assertEquals(emptyList<TimedSegment>(), TimedSegmentsConverter.fromJson("{not json"))
    }
}
