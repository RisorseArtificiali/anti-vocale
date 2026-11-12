package com.antivocale.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TASK-427: the tier boundaries of the pre-download fit hint. The budget is
 * the selection gate's own (2.5x headroom, 1.2GB floor), so the tiers and
 * the gate can never disagree about the DoesNotFit line.
 */
class DeviceCompatibilityFitTest {

    @Test
    fun `budget keeps the headroom factor and the model floor`() {
        assertEquals((640L * 5) / 2, DeviceCompatibility.requiredBudgetMb(640))
        // 100MB model still demands the 1.2GB floor.
        assertEquals(1_200L, DeviceCompatibility.requiredBudgetMb(100))
    }

    @Test
    fun `tiers split at the budget line and a quarter above it`() {
        // 640MB model -> budget 1600MB. Boundaries as literals so a factor
        // change in production cannot silently keep this green.
        // 1599MB: DoesNotFit / 1600MB: Tight / 1999MB: Tight / 2000MB: Fits.
        assertEquals(
            DeviceCompatibility.ModelFit.DoesNotFit,
            DeviceCompatibility.modelFitForRam(1599L * 1024 * 1024, 640))
        assertEquals(
            DeviceCompatibility.ModelFit.Tight,
            DeviceCompatibility.modelFitForRam(1600L * 1024 * 1024, 640))
        assertEquals(
            DeviceCompatibility.ModelFit.Tight,
            DeviceCompatibility.modelFitForRam(1999L * 1024 * 1024, 640))
        assertEquals(
            DeviceCompatibility.ModelFit.Fits,
            DeviceCompatibility.modelFitForRam(2000L * 1024 * 1024, 640))
    }

    @Test
    fun `both unreadable sentinels fail open as null`() {
        assertEquals(null, DeviceCompatibility.modelFitForRam(-1, 640))
        assertEquals(null, DeviceCompatibility.modelFitForRam(Long.MAX_VALUE, 640))
    }
}

/**
 * TASK-996: the device RAM-class bands (the surfacing axis; boundaries as
 * literals so a band change must touch this pin too).
 */
class RamTierTest {

    @Test
    fun `bands split at totalmem-calibrated thresholds`() {
        // TASK-996 review F1: the bands compare REPORTED totalMem, so the
        // thresholds sit below the nominal marks (a 4GB-marketed phone
        // reports ~3.6GiB - the TASK-395 Moto G10 data point; an 8GB one
        // ~7.2-7.6GiB). Literals so a band change must touch this pin.
        val mib = 1024L * 1024
        assertEquals(DeviceCompatibility.RamTier.CONSTRAINED, DeviceCompatibility.ramTierFor(3L * mib * 1024))
        // 4GB-marketed reporting 3.6GiB: STANDARD, not entry.
        assertEquals(DeviceCompatibility.RamTier.STANDARD, DeviceCompatibility.ramTierFor(3600 * mib))
        // 8GB-marketed reporting 7.2-7.6GiB: HIGH, not mid-range.
        assertEquals(DeviceCompatibility.RamTier.HIGH, DeviceCompatibility.ramTierFor(7200 * mib))
        assertEquals(DeviceCompatibility.RamTier.HIGH, DeviceCompatibility.ramTierFor(12L * mib * 1024))
        // The exact boundary literals (3.5GiB / 7GiB).
        assertEquals(DeviceCompatibility.RamTier.CONSTRAINED, DeviceCompatibility.ramTierFor(3583 * mib))
        assertEquals(DeviceCompatibility.RamTier.STANDARD, DeviceCompatibility.ramTierFor(3584 * mib))
        assertEquals(DeviceCompatibility.RamTier.STANDARD, DeviceCompatibility.ramTierFor(7167 * mib))
        assertEquals(DeviceCompatibility.RamTier.HIGH, DeviceCompatibility.ramTierFor(7168 * mib))
    }

    @Test
    fun `unreadable forms fail open as null`() {
        assertNull(DeviceCompatibility.ramTierFor(null))
        assertNull(DeviceCompatibility.ramTierFor(-1))
        assertNull(DeviceCompatibility.ramTierFor(Long.MAX_VALUE))
    }
}
