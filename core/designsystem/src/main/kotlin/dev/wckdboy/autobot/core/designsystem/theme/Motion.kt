package dev.wckdboy.autobot.core.designsystem.theme

/**
 * Motion tokens ("ink" spec), mirrored from `shared/design/tokens.json` → `motion` and kept in
 * sync by `TokensParityTest`. Durations are milliseconds; [registerOffset] and [enterRise] are dp.
 */
object Motion {
    const val statusDotPulseMillis = 700
    const val statusDotPulseMinAlpha = 0.25f
    const val frameIntervalMillis = 33
    const val lowPowerFrameIntervalMillis = 66
    const val flowCyclesPerSecond = 0.18f
    const val flowMinFill = 0.35f
    const val developMillis = 900
    const val developStagger = 0.5f
    const val printMillis = 900
    const val printMergeScale = 1.7f
    const val registerOffset = 3f
    const val registerMillis = 420
    const val stampMillis = 260
    const val stampScale = 1.35f
    const val stampRotationDegrees = -6f
    const val enterMillis = 180
    const val enterRise = 4f
    const val progressMillis = 240
    const val reducedMotionMillis = 150
    const val ambientFrameIntervalMillis = 100
    const val ambientCycleSeconds = 48
    const val ambientOpacity = 0.10f
    const val ambientBreathSeconds = 8
    const val ambientIdleStopSeconds = 60
    const val loaderCycleMillis = 1100
    const val transitionMillis = 240
    const val staggerMillis = 35
    const val dissolveMillis = 200
    const val indeterminateSweepMillis = 1400
    const val barDotPitch = 1.6f
    const val barEmptyDotScale = 0.15f
    const val barFillStartScale = 0.45f
    const val barWaveSlots = 7
    const val barRunningWaveAmplitude = 0.10f
    const val cardDotPitch = 11
    const val cardDotOpacity = 0.12f
    const val cardWavePeriodSeconds = 12
    const val cardMaxDots = 600

    /** Every token by its tokens.json name (for the parity test). */
    val all: Map<String, Number> = mapOf(
        "statusDotPulseMillis" to statusDotPulseMillis,
        "statusDotPulseMinAlpha" to statusDotPulseMinAlpha,
        "frameIntervalMillis" to frameIntervalMillis,
        "lowPowerFrameIntervalMillis" to lowPowerFrameIntervalMillis,
        "flowCyclesPerSecond" to flowCyclesPerSecond,
        "flowMinFill" to flowMinFill,
        "developMillis" to developMillis,
        "developStagger" to developStagger,
        "printMillis" to printMillis,
        "printMergeScale" to printMergeScale,
        "registerOffset" to registerOffset,
        "registerMillis" to registerMillis,
        "stampMillis" to stampMillis,
        "stampScale" to stampScale,
        "stampRotationDegrees" to stampRotationDegrees,
        "enterMillis" to enterMillis,
        "enterRise" to enterRise,
        "progressMillis" to progressMillis,
        "reducedMotionMillis" to reducedMotionMillis,
        "ambientFrameIntervalMillis" to ambientFrameIntervalMillis,
        "ambientCycleSeconds" to ambientCycleSeconds,
        "ambientOpacity" to ambientOpacity,
        "ambientBreathSeconds" to ambientBreathSeconds,
        "ambientIdleStopSeconds" to ambientIdleStopSeconds,
        "loaderCycleMillis" to loaderCycleMillis,
        "transitionMillis" to transitionMillis,
        "staggerMillis" to staggerMillis,
        "dissolveMillis" to dissolveMillis,
        "indeterminateSweepMillis" to indeterminateSweepMillis,
        "barDotPitch" to barDotPitch,
        "barEmptyDotScale" to barEmptyDotScale,
        "barFillStartScale" to barFillStartScale,
        "barWaveSlots" to barWaveSlots,
        "barRunningWaveAmplitude" to barRunningWaveAmplitude,
        "cardDotPitch" to cardDotPitch,
        "cardDotOpacity" to cardDotOpacity,
        "cardWavePeriodSeconds" to cardWavePeriodSeconds,
        "cardMaxDots" to cardMaxDots,
    )
}
