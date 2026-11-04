package tv.frostline.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ProfileStateTest {
    private val pkg = "tv.frostline.app"
    private val odexBytes = "\u007fELF....compiler-filter\u0000speed-profile\u0000x\u0000".toByteArray(Charsets.ISO_8859_1)

    @Test fun aCompiledFilterIsNamedWithItsMethod() {
        assertEquals("compiled (speed-profile) via maps", ProfileState.text(false, true, OdexReading("speed-profile", true, "maps")))
        assertEquals("compiled (speed) via file", ProfileState.text(false, false, OdexReading("speed", true, "file")))
        assertEquals("compiled (speed-profile) via reflect", ProfileState.text(false, true, OdexReading("speed-profile", false, "reflect")))
    }

    @Test fun verifierCompiledWithoutAReadableFilter() {
        assertEquals("compiled (profile) via verifier", ProfileState.text(true, false, OdexReading(null, false, "none")))
    }

    @Test fun anOdexWeCannotReadIsNotAwaiting() {
        assertEquals("odex present, filter unknown via maps", ProfileState.text(false, true, OdexReading(null, true, "maps")))
        assertEquals("unknown", ProfileState.text(false, false, OdexReading(null, false, "none")))
        assertEquals("unknown", ProfileState.text(false, false, null))
    }

    @Test fun awaitingOnlyWhenEnqueuedAndNoOdexExists() {
        assertEquals("awaiting (no odex) via verifier", ProfileState.text(false, true, OdexReading(null, false, "none")))
    }

    @Test fun aFilterThatIsNotCompiledSaysSo() {
        assertEquals("not compiled (verify) via file", ProfileState.text(false, true, OdexReading("verify", true, "file")))
    }

    @Test fun readsTheCompilerFilterFromAnOatKeyValueStore() {
        val bytes = "\u007fELF....dex-file-location\u0000/data/app/base.apk\u0000compiler-filter\u0000speed-profile\u0000concurrent-copying\u0000true\u0000".toByteArray(Charsets.ISO_8859_1)
        assertEquals("speed-profile", ProfileState.filterIn(bytes))
        assertNull(ProfileState.filterIn("nothing here".toByteArray()))
    }

    @Test fun mapsLinesFindThisAppsArtifactsAndNotWebViews() {
        val maps = sequenceOf(
            "7f00000000-7f00100000 r--p 00000000 fd:05 123  /data/app/~~aaa==/com.google.android.webview-bbb==/oat/arm64/base.odex",
            "7f10000000-7f10100000 r-xp 00001000 fd:05 456  /data/app/~~ccc==/tv.frostline.app-ddd==/oat/arm64/base.odex",
            "7f20000000-7f20100000 r--p 00000000 fd:05 789  /data/app/~~ccc==/tv.frostline.app-ddd==/oat/arm64/base.vdex",
            "7f30000000-7f30100000 r--p 00000000 fd:05 790  /data/app/~~ccc==/tv.frostline.app-ddd==/oat/arm64/base.art",
            "7f40 r--p 0 fd:05 1  [anon:dalvik-classes.dex extracted in memory from /data/app/x/base.apk]",
        )
        assertEquals(3, ProfileState.artifactPathsFromMaps(maps, pkg).size)
        assertEquals("/data/app/~~ccc==/tv.frostline.app-ddd==/oat/arm64/base.odex", ProfileState.odexPathFromMaps(maps, pkg))
        assertNull(ProfileState.odexPathFromMaps(sequenceOf("7f10 r--p 0 fd:05 1  [anon:stack]"), pkg))
    }

    @Test fun probeUsesMapsFirstEvenWhenOnlyTheVdexIsMapped() {
        val maps = sequenceOf("7f20 r--p 0 fd:05 789  /data/app/~~c==/tv.frostline.app-d==/oat/arm64/base.vdex")
        val r = ProfileState.probe(pkg, maps, File("/data/app/~~c==/tv.frostline.app-d=="), listOf("arm64"),
            readHeader = { f -> if (f.path.replace('\\', '/').endsWith("/oat/arm64/base.odex")) odexBytes else null })
        assertEquals("speed-profile", r.filter)
        assertEquals("maps", r.method)
    }

    @Test fun probeFallsBackToTheFileBesideTheApk() {
        val parent = File("/data/app/~~c==/tv.frostline.app-d==")
        val r = ProfileState.probe(pkg, emptySequence(), parent, listOf("arm64", "arm"),
            readHeader = { odexBytes }, exists = { it.path.replace('\\', '/').contains("/oat/arm64/base.odex") })
        assertEquals("speed-profile", r.filter)
        assertEquals("file", r.method)
    }

    @Test fun probeFallsBackToReflectionWhenTheHeaderIsUnreadable() {
        val parent = File("/data/app/~~c==/tv.frostline.app-d==")
        val r = ProfileState.probe(pkg, emptySequence(), parent, listOf("arm64"),
            readHeader = { null }, exists = { true }, reflect = { "speed-profile" })
        assertEquals("speed-profile", r.filter)
        assertEquals("reflect", r.method)
        assertEquals(true, r.present)
    }

    @Test fun probeWithNothingIsNoneAndNeverClaimsAwaitingByItself() {
        val r = ProfileState.probe(pkg, emptySequence(), null, listOf("arm64"), readHeader = { null }, exists = { false })
        assertNull(r.filter)
        assertEquals("none", r.method)
        assertEquals("unknown", ProfileState.text(false, false, r))
    }
}
