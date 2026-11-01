package au.com.uulabs.uudownloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import au.com.uulabs.uudownloader.download.BundledEngine
import au.com.uulabs.uudownloader.download.EngineUpdate
import au.com.uulabs.uudownloader.download.YtDlpEngine
import au.com.uulabs.uudownloader.update.AppUpdater
import au.com.uulabs.uudownloader.update.EngineUpdater
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The real update path against yt-dlp's real releases, so it needs the internet. */
@RunWith(AndroidJUnit4::class)
class EngineUpdateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun anOlderEngineIsReplacedByTheVerifiedRelease() {
        val engine = YtDlpEngine.shared(context)
        engine.ensureReady()
        val latest = AppUpdater.latestEngineVersion().getOrNull()
        assumeTrue("no route to GitHub", latest != null)

        // Claim to be old, so the update has something to do.
        val dir = BundledEngine.directory(context)
        File(dir, ".uu-engine").writeText("2020.1.1")

        val update = engine.updateEngine()
        assertTrue("expected an update, got $update", update is EngineUpdate.Updated)
        assertEquals(latest, update.version)
        assertEquals(latest, BundledEngine.installedVersion(context))
        assertEquals(latest, engine.versionOrInit())
        assertTrue(File(dir, "yt-dlp").length() > 1_000_000)
        assertFalse("the old engine was left behind", File(dir, "yt-dlp.previous").exists())
        assertFalse("the download was left behind", File(dir, "yt-dlp.new").exists())

        val again = engine.updateEngine()
        assertTrue("expected already current, got $again", again is EngineUpdate.AlreadyCurrent)
    }

    @Test
    fun anUnknownReleaseFailsWithoutTouchingTheEngine() {
        val target = File(context.cacheDir, "engine-test")
        val failure = runCatching { EngineUpdater.fetchVerified("2000.1.1", target) }.exceptionOrNull()
        assertTrue("a release that does not exist must not succeed", failure != null)
        assertFalse(target.exists())
    }
}
