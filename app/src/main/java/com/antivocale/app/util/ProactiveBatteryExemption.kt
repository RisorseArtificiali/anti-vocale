package com.antivocale.app.util

/**
 * TASK-493: whether the battery-exemption card appears PROACTIVELY (no
 * interruption detected yet) on the kill-vulnerable class. The task's own
 * heuristic, both arms: [ActivityManager.isLowRamDevice] (the platform's
 * Android-Go class flag) OR total RAM under 3GB (the task's threshold; a
 * sliver above the flag's reach, kept because the task names it, not from
 * kill-report data). Pure so the decision is unit-testable; null readings
 * (test contexts) mean NO proactive offer (fail-closed: the card still
 * appears the moment a real interruption is recorded).
 */
internal fun proactiveBatteryExemptionOffer(
    isLowRamDevice: Boolean?,
    totalRamBytes: Long?,
): Boolean = isLowRamDevice == true || (totalRamBytes != null && totalRamBytes < 3L * 1024 * 1024 * 1024)
