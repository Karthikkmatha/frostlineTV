package tv.frostline.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileStateTest {
    @Test fun verifierCompiledWins() {
        assertEquals("compiled", ProfileState.text(verifierCompiled = true, verifierEnqueued = false, oatFilter = "speed-profile"))
    }

    @Test fun compiledByCmdIsSaidSoEvenWhenTheVerifierStillSaysEnqueued() {
        assertEquals("compiled (cmd)", ProfileState.text(false, true, "speed-profile"))
        assertEquals("compiled (cmd)", ProfileState.text(false, false, "speed"))
    }

    @Test fun enqueuedWithNothingCompiledIsAwaiting() {
        assertEquals("awaiting", ProfileState.text(false, true, null))
        assertEquals("awaiting", ProfileState.text(false, true, "verify"))
    }

    @Test fun nothingAtAllIsNone() {
        assertEquals("none", ProfileState.text(false, false, null))
        assertEquals("none", ProfileState.text(false, false, "verify"))
    }

    @Test fun readsTheCompilerFilterFromAnOatKeyValueStore() {
        val bytes = "\u007fELF....dex-file-location\u0000/data/app/base.apk\u0000compiler-filter\u0000speed-profile\u0000concurrent-copying\u0000true\u0000".toByteArray(Charsets.ISO_8859_1)
        assertEquals("speed-profile", ProfileState.filterIn(bytes))
        assertNull(ProfileState.filterIn("nothing here".toByteArray()))
    }
}
