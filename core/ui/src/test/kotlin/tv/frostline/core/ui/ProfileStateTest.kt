package tv.frostline.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileStateTest {
    @Test fun aCompiledFilterIsNamed() {
        assertEquals("compiled (speed-profile)", ProfileState.text(verifierCompiled = true, verifierEnqueued = false, oatFilter = "speed-profile"))
        assertEquals("compiled (speed-profile)", ProfileState.text(false, true, "speed-profile")) // cmd package compile: the verifier still says enqueued
        assertEquals("compiled (speed)", ProfileState.text(false, false, "speed"))
    }

    @Test fun verifierCompiledWithoutAReadableFilter() {
        assertEquals("compiled (profile)", ProfileState.text(true, false, null))
    }

    @Test fun anOdexMappedIntoTheProcessCountsAsCompiled() {
        assertEquals("compiled (odex mapped)", ProfileState.text(false, true, null, odexMapped = true))
        assertEquals("awaiting", ProfileState.text(false, true, "verify", odexMapped = true))
    }

    @Test fun enqueuedWithNothingCompiledIsAwaiting() {
        assertEquals("awaiting", ProfileState.text(false, true, null))
        assertEquals("awaiting", ProfileState.text(false, true, "verify"))
    }

    @Test fun nothingAtAllIsNone() {
        assertEquals("none", ProfileState.text(false, false, null))
        assertEquals("not compiled (verify)", ProfileState.text(false, false, "verify"))
    }

    @Test fun readsTheCompilerFilterFromAnOatKeyValueStore() {
        val bytes = "\u007fELF....dex-file-location\u0000/data/app/base.apk\u0000compiler-filter\u0000speed-profile\u0000concurrent-copying\u0000true\u0000".toByteArray(Charsets.ISO_8859_1)
        assertEquals("speed-profile", ProfileState.filterIn(bytes))
        assertNull(ProfileState.filterIn("nothing here".toByteArray()))
    }

    @Test fun findsThisAppsOdexInProcMapsAndNotWebViews() {
        val maps = sequenceOf(
            "7f00000000-7f00100000 r--p 00000000 fd:05 123  /data/app/~~aaa==/com.google.android.webview-bbb==/oat/arm64/base.odex",
            "7f10000000-7f10100000 r-xp 00001000 fd:05 456  /data/app/~~ccc==/tv.frostline.app-ddd==/oat/arm64/base.odex",
            "7f20000000-7f20100000 r--p 00000000 fd:05 789  /data/app/~~ccc==/tv.frostline.app-ddd==/oat/arm64/base.vdex",
        )
        assertEquals("/data/app/~~ccc==/tv.frostline.app-ddd==/oat/arm64/base.odex", ProfileState.odexPathFromMaps(maps, "tv.frostline.app"))
        assertNull(ProfileState.odexPathFromMaps(sequenceOf("7f10 r--p 0 fd:05 1  [anon:stack]"), "tv.frostline.app"))
    }
}
