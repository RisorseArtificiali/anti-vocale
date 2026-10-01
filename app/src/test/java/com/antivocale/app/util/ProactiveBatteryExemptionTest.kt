package com.antivocale.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK-493: the pure half of the proactive battery-exemption offer. The
 * card appears without a recorded interruption only on the kill-vulnerable
 * class; every null reading fails CLOSED (the post-interruption behavior
 * is unchanged and remains the only trigger when the class is unreadable).
 */
class ProactiveBatteryExemptionTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `the platform low-RAM flag wins when readable`() {
        assertTrue(proactiveBatteryExemptionOffer(isLowRamDevice = true, totalRamBytes = 8 * gb))
        // The flag is authoritative even against a large-RAM reading (an
        // OEM-marked low-RAM device is the kill-vulnerable class).
        assertTrue(proactiveBatteryExemptionOffer(isLowRamDevice = true, totalRamBytes = null))
    }

    @Test
    fun `under 3GB total RAM is the vulnerable band`() {
        assertTrue(proactiveBatteryExemptionOffer(isLowRamDevice = false, totalRamBytes = 2 * gb))
        assertTrue(proactiveBatteryExemptionOffer(isLowRamDevice = null, totalRamBytes = 2 * gb + 1))
    }

    @Test
    fun `a healthy device gets no proactive offer`() {
        assertFalse(proactiveBatteryExemptionOffer(isLowRamDevice = false, totalRamBytes = 8 * gb))
        assertFalse(proactiveBatteryExemptionOffer(isLowRamDevice = false, totalRamBytes = 4 * gb))
    }

    @Test
    fun `unreadable readings fail closed`() {
        // Test contexts and any read failure: no proactive card; the
        // post-interruption trigger still fires for everyone.
        assertFalse(proactiveBatteryExemptionOffer(isLowRamDevice = null, totalRamBytes = null))
    }
}
