package tv.frostline.core.data

/** Presets the chat menu and Settings cycle through. Indices are stored in ChatSettings. */
object ChatOptions {
    /** Chat panel width as a percentage of the screen width: set by the resize control, 2 % steps. */
    const val MIN_WIDTH_PCT = 20
    const val MAX_WIDTH_PCT = 45
    const val WIDTH_STEP_PCT = 2
    const val DEFAULT_WIDTH_PCT = 28

    /** The old Narrow / Medium / Wide / Extra wide presets, used only to migrate a stored index. */
    private val legacyWidthPct = listOf(22, 28, 36, 45)
    fun fromLegacyIndex(index: Int): Int = legacyWidthPct[index.coerceIn(0, legacyWidthPct.lastIndex)]

    fun clampWidth(pct: Int): Int = pct.coerceIn(MIN_WIDTH_PCT, MAX_WIDTH_PCT)

    /**
     * One key event of the resize control. [dir] +1 makes the panel wider, -1 narrower; [repeat] is the key's
     * repeat count: holding the key speeds up (2 steps after 10 repeats, 4 after 24).
     */
    fun stepWidth(current: Int, dir: Int, repeat: Int = 0): Int {
        val mult = when {
            repeat >= 24 -> 4
            repeat >= 10 -> 2
            else -> 1
        }
        return clampWidth(current + dir * WIDTH_STEP_PCT * mult)
    }

    val fontSizesSp = listOf(12, 14, 17, 20)
    val fontNames = listOf("Small", "Medium", "Large", "Extra large")

    fun nextIndex(current: Int, size: Int) = (current + 1) % size
}
