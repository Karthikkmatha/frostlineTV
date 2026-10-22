package tv.frostline.core.twitch

import org.junit.Assert.assertEquals
import org.junit.Test

class SortTest {
    private fun f(name: String, login: String = name.lowercase()) = FollowedChannel("id-$login", login, name)

    @Test fun sortsCaseInsensitively() {
        val sorted = listOf(f("banana"), f("Apple"), f("cherry"), f("Avocado"), f("BLUEBERRY")).sortedAz()
        assertEquals(listOf("Apple", "Avocado", "banana", "BLUEBERRY", "cherry"), sorted.map { it.name })
    }

    @Test fun fallsBackToLoginWhenNameBlank() {
        val sorted = listOf(f("Zoe"), FollowedChannel("9", "adam", "")).sortedAz()
        assertEquals(listOf("adam", "Zoe"), sorted.map { it.displayName })
    }

    @Test fun isStableForEqualNames() {
        val a = f("same", "one"); val b = f("SAME", "two")
        assertEquals(listOf("one", "two"), listOf(a, b).sortedAz().map { it.login })
    }
}
