package com.antivocale.app.transcription

/**
 * TASK-909 (GH #118): the device-tier axis of the memory math. The
 * free-RAM policy ([TranscriptionMemoryPolicy]) prices ONE decode against
 * what is free RIGHT NOW; a tier prices the DEVICE: a 6 GB phone with a
 * heavy OEM skin and an 8 GB light one read the same free-RAM threshold
 * today, and TASK-631 traced the healthy-phone misfires to exactly that
 * flat band. The tier scales the policy's headroom (how much RAM must
 * stay for the system while a chunk decodes): a constrained device keeps
 * MORE aside, a flagship trusts more of what it reports free.
 *
 * The axis is deliberately minimal and the constants are PLACEHOLDERS
 * pending TASK-631's calibration data (the wife-phone collection); the
 * derivation reads total RAM, core count, and the media performance
 * class, following the vocaphone DeviceProfile precedent (RAM + cores +
 * clock + media class; the clock axis is dropped until calibration says
 * it earns its read).
 *
 * Fail-open is the whole point of the shape: ANY unknown input degrades
 * toward STANDARD and can only widen or narrow a band, never refuse on
 * its own (the refusal still needs the measured free-RAM bar to fail).
 */
enum class DeviceTier(internal val headroomScale: Double) {
    /** Keeps [TranscriptionMemoryPolicy.HEADROOM_MIB] aside, times 1.25. */
    CONSTRAINED(1.25),

    /** The neutral band: today's flat headroom, and the fail-open default. */
    STANDARD(1.0),

    /** Times 0.85. */
    FAST(0.85),

    /** Times 0.7. */
    FLAGSHIP(0.7);

    companion object {
        /**
         * Whether [headroomScale] multiplies the policy headroom YET. The
         * derivation ships now (tested); the SCALES stay inert until
         * TASK-631 collects the calibration data, because placeholder
         * constants that already move shipped caps and refusals are a
         * behavior change ahead of its evidence (code review F1: a 4GB
         * phone's cap dropped 110s to 40s under the placeholders).
         * Flipping this WITH the calibrated constants is part of 631's
         * landing.
         */
        @Volatile
        internal var scalingEnabled: Boolean = false

        /** The multiplier a policy comparison must use: 1.0 while inert. */
        internal fun effectiveScale(tier: DeviceTier): Double =
            if (scalingEnabled) tier.headroomScale else 1.0

        /** RAM bands (bytes) for the base tier; TASK-631-pending. */
        private const val CONSTRAINED_RAM = 4L * 1024 * 1024 * 1024
        private const val FAST_RAM = 8L * 1024 * 1024 * 1024
        private const val FLAGSHIP_RAM = 12L * 1024 * 1024 * 1024

        /** The media performance class a flagship is expected to declare. */
        private const val FLAGSHIP_MEDIA_CLASS = 33

        private fun stepDown(tier: DeviceTier): DeviceTier = when (tier) {
            FLAGSHIP -> FAST
            FAST -> STANDARD
            STANDARD -> CONSTRAINED
            CONSTRAINED -> CONSTRAINED
        }

        /**
         * Pure derivation. Band on total RAM; tighten toward CONSTRAINED
         * when the cores say so; FLAGSHIP additionally requires
         * corroboration (strong cores or a strong media class) and
         * otherwise degrades to FAST. Unknown RAM degrades to STANDARD,
         * the fail-open default; no input combination throws or refuses.
         */
        fun of(
            totalRamBytes: Long?,
            coreCount: Int?,
            mediaPerformanceClass: Int = 0,
        ): DeviceTier {
            if (totalRamBytes == null || totalRamBytes <= 0) {
                // Unknown RAM: the only honest tier is the neutral one.
                return STANDARD
            }
            val base = when {
                totalRamBytes <= CONSTRAINED_RAM -> CONSTRAINED
                totalRamBytes >= FLAGSHIP_RAM -> FLAGSHIP
                totalRamBytes >= FAST_RAM -> FAST
                else -> STANDARD
            }
            val weakCores = coreCount != null && coreCount > 0 && coreCount <= 4
            val strongCores = coreCount != null && coreCount >= 8
            val strongMedia = mediaPerformanceClass >= FLAGSHIP_MEDIA_CLASS
            return when {
                // Weak cores always cost one band: a heavy skin on mid
                // silicon is the misfire class this axis exists for.
                weakCores && base != CONSTRAINED -> stepDown(base)
                // Flagship RAM alone is not flagship: without corroborating
                // cores or media class it reads as FAST.
                base == FLAGSHIP && !strongCores && !strongMedia -> FAST
                // FAST climbs to flagship only when cores AND the media
                // class agree.
                base == FAST && strongCores && strongMedia -> FLAGSHIP
                else -> base
            }
        }
    }
}
