package au.com.uulabs.uudownloader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import au.com.uulabs.uudownloader.core.Platform
import au.com.uulabs.uudownloader.core.Quality
import au.com.uulabs.uudownloader.data.CookieJar
import au.com.uulabs.uudownloader.download.BundledEngine
import au.com.uulabs.uudownloader.download.DownloadHub
import au.com.uulabs.uudownloader.download.DownloadItem
import au.com.uulabs.uudownloader.download.DownloadService
import au.com.uulabs.uudownloader.download.DownloadState
import au.com.uulabs.uudownloader.download.SavedFiles
import au.com.uulabs.uudownloader.download.YtDlpEngine
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole path on a real Android runtime: service, Python, yt-dlp, and the file in Downloads.
 *
 * tools/device-check.sh runs this after clearing the app's data. The first-run path is exactly
 * what a phone with leftovers from an earlier build never exercises, and that gap has already
 * shipped one release that failed for everybody else.
 */
@RunWith(AndroidJUnit4::class)
class DownloadFlowTest {

    @get:Rule
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: TestServer
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        server = TestServer()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        DownloadHub.items.value.forEach { item ->
            if (item.state.isTerminal) {
                SavedFiles.delete(context, item.savedUri)
                DownloadHub.forget(item.id)
            }
        }
        scenario.close()
        server.stop()
    }

    private fun enqueue(path: String): String =
        DownloadHub.enqueue("${server.base}$path", Platform.OTHER, Quality.NORMAL, siteName = "Test").id.also {
            assertTrue(DownloadService.start(context))
        }

    private fun item(id: String): DownloadItem = DownloadHub.items.value.first { it.id == id }

    private fun finished(id: String, seconds: Int = 180): DownloadItem {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val current = item(id)
            if (current.state.isTerminal) return current
            Thread.sleep(100)
        }
        throw AssertionError("still ${item(id).state} after $seconds s: ${item(id).detail}")
    }

    private fun waitFor(what: String, seconds: Int = 90, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(50)
        }
        throw AssertionError("never saw: $what")
    }

    private fun savedBytes(item: DownloadItem): ByteArray =
        context.contentResolver.openInputStream(Uri.parse(item.savedUri!!))!!.use { it.readBytes() }

    @Test
    fun theEngineStartsFromNothing() {
        val version = YtDlpEngine.shared(context).versionOrInit()
        assertNotNull("the runtime did not come up", version)
        assertEquals(BundledEngine.bundledVersion(context), version)
    }

    @Test
    fun aDirectFileIsSaved() {
        val done = finished(enqueue("/clip.mp4"))
        assertEquals(done.error, DownloadState.DONE, done.state)
        assertArrayEquals(server.clip, savedBytes(done))
        assertEquals(server.clip.size.toLong(), done.sizeBytes)
    }

    @Test
    fun aPageAnsweringAMediaLinkGoesToTheEngineAndSavesTheVideo() {
        val done = finished(enqueue("/mislabelled.mp4"))
        assertEquals(done.errorDetail ?: done.error, DownloadState.DONE, done.state)
        assertArrayEquals(server.clip, savedBytes(done))
    }

    @Test
    fun aPageWithNoVideoIsNeverSavedAsOne() {
        val done = finished(enqueue("/nothing.mp4"))
        assertEquals(DownloadState.FAILED, done.state)
        assertNull(done.savedUri)
    }

    @Test
    fun aConnectionThatEndsEarlyIsAFailure() {
        val done = finished(enqueue("/short.mp4"))
        assertEquals(DownloadState.FAILED, done.state)
        assertNull(done.savedUri)
    }

    @Test
    fun aPageDownloadKeepsTheRealTitle() {
        val done = finished(enqueue("/watch"))
        assertEquals(done.errorDetail ?: done.error, DownloadState.DONE, done.state)
        assertArrayEquals(server.clip, savedBytes(done))
        assertTrue(done.savedName, done.savedName!!.contains("語"))
        assertTrue(done.savedName, done.savedName!!.endsWith(".mp4"))
    }

    @Test
    fun aLinkHoldingSeveralVideosSaysSo() {
        val done = finished(enqueue("/two"))
        assertEquals(done.errorDetail ?: done.error, DownloadState.DONE, done.state)
        assertTrue(done.note, done.note!!.contains("2 videos"))
    }

    @Test
    fun aQueueRunsToTheEnd() {
        val ids = listOf(enqueue("/clip.mp4"), enqueue("/clip2.mp4"), enqueue("/clip3.mp4"))
        ids.forEach { id ->
            val done = finished(id)
            assertEquals(done.error, DownloadState.DONE, done.state)
        }
    }

    @Test
    fun cancellingADirectDownloadSavesNothing() {
        val id = enqueue("/slow.mp4")
        waitFor("the download starting") { item(id).state == DownloadState.DOWNLOADING }
        DownloadService.cancel(context, id)
        val done = finished(id, seconds = 30)
        assertEquals(DownloadState.CANCELLED, done.state)
        assertNull(done.savedUri)
    }

    @Test
    fun cancellingAnEngineDownloadSavesNothing() {
        val id = enqueue("/slow-page")
        waitFor("the engine starting") { item(id).state == DownloadState.DOWNLOADING }
        DownloadService.cancel(context, id)
        val done = finished(id, seconds = 30)
        assertEquals(DownloadState.CANCELLED, done.state)
        assertNull(done.savedUri)
    }

    @Test
    fun aCancelBeforeTheEngineHasStartedIsNotLost() {
        val id = enqueue("/slow-page")
        DownloadService.cancel(context, id)
        val done = finished(id, seconds = 60)
        assertEquals(DownloadState.CANCELLED, done.state)
        assertNull(done.savedUri)
    }

    @Test
    fun deletingAFileIsReportedTruthfully() {
        val done = finished(enqueue("/clip.mp4"))
        val uri = done.savedUri!!
        assertTrue(SavedFiles.exists(context, uri))
        assertTrue(SavedFiles.delete(context, uri))
        assertFalse(SavedFiles.exists(context, uri))
        assertTrue("deleting a file that has gone is still success", SavedFiles.delete(context, uri))
    }

    @Test
    fun theAllowanceForgetsFilesDeletedElsewhere() {
        val before = DownloadHub.retainedBytes()
        val done = finished(enqueue("/clip.mp4"))
        assertEquals(before + server.clip.size, DownloadHub.retainedBytes())
        context.contentResolver.delete(Uri.parse(done.savedUri!!), null, null)
        DownloadHub.reconcile { SavedFiles.exists(context, it.uri) }
        assertEquals(before, DownloadHub.retainedBytes())
    }

    @Test
    fun aRunGetsItsOwnCopyOfTheCookies() {
        val jar = CookieJar(context)
        jar.clear()
        assertNull(jar.runCopy())

        val export = File(context.cacheDir, "export.txt")
        export.writeText(".example.com\tTRUE\t/\tTRUE\t0\tname\tvalue\n")
        assertEquals(1, jar.import(Uri.fromFile(export)).getOrThrow())
        val copy = jar.runCopy()!!
        assertEquals(export.readText(), copy.readText())

        // What a run writes to its copy never reaches the jar, and clearing the jar is final.
        copy.appendText("planted\n")
        val second = jar.runCopy()!!
        assertEquals(export.readText(), second.readText())
        jar.clear()
        assertNull(jar.runCopy())
        copy.delete()
        second.delete()
    }

    @Test
    fun anOversizedCookieFileIsRefusedWithoutBeingKept() {
        val jar = CookieJar(context)
        jar.clear()
        val huge = File(context.cacheDir, "huge.txt")
        huge.outputStream().use { out -> repeat(5) { out.write(ByteArray(1024 * 1024)) } }
        assertTrue(jar.import(Uri.fromFile(huge)).isFailure)
        assertFalse(jar.exists)
        assertTrue(context.noBackupFilesDir.listFiles()!!.none { it.name.endsWith(".importing") })
        huge.delete()
    }

    /**
     * The activity closes itself after taking a share, so it is started the way another app would
     * start it. Reopening from Recents and rotating both hand the same intent back to onCreate.
     */
    @Test
    fun aSharedLinkDownloadsOnce() {
        scenario.close()
        val share = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Watch this ${server.base}/clip.mp4")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val before = DownloadHub.items.value.size

        context.startActivity(share)
        waitFor("the share being taken") { DownloadHub.items.value.size == before + 1 }
        val id = DownloadHub.items.value.first().id
        assertEquals(DownloadState.DONE, finished(id).state)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val reopened = instrumentation.startActivitySync(
            Intent(share).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY),
        )
        instrumentation.waitForIdleSync()
        Thread.sleep(1_000)
        assertEquals("reopening from Recents downloaded again", before + 1, DownloadHub.items.value.size)

        instrumentation.runOnMainSync { reopened.recreate() }
        instrumentation.waitForIdleSync()
        Thread.sleep(1_000)
        assertEquals("rotating downloaded again", before + 1, DownloadHub.items.value.size)

        instrumentation.runOnMainSync { reopened.finishAndRemoveTask() }
    }
}
