package io.trimio.engine.motion

/**
 * The BBC Subtitle Guidelines numbers as code: how tall a caption line is, how far it runs, how
 * many lines a format stacks, and how fast a viewer reads them. The planner and the compiler size
 * captions against these bands; where the craft rulebook already owns a number (the holds of
 * [MotionSpec.Time]) it is referenced, never copied.
 */
object CaptionMetrics {

    /**
     * One line's pitch as a share of the frame height: the broadcast band for 16:9/4:3/1:1. A
     * 9:16 stack shows three lines and must pack them tighter to stay clear of the platform's
     * buttons and the lower-third.
     */
    fun lineHeightFraction(isVertical: Boolean): ClosedFloatingPointRange<Float> =
        if (isVertical) 0.039f..0.045f else 0.07f..0.08f

    /** Lines one caption may stack: two in landscape, three in a 9:16. */
    fun maxLines(isVertical: Boolean): Int = if (isVertical) 3 else 2

    /** How much of the frame's width a line may run: 68% of 16:9, 90% of 9:16. */
    fun lineLengthFraction(isVertical: Boolean): Float = if (isVertical) 0.9f else 0.68f

    /** Words per minute a viewer consumes on screen. */
    val wordsPerMinute: ClosedFloatingPointRange<Float> = 160f..180f

    /** A caption may show 10% less than the nominal rate before it counts as unreadable. */
    const val ReadableSlack = 0.9f

    /**
     * Seconds one word owns at the band's midpoint (170 wpm): the two ends are the slow and the
     * fast reader, and one scheduling rate from the middle keeps captions calm for both.
     */
    fun secondsPerWord(): Float = 60f / 170f

    /**
     * Whether [words] words are readable in [durationSec]: the floor of [secondsPerWord] a word,
     * with [ReadableSlack] off it so speech a hair faster than the band still reads. The band is
     * format-independent — 9:16 stacks more, shorter lines ([maxLines]); it does not slow the
     * reader — so [isVertical] names the caption's format without moving the floor.
     */
    @Suppress("UNUSED_PARAMETER")
    fun fits(words: Int, durationSec: Float, isVertical: Boolean): Boolean =
        durationSec >= words * secondsPerWord() * ReadableSlack

    /**
     * How long a line must hold: the craft rulebook's hold ([MotionSpec.Time.holdForLine]) and the
     * BBC reading floor of [fits], whichever is longer. The hold numbers stay in [MotionSpec] so
     * the compiler, the audit and the jury cannot drift apart; this only raises the floor.
     */
    fun holdForLine(words: Int, isLast: Boolean): Float = maxOf(
        MotionSpec.Time.holdForLine(words, isLast),
        words * secondsPerWord() * ReadableSlack,
    )
}
