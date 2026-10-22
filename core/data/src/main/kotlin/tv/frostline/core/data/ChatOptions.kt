package tv.frostline.core.data

/** Presets the chat menu and Settings cycle through. Indices are stored in ChatSettings. */
object ChatOptions {
    val widthFractions = listOf(0.22f, 0.28f, 0.36f, 0.45f)
    val widthNames = listOf("Narrow", "Medium", "Wide", "Extra wide")
    val fontSizesSp = listOf(12, 14, 17, 20)
    val fontNames = listOf("Small", "Medium", "Large", "Extra large")

    fun nextIndex(current: Int, size: Int) = (current + 1) % size
}
