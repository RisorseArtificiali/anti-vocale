package com.antivocale.app.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TASK-909 (GH #118): the device-tier axis. The derivation is a pure band
 * on RAM with core and media-class adjustments; every unknown input degrades
 * toward STANDARD (the fail-open stance: the tier scales a band, it never
 * refuses on its own).
 */
class DeviceTierTest {

    private val GB = 1024L * 1024 * 1024

    @Test
    fun `the RAM bands derive the base tier`() {
        assertEquals(DeviceTier.CONSTRAINED, DeviceTier.of(3 * GB, 8, 33))
        assertEquals(DeviceTier.STANDARD, DeviceTier.of(6 * GB, 8, 0))
        assertEquals(DeviceTier.FAST, DeviceTier.of(8 * GB, 8, 0))
        assertEquals(DeviceTier.FLAGSHIP, DeviceTier.of(16 * GB, 8, 33))
    }

    @Test
    fun `unknown or absurd RAM is the neutral tier`() {
        assertEquals(DeviceTier.STANDARD, DeviceTier.of(null, 8, 33))
        assertEquals(DeviceTier.STANDARD, DeviceTier.of(0, 8, 33))
        assertEquals(DeviceTier.STANDARD, DeviceTier.of(-1, 8, 33))
    }

    @Test
    fun `weak cores tighten, strong signals only loosen together`() {
        // 8GB of RAM on four cores: FAST drops to STANDARD (a heavy skin on
        // mid silicon is the TASK-631 misfire class).
        assertEquals(DeviceTier.STANDARD, DeviceTier.of(8 * GB, 4, 0))
        // The same step-down one band lower: STANDARD to CONSTRAINED.
        assertEquals(DeviceTier.CONSTRAINED, DeviceTier.of(6 * GB, 4, 0))
        // 12GB alone is not flagship: without strong cores or a strong
        // media class it stays FAST.
        assertEquals(DeviceTier.FAST, DeviceTier.of(12 * GB, 6, 0))
        assertEquals(DeviceTier.FAST, DeviceTier.of(12 * GB, null, 0))
        // 12GB with the media class agreeing is flagship.
        assertEquals(DeviceTier.FLAGSHIP, DeviceTier.of(12 * GB, 6, 33))
        // 8GB with cores AND media class agreeing climbs one band.
        assertEquals(DeviceTier.FLAGSHIP, DeviceTier.of(8 * GB, 8, 33))
        // Unknown cores degrade nothing: the RAM band stands.
        assertEquals(DeviceTier.FAST, DeviceTier.of(8 * GB, null, 0))
    }

    @Test
    fun `the headroom scales are ordered and STANDARD is identity`() {
        assertEquals(1.0, DeviceTier.STANDARD.headroomScale, 0.0)
        assert(DeviceTier.CONSTRAINED.headroomScale > DeviceTier.STANDARD.headroomScale)
        assert(DeviceTier.STANDARD.headroomScale > DeviceTier.FAST.headroomScale)
        assert(DeviceTier.FAST.headroomScale > DeviceTier.FLAGSHIP.headroomScale)
    }

    /**
     * F1 contract: until TASK-631's calibration flips the switch, the tier
     * scales NOTHING - a CONSTRAINED device reads exactly the band the
     * shipped flat headroom gave it. Placeholder constants must not move
     * caps or refusals ahead of the data.
     */
    @Test
    fun `the tier stays neutral until TASK-631 enables the scaling`() {
        val avail = 2_200L * 1024 * 1024
        val model = 400L * 1024 * 1024
        val family = TranscriptionMemoryPolicy.Family.TRANSDUCER
        // While inert every tier serves the same verdict as STANDARD.
        assertEquals(
            TranscriptionMemoryPolicy.canServeMinimumChunk(avail, model, family),
            TranscriptionMemoryPolicy.canServeMinimumChunk(avail, model, family, DeviceTier.CONSTRAINED))
        assertEquals(
            TranscriptionMemoryPolicy.canServeMinimumChunk(avail, model, family),
            TranscriptionMemoryPolicy.canServeMinimumChunk(avail, model, family, DeviceTier.FLAGSHIP))
    }

    /**
     * The mechanism, exercised with the switch forced on (the test-only
     * seam; production flips it WITH the calibrated constants): the same
     * free RAM then serves a minimum transducer decode or refuses it
     * depending only on the tier.
     */
    @Test
    fun `with scaling enabled a constrained tier refuses where a flagship serves`() {
        val previous = DeviceTier.scalingEnabled
        DeviceTier.scalingEnabled = true
        try {
            val avail = 2_200L * 1024 * 1024
            val model = 400L * 1024 * 1024
            val family = TranscriptionMemoryPolicy.Family.TRANSDUCER
            assertEquals(
                false,
                TranscriptionMemoryPolicy.canServeMinimumChunk(avail, model, family, DeviceTier.CONSTRAINED))
            assertEquals(
                true,
                TranscriptionMemoryPolicy.canServeMinimumChunk(avail, model, family, DeviceTier.FLAGSHIP))
        } finally {
            DeviceTier.scalingEnabled = previous
        }
    }
}
