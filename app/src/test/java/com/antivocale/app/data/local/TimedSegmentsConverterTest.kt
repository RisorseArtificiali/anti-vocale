package com.antivocale.app.data.local

import com.antivocale.app.transcription.TimedSegment
import com.antivocale.app.transcription.TimedToken
import com.antivocale.app.transcription.TimedWord
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

    @Test
    fun `speaker labels round-trip and old rows stay unlabeled`() {
        val labeled = listOf(
            TimedSegment(0, 1000, "prima frase", speaker = 0),
            TimedSegment(1000, 2000, "seconda", speaker = 1),
            TimedSegment(2000, 3000, "terza senza label"),
        )
        assertEquals(labeled, TimedSegmentsConverter.fromJson(TimedSegmentsConverter.toJson(labeled)))
        // Rows written before GH #83 carry no speaker key.
        val legacy = """[{"startMs":0,"endMs":10,"text":"vecchia"}]"""
        assertNull(TimedSegmentsConverter.fromJson(legacy).single().speaker)
    }

    @Test
    fun `speaker names round-trip and old rows stay generic`() {
        // TASK-670 (GH #83): the matched person's name persists beside the
        // cluster id and survives the read; pre-existing rows (with or
        // without a speaker id) read back with no name.
        val named = listOf(
            TimedSegment(0, 1000, "prima", speaker = 0, speakerName = "Alice"),
            TimedSegment(1000, 2000, "seconda", speaker = 1),
        )
        assertEquals(named, TimedSegmentsConverter.fromJson(TimedSegmentsConverter.toJson(named)))
        val legacyNamedId = """[{"startMs":0,"endMs":10,"text":"vecchia","speaker":0}]"""
        assertNull(TimedSegmentsConverter.fromJson(legacyNamedId).single().speakerName)
        val blankName = """[{"startMs":0,"endMs":10,"text":"x","speakerName":""}]"""
        assertNull(TimedSegmentsConverter.fromJson(blankName).single().speakerName)
    }

    @Test
    fun `word timing round-trips and legacy rows stay untimed`() {
        // TASK-928: the flat pair list survives the read on new rows;
        // pre-feature rows carry no key and read back untimed.
        val timed = listOf(
            TimedSegment(0, 2000, "ciao mondo", words = listOf(
                TimedWord(0, 500),
                TimedWord(600, 1900),
            )),
            TimedSegment(2000, 3000, "senza"),
        )
        assertEquals(timed, TimedSegmentsConverter.fromJson(TimedSegmentsConverter.toJson(timed)))
        val legacy = """[{"startMs":0,"endMs":10,"text":"vecchia"}]"""
        assertEquals(emptyList<TimedWord>(),
            TimedSegmentsConverter.fromJson(legacy).single().words)
    }

    @Test
    fun `tokens on the cue derive the persisted word timing`() {
        // TASK-928: the plain (non-diarized) path arrives here with the
        // transient tokens still attached; the serializer derives the words
        // at the write boundary.
        val cue = TimedSegment(
            startMs = 0, endMs = 1800, text = "alpha beta",
            tokens = listOf(
                TimedToken("▁alpha", 0, 800),
                TimedToken("▁beta", 1000, 1800),
            ),
        )
        val decoded = TimedSegmentsConverter.fromJson(TimedSegmentsConverter.toJson(listOf(cue))).single()
        assertEquals(
            listOf(
                TimedWord(0, 800),
                TimedWord(1000, 1800),
            ),
            decoded.words,
        )
        // The transient tokens themselves never serialize (the GH #92 contract).
        assertEquals(emptyList<TimedToken>(), decoded.tokens)
    }

    @Test
    fun `a malformed words array reads back untimed`() {
        // Odd pair count: an end would have to be guessed; drop, don't guess.
        val odd = """[{"startMs":0,"endMs":10,"text":"x","words":[5]}]"""
        assertEquals(emptyList<TimedWord>(),
            TimedSegmentsConverter.fromJson(odd).single().words)
        val inverted = """[{"startMs":0,"endMs":10,"text":"x","words":[500,100]}]"""
        assertEquals(emptyList<TimedWord>(),
            TimedSegmentsConverter.fromJson(inverted).single().words)
    }
}
