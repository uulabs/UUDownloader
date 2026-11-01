package au.com.uulabs.uudownloader

import au.com.uulabs.uudownloader.core.DirectLinks
import au.com.uulabs.uudownloader.core.EngineOptions
import au.com.uulabs.uudownloader.core.LogRedaction
import au.com.uulabs.uudownloader.core.FileNames
import au.com.uulabs.uudownloader.core.Platform
import au.com.uulabs.uudownloader.core.Quality
import au.com.uulabs.uudownloader.core.QualityChoice
import au.com.uulabs.uudownloader.data.ByteSize
import au.com.uulabs.uudownloader.data.LimitMode
import au.com.uulabs.uudownloader.data.StorageLimit
import au.com.uulabs.uudownloader.core.SocialLinks
import au.com.uulabs.uudownloader.download.DownloadItem
import au.com.uulabs.uudownloader.download.DownloadState
import au.com.uulabs.uudownloader.download.HistoryStore
import au.com.uulabs.uudownloader.download.ProgressText
import au.com.uulabs.uudownloader.download.SavedEntry
import au.com.uulabs.uudownloader.download.SavedLedger
import au.com.uulabs.uudownloader.update.AppUpdater
import au.com.uulabs.uudownloader.update.EngineUpdater
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
        assertEquals(Platform.REDDIT, SocialLinks.parse("https://www.reddit.com/r/a/comments/1/x/")!!.platform)
        assertEquals(Platform.REDDIT, SocialLinks.parse("https://v.redd.it/abc123")!!.platform)
        assertEquals(Platform.REDDIT, SocialLinks.parse("https://redd.it/abc123")!!.platform)
    }

    @Test
    fun `a host that merely ends in a known name is not that site`() {
        assertEquals(Platform.OTHER, SocialLinks.parse("https://notyoutube.com/x")!!.platform)
        assertEquals(Platform.OTHER, SocialLinks.parse("https://youtube.com.evil.example/x")!!.platform)
    }

    @Test
    fun `an unnamed site is called by its own name, not by "Link"`() {
        assertEquals(
            "Example",
            SocialLinks.parse("https://www.example.com/watch?v=abc")!!.siteName,
        )
        assertEquals("Vimeo", SocialLinks.parse("https://vimeo.com/12345")!!.siteName)
        assertEquals("Dailymotion", SocialLinks.parse("https://www.dailymotion.com/x")!!.siteName)
        assertEquals("Example", SocialLinks.siteNameOf("ew.example.co.uk"))
        assertEquals("Spank Bang", SocialLinks.siteNameOf("spank-bang.com"))
    }

    @Test
    fun `the named platforms keep their own label`() {
        assertEquals("YouTube", SocialLinks.parse("https://youtu.be/x")!!.siteName)
        assertEquals("TikTok", SocialLinks.parse("https://www.tiktok.com/@a/video/1")!!.siteName)
        assertEquals("X", SocialLinks.parse("https://x.com/a/status/1")!!.siteName)
        assertEquals("Reddit", SocialLinks.parse("https://reddit.com/r/a/comments/1/x/")!!.siteName)
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
        assertTrue(QualityChoice.selector(Quality.NORMAL, canMerge = true).contains("height<=720"))
        assertTrue(QualityChoice.selector(Quality.HIGH, canMerge = true).contains("height<=2160"))
        // Every selector has a plain "best" fallback, so a source with no matching streams
        // still produces a file instead of an error.
        for (quality in Quality.entries) {
            for (merge in listOf(true, false)) {
                assertTrue(QualityChoice.selector(quality, merge).endsWith("/b"))
            }
        }
    }

    @Test
    fun `a build without ffmpeg never asks yt-dlp to merge`() {
        // +ba means "also fetch the audio stream and join it", which needs ffmpeg.
        assertTrue(!QualityChoice.selector(Quality.HIGH, canMerge = false).contains("+ba"))
        assertTrue(QualityChoice.selector(Quality.HIGH, canMerge = true).contains("+ba"))
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

    @Test
    fun `a login wall is explained rather than pasted at the user`() {
        val raw = "[Reddit] 90bu6w: Account authentication is required. Use --cookies, " +
            "--cookies-from-browser, --username and --password (reddit)"
        val hint = ProgressText.hint(raw)
        assertTrue(hint.contains("signed-in session"), hint)
        assertTrue(!hint.contains("--cookies-from-browser"), hint)
        assertEquals("That video is private.", ProgressText.hint("This video is private"))
    }

    // ---- storage allowance -------------------------------------------------

    @Test
    fun `a gigabyte limit is a number of bytes and a percent is a share of the phone`() {
        val gb = StorageLimit(LimitMode.GIGABYTES, gigabytes = 5, percent = 10)
        assertEquals(5_000_000_000L, gb.bytes(totalInternalBytes = 128_000_000_000L))

        val percent = StorageLimit(LimitMode.PERCENT, gigabytes = 5, percent = 10)
        assertEquals(12_800_000_000L, percent.bytes(totalInternalBytes = 128_000_000_000L))
        // The percentage must not depend on free space, only on the device's capacity.
        assertEquals(12_800_000_000L, percent.bytes(totalInternalBytes = 128_000_000_000L))
    }

    @Test
    fun `sizes read the way a person says them`() {
        assertEquals("476 KB", ByteSize.human(476_000))
        assertEquals("12 MB", ByteSize.human(12_400_000))
        assertEquals("5.0 GB", ByteSize.human(5_000_000_000))
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

    @Test
    fun `a website on a media-sounding domain is not a file`() {
        assertTrue(!DirectLinks.looksDirect("https://clips.mov"))
        assertTrue(!DirectLinks.looksDirect("https://clips.mov/watch/123"))
        assertNull(DirectLinks.suggestedName("https://clips.mov"))
    }

    @Test
    fun `a page answering a media link is recognised`() {
        val html = "<!DOCTYPE html><html>".toByteArray()
        val mp4 = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray()
        assertTrue(DirectLinks.looksLikePage("text/html; charset=utf-8", mp4))
        assertTrue(DirectLinks.looksLikePage("application/octet-stream", html))
        assertTrue(DirectLinks.looksLikePage(null, "  \n<html>".toByteArray()))
        assertTrue(!DirectLinks.looksLikePage("video/mp4", mp4))
        assertTrue(!DirectLinks.looksLikePage("application/octet-stream", mp4))
        assertTrue(!DirectLinks.looksLikePage(null, ByteArray(0)))
    }

    // ---- links in captions -------------------------------------------------

    @Test
    fun `a closing bracket stays when it belongs to the link`() {
        assertEquals("https://example.com/video_(1)", SocialLinks.extractUrl("see https://example.com/video_(1)"))
        assertEquals("https://example.com/video_(1)", SocialLinks.extractUrl("(https://example.com/video_(1))."))
        assertEquals("https://example.com/a", SocialLinks.extractUrl("[https://example.com/a]"))
    }

    @Test
    fun `a site the app can name beats a link page earlier in the caption`() {
        assertEquals(
            "https://www.tiktok.com/@a/video/1",
            SocialLinks.extractUrl("via https://linktr.ee/me https://www.tiktok.com/@a/video/1"),
        )
        assertEquals("https://linktr.ee/me", SocialLinks.extractUrl("https://linktr.ee/me https://other.example/x"))
    }

    // ---- filenames beyond ASCII --------------------------------------------

    @Test
    fun `a long title in another script still fits the filesystem`() {
        val name = FileNames.fromTitle("\u8a9e".repeat(200))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255, name)
        assertTrue(name.endsWith(".mp4"))
    }

    @Test
    fun `an emoji is never cut in half`() {
        val name = FileNames.fromTitle("a" + "\uD83D\uDE00".repeat(60))
        val stem = name.removeSuffix(".mp4")
        assertTrue(!stem.last().isHighSurrogate(), "ends in half an emoji")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 255, name)
    }

    @Test
    fun `text direction controls and odd extensions do not reach the filename`() {
        assertEquals("abc.mp4", FileNames.fromTitle("a\u202Ebc"))
        assertEquals("clip.mp4", FileNames.fromTitle("clip", extension = "../.."))
        assertEquals("clip.webm", FileNames.fromTitle("clip", extension = ".webm"))
    }

    // ---- what a failure is called ------------------------------------------

    @Test
    fun `a failure is not blamed on the video without the site saying so`() {
        val tool = "Postprocessing: ffprobe and ffmpeg not found"
        assertEquals(tool, ProgressText.hint(tool))
        val disk = "Could not write to private storage"
        assertEquals(disk, ProgressText.hint(disk))
        val word = "Error in dialog in extractor"
        assertEquals(word, ProgressText.hint(word))
        assertTrue(ProgressText.hint("Unable to download webpage: Name or service not known").contains("connection"))
    }

    @Test
    fun `the site's own refusals are still recognised`() {
        assertEquals("That video is private.", ProgressText.hint("Private video. Sign-in may be required"))
        assertEquals("That video has been removed.", ProgressText.hint("HTTP Error 404: Not Found"))
        assertEquals("That video has been removed.", ProgressText.hint("This video has been removed by the uploader"))
        assertTrue(ProgressText.hint("You need to log in to view this").contains("signed-in"))
        assertTrue(ProgressText.hint("This content isn't available, login required").contains("signed-in"))
    }

    @Test
    fun `the whole error line is kept`() {
        assertEquals(400, ProgressText.errorMessage("ERROR: " + "x".repeat(400))?.length)
    }

    // ---- what yt-dlp is asked for ------------------------------------------

    private fun command(quality: Quality = Quality.HIGH, client: String? = null, cookies: String? = null) =
        EngineOptions.downloadCommand(quality, canMerge = true, client = client, workDir = "/w", cookies = cookies)

    private fun List<String>.after(option: String): String = this[indexOf(option) + 1]

    @Test
    fun `a playlist or channel link saves one video`() {
        assertEquals("1", command().after("--playlist-items"))
        assertEquals("1", EngineOptions.probeCommand(null).after("--playlist-items"))
    }

    @Test
    fun `a live stream is refused rather than recorded`() {
        assertEquals("!is_live", command().after("--match-filter"))
        assertTrue(EngineOptions.wasFilteredOut("[download] Stream does not pass filter (!is_live), skipping .."))
        assertTrue(!EngineOptions.wasFilteredOut("[download] 100%"))
    }

    @Test
    fun `yt-dlp names its own result`() {
        val args = command()
        val at = args.indexOf("--print-to-file")
        assertEquals("after_move:%(filepath)s", args[at + 1])
        assertEquals("/w/${EngineOptions.PATH_FILE}", args[at + 2])
    }

    @Test
    fun `a percent sign in a folder is not read as a template`() {
        val args = EngineOptions.downloadCommand(Quality.NORMAL, false, null, "/a%b", null)
        assertTrue(args.after("-o").startsWith("/a%%b/"))
    }

    @Test
    fun `cookies and the client are only passed when there are any`() {
        assertTrue("--cookies" !in command())
        assertEquals("/c", command(cookies = "/c").after("--cookies"))
        assertTrue("--extractor-args" !in command())
        assertEquals("youtube:player_client=tv", command(client = "tv").after("--extractor-args"))
    }

    @Test
    fun `only YouTube gets more than one client`() {
        assertEquals(5, EngineOptions.clientLadder("https://www.youtube.com/watch?v=x").size)
        assertEquals(5, EngineOptions.clientLadder("https://www.youtube-nocookie.com/embed/x").size)
        assertEquals(5, EngineOptions.clientLadder("https://youtu.be/x").size)
        assertEquals(1, EngineOptions.clientLadder("https://notyoutube.com/x").size)
        assertEquals(1, EngineOptions.clientLadder("https://youtube.com.evil.example/x").size)
        assertEquals(1, EngineOptions.clientLadder("https://vimeo.com/1").size)
    }

    @Test
    fun `refusals are sorted into worth retrying and final`() {
        assertTrue(EngineOptions.isBotCheck("Sign in to confirm you're not a bot"))
        assertTrue(EngineOptions.isBotCheck("HTTP Error 403: Forbidden"))
        assertTrue(!EngineOptions.isBotCheck("id a403b is unavailable"))
        assertTrue(EngineOptions.isFinalRefusal("This video is private"))
        assertTrue(!EngineOptions.isFinalRefusal("Postprocessing: Conversion failed"))
        assertTrue(!EngineOptions.isFinalRefusal("HTTP Error 403: Forbidden"))
    }

    @Test
    fun `what yt-dlp printed is read carefully`() {
        assertEquals(2, EngineOptions.playlistCount("2\n"))
        assertNull(EngineOptions.playlistCount("1\n"))
        assertNull(EngineOptions.playlistCount("\n"))
        assertNull(EngineOptions.playlistCount(null))
        assertEquals("A title", EngineOptions.printedValue("A title\n"))
        assertNull(EngineOptions.printedValue("NA\n"))
    }

    // ---- logs --------------------------------------------------------------

    @Test
    fun `a release log never says what was downloaded`() {
        val cleaned = LogRedaction.clean(
            "ERROR: [youtube] dQw4w9WgXcQ: Sign in; https://www.youtube.com/watch?v=dQw4w9WgXcQ failed",
        )
        assertTrue("dQw4w9WgXcQ" !in cleaned, cleaned)
        assertTrue("youtube" !in cleaned, cleaned)
        assertTrue("Sign in" in cleaned, cleaned)
    }

    // ---- versions ----------------------------------------------------------

    @Test
    fun `version comparison`() {
        assertTrue(AppUpdater.isNewer("v1.6.0", "1.5.0"))
        assertTrue(AppUpdater.isNewer("1.5.1", "1.5"))
        assertTrue(AppUpdater.isNewer("2026.11.1", "2026.10.26"))
        assertTrue(!AppUpdater.isNewer("1.5.0", "1.5.0"))
        assertTrue(!AppUpdater.isNewer("1.5.0-2", "1.5.0"))
        assertTrue(!AppUpdater.isNewer("1.6.0-rc1", "1.6.0"))
        assertTrue(!AppUpdater.isNewer("1.x.9", "1.5.0"))
        assertTrue(!AppUpdater.isNewer("1.9.0", "unknown"))
    }

    @Test
    fun `the engine checksum comes from the right line`() {
        val hash = "e51e69e89489f199530f13d32a4342da3ddb2cf34842f364603a066db05afbf3"
        val other = "a".repeat(64)
        val sums = "$other  yt-dlp.exe\n$other  yt-dlp_linux\n$hash  yt-dlp\n$other  yt-dlp.tar.gz\n"
        assertEquals(hash, EngineUpdater.checksumFor(sums))
        assertEquals(hash, EngineUpdater.checksumFor("$hash *yt-dlp\n"))
        assertNull(EngineUpdater.checksumFor("$other  yt-dlp_linux\n"))
        assertNull(EngineUpdater.checksumFor("nonsense  yt-dlp\n"))
    }

    @Test
    fun `an odd engine tag cannot reach a download address`() {
        assertTrue(EngineUpdater.isValidTag("2026.10.26"))
        assertTrue(EngineUpdater.isValidTag("2026.10.26.1"))
        assertTrue(!EngineUpdater.isValidTag("../../evil"))
        assertTrue(!EngineUpdater.isValidTag("2026.10.26/x"))
        assertTrue(!EngineUpdater.isValidTag(""))
    }

    // ---- storage ledger ----------------------------------------------------

    private fun done(id: String, bytes: Long, uri: String? = "content://x/$id") = DownloadItem(
        id = id,
        url = "https://e.com/$id",
        platform = Platform.OTHER,
        quality = Quality.NORMAL,
        state = DownloadState.DONE,
        savedUri = uri,
        sizeBytes = bytes,
    )

    @Test
    fun `the allowance counts files whose rows have scrolled out of history`() {
        var historyText: String? = null
        var ledgerText: String? = null
        val history = HistoryStore({ historyText }, { historyText = it })
        val ledger = SavedLedger({ ledgerText }, { ledgerText = it })
        repeat(31) { index ->
            val item = done("$index", 1_000)
            history.record(item)
            ledger.add(SavedEntry(item.id, item.savedUri!!, item.sizeBytes!!))
        }
        assertEquals(30, history.load().size)
        assertEquals(31_000L, ledger.totalBytes())
    }

    @Test
    fun `the ledger lets go of files that have gone`() {
        var text: String? = null
        val ledger = SavedLedger({ text }, { text = it })
        ledger.add(SavedEntry("a", "content://x/a", 10))
        ledger.add(SavedEntry("b", "content://x/b", 20))
        ledger.retain { it.id != "a" }
        assertEquals(20L, ledger.totalBytes())
        ledger.remove("b")
        assertEquals(0L, ledger.totalBytes())
    }

    @Test
    fun `an update starts the ledger from the old history, once`() {
        var text: String? = null
        val ledger = SavedLedger({ text }, { text = it })
        ledger.seedIfNew(listOf(done("a", 10), done("b", 20, uri = null)))
        assertEquals(10L, ledger.totalBytes())
        ledger.remove("a")
        ledger.seedIfNew(listOf(done("a", 10)))
        assertEquals(0L, ledger.totalBytes())
    }

    @Test
    fun `the engine's whole message survives in history`() {
        var text: String? = null
        val history = HistoryStore({ text }, { text = it })
        history.record(done("a", 1).copy(state = DownloadState.FAILED, error = "short", errorDetail = "long detail"))
        assertEquals("long detail", history.load().single().errorDetail)
    }

    // ---- licences ----------------------------------------------------------

    @Test
    fun `the app carries the same licence as the repository`() {
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .first { java.io.File(it, "LICENSE").exists() }
        assertEquals(
            java.io.File(root, "LICENSE").readText(),
            java.io.File(root, "app/src/main/assets/licenses/GPL-3.0.txt").readText(),
        )
    }
}
