package au.com.uulabs.uudownloader

import au.com.uulabs.uudownloader.core.DirectLinks
import au.com.uulabs.uudownloader.core.FileNames
import au.com.uulabs.uudownloader.core.Platform
import au.com.uulabs.uudownloader.core.Quality
import au.com.uulabs.uudownloader.core.QualityChoice
import au.com.uulabs.uudownloader.core.SocialLinks
import au.com.uulabs.uudownloader.download.ProgressText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the app that can be wrong without a phone in your hand: what counts as a link,
 * what the two quality buttons mean, and what a filename or a progress line turns into.
 */
class CoreTest {

    // ---- links -------------------------------------------------------------

    @Test
    fun `finds the link inside a shared caption`() {
        val text = "Watch this 😂 https://www.instagram.com/reel/Cx1y2z3/ it's wild"
        assertEquals("https://www.instagram.com/reel/Cx1y2z3/", SocialLinks.extractUrl(text))
    }

    @Test
    fun `drops the punctuation that belongs to the sentence`() {
        assertEquals(
            "https://youtu.be/abc123",
            SocialLinks.extractUrl("Look: https://youtu.be/abc123."),
        )
        assertEquals(
            "https://youtu.be/abc123",
            SocialLinks.extractUrl("(https://youtu.be/abc123)"),
        )
    }

    @Test
    fun `names the site from the host`() {
        assertEquals(Platform.YOUTUBE, SocialLinks.parse("https://youtu.be/x")!!.platform)
        assertEquals(Platform.YOUTUBE, SocialLinks.parse("https://m.youtube.com/watch?v=x")!!.platform)
        assertEquals(Platform.TIKTOK, SocialLinks.parse("https://vm.tiktok.com/x/")!!.platform)
        assertEquals(Platform.INSTAGRAM, SocialLinks.parse("https://instagram.com/reel/x")!!.platform)
        assertEquals(Platform.FACEBOOK, SocialLinks.parse("https://fb.watch/x/")!!.platform)
        assertEquals(Platform.X, SocialLinks.parse("https://x.com/u/status/1")!!.platform)
        assertEquals(Platform.X, SocialLinks.parse("https://t.co/x")!!.platform)
    }

    @Test
    fun `a host that merely ends in a known name is not that site`() {
        assertEquals(Platform.OTHER, SocialLinks.parse("https://notyoutube.com/x")!!.platform)
        assertEquals(Platform.OTHER, SocialLinks.parse("https://youtube.com.evil.example/x")!!.platform)
    }

    @Test
    fun `rejects text that is not a link`() {
        assertNull(SocialLinks.parse("hello there"))
        assertNull(SocialLinks.parse(""))
        assertNull(SocialLinks.parse("https://"))
        assertNull(SocialLinks.parse("ftp://youtube.com/x"))
        assertNull(SocialLinks.extractUrl("no link here at all"))
    }

    // ---- quality -----------------------------------------------------------

    @Test
    fun `hq is hidden when the source cannot beat 720`() {
        assertEquals(listOf(Quality.NORMAL), QualityChoice.options(480))
        assertEquals(listOf(Quality.NORMAL), QualityChoice.options(720))
        assertEquals(listOf(Quality.NORMAL, Quality.HIGH), QualityChoice.options(1080))
        assertEquals(listOf(Quality.NORMAL, Quality.HIGH), QualityChoice.options(4320))
    }

    @Test
    fun `an unknown maximum keeps both choices`() {
        assertEquals(listOf(Quality.NORMAL, Quality.HIGH), QualityChoice.options(null))
    }

    @Test
    fun `selectors always end in something that can actually be saved`() {
        assertTrue(QualityChoice.selector(Quality.NORMAL).contains("height<=720"))
        assertTrue(QualityChoice.selector(Quality.HIGH).contains("height<=2160"))
        // Every selector has a plain "best" fallback, so a source with no matching streams
        // still produces a file instead of an error.
        for (quality in Quality.entries) {
            assertTrue(QualityChoice.selector(quality).endsWith("/b"))
        }
    }

    // ---- filenames ---------------------------------------------------------

    @Test
    fun `a title becomes a filename a storage provider will accept`() {
        assertEquals("Me at the zoo.mp4", FileNames.fromTitle("Me at the zoo"))
        val hostile = FileNames.fromTitle("a/b:c*?\"<>|c")
        assertTrue(hostile.none { it in "\\/:*?\"<>|" }, "illegal character survived: $hostile")
        assertTrue(hostile.endsWith(".mp4"))
        assertEquals("UU video.mp4", FileNames.fromTitle("   "))
        assertEquals("UU video.mp4", FileNames.fromTitle(null))
        assertEquals("clip.webm", FileNames.fromTitle("clip", extension = "webm"))
    }

    @Test
    fun `long titles are cut before they lose their extension`() {
        val long = "y".repeat(400)
        val name = FileNames.fromTitle(long)
        assertTrue(name.length <= 90 + ".mp4".length)
        assertTrue(name.endsWith(".mp4"))
    }

    // ---- progress text -----------------------------------------------------

    @Test
    fun `a progress line becomes speed and eta`() {
        val detail = ProgressText.detail(
            "[download]  42.1% of 12.34MiB at 3.21MiB/s ETA 00:12"
        )
        assertEquals("12.34MiB · 3.21MiB/s · ETA 00:12", detail)
    }

    @Test
    fun `merging is announced in words`() {
        assertEquals("Finishing up", ProgressText.detail("[Merger] Merging formats into \"x.mp4\""))
        assertEquals(
            "1.00GiB · 1.00MiB/s · ETA 01:00",
            ProgressText.detail("[download]  10.0% of 1.00GiB at 1.00MiB/s ETA 01:00"),
        )
    }

    @Test
    fun `an error line is pulled out of yt-dlp's stderr`() {
        val stderr = "WARNING: nonsense\nERROR: Video unavailable\nTraceback"
        assertEquals("Video unavailable", ProgressText.errorMessage(stderr))
        assertNull(ProgressText.errorMessage("nothing useful"))
    }

    // ---- direct links ------------------------------------------------------

    @Test
    fun `only media files are treated as direct`() {
        assertTrue(DirectLinks.looksDirect("https://cdn.example.com/a.mp4"))
        assertTrue(DirectLinks.looksDirect("https://cdn.example.com/a.mp4?token=1"))
        assertTrue(DirectLinks.looksDirect("https://cdn.example.com/a.WEBM"))
        assertTrue(!DirectLinks.looksDirect("https://www.instagram.com/reel/x/"))
        assertTrue(!DirectLinks.looksDirect("https://youtu.be/x"))
    }

    @Test
    fun `a direct link suggests its own name`() {
        assertEquals("clip.mp4", DirectLinks.suggestedName("https://cdn.example.com/clip.mp4?sig=1"))
        assertEquals("my clip.mp4", DirectLinks.suggestedName("https://cdn.example.com/my%20clip.mp4"))
        assertNull(DirectLinks.suggestedName("https://cdn.example.com/"))
    }
}
