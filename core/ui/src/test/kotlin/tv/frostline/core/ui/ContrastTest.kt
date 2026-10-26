package tv.frostline.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val l1 = lum(a)
        val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun lum(c: Color): Double {
        fun ch(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private val surfaces = listOf(FrostColors.Background, FrostColors.Surface, FrostColors.SurfaceAlt, FrostColors.Container, FrostColors.ContainerFocused)

    @Test fun bodyTextIsAaOnEverySurface() {
        for (s in surfaces) {
            assertTrue("OnSurface on $s", ratio(FrostColors.OnSurface, s) >= 4.5)
            assertTrue("Muted on $s", ratio(FrostColors.Muted, s) >= 4.5)
            assertTrue("Lavender on $s", ratio(FrostColors.Lavender, s) >= 4.5)
            assertTrue("Ice on $s", ratio(FrostColors.Ice, s) >= 4.5)
        }
    }

    @Test fun focusRingIsVisibleAgainstSurfaces() {
        for (s in surfaces) assertTrue("Violet ring on $s", ratio(FrostColors.Violet, s) >= 3.0)
    }

    @Test fun buttonTextIsAaOnPrimary() {
        assertTrue(ratio(FrostColors.OnSurface, FrostColors.Primary) >= 4.5)
        assertTrue(ratio(FrostColors.OnSurface, FrostColors.PrimaryFocused) >= 4.5)
    }

    @Test fun liveRedReadsOnDarkAndCarriesDarkText() {
        assertTrue(ratio(FrostColors.Live, FrostColors.Background) >= 4.5)
        assertTrue(ratio(FrostColors.Background, FrostColors.Live) >= 4.5)
    }

    @Test fun brandNameHasBoldTv() {
        val n = brandName()
        assertEquals("frostlineTV", n.text)
        assertEquals(1, n.spanStyles.size)
        assertEquals(9, n.spanStyles[0].start)
    }
}
