package tv.frostline.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSettingsTest {
    @Test fun defaultsAreChatOnMediumEverythingShown() = runTest {
        val s = SettingsStore(InMemoryPreferencesStore()).chat.first()
        assertEquals(ChatSettings(), s)
        assertTrue(s.enabled && s.show7tv && s.showBttv && s.showFfz)
        assertEquals(false, s.timestamps)
        assertEquals(1, s.widthIndex)
        assertEquals(1, s.fontIndex)
    }

    @Test fun updatesPersistAndClampIndices() = runTest {
        val store = SettingsStore(InMemoryPreferencesStore())
        store.updateChat { it.copy(enabled = false, widthIndex = 9, fontIndex = -3, timestamps = true, showBttv = false) }
        val s = store.chat.first()
        assertEquals(false, s.enabled)
        assertEquals(3, s.widthIndex)
        assertEquals(0, s.fontIndex)
        assertEquals(true, s.timestamps)
        assertEquals(false, s.showBttv)
        assertEquals(true, s.show7tv)
    }

    @Test fun optionListsMatchTheIndexRange() {
        assertEquals(4, ChatOptions.widthFractions.size)
        assertEquals(4, ChatOptions.widthNames.size)
        assertEquals(4, ChatOptions.fontSizesSp.size)
        assertEquals(4, ChatOptions.fontNames.size)
        assertEquals(0, ChatOptions.nextIndex(3, 4))
    }
}
