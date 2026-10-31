package au.com.uulabs.uudownloader.core

/**
 * The sites the app understands, and nothing else.
 *
 * Detection is on the *host* only. A path match would be wrong in the one case that matters:
 * Instagram serves a reel from `instagram.com/reel/...` today and from a different path
 * tomorrow, while the host has been stable for a decade.
 */
enum class Platform(val label: String, private val hosts: List<String>) {
    YOUTUBE("YouTube", listOf("youtube.com", "youtu.be", "youtube-nocookie.com")),
    TIKTOK("TikTok", listOf("tiktok.com")),
    INSTAGRAM("Instagram", listOf("instagram.com", "instagr.am")),
    FACEBOOK("Facebook", listOf("facebook.com", "fb.watch", "fb.com", "fb.me")),
    X("X", listOf("x.com", "twitter.com", "t.co")),
    REDDIT("Reddit", listOf("reddit.com", "redd.it", "v.redd.it")),

    /**
     * A link the app does not recognise by name.
     *
     * This is the majority of what yt-dlp handles — Pornhub, Xvideos, Vimeo and a thousand
     * others — and it is deliberately not an allow-list. It is still worth trying: yt-dlp knows a few
     * thousand sites, and refusing a Vimeo link because the label would read "Link" is worse
     * than the label reading "Link".
     */
    OTHER("Link", emptyList());

    fun matches(host: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return hosts.any { h == it || h.endsWith(".$it") }
    }
}

/** A link the app can act on, plus what it believes the link is. */
data class SocialLink(val url: String, val host: String, val platform: Platform) {
    /**
     * What to call the place the video came from.
     *
     * The five sites named in the app get their own label. Everything else — and that is
     * thousands of sites, from Vimeo to Pornhub to a niche blog — is named after its domain,
     * because "Link" tells the user nothing about where their video went.
     */
    val siteName: String
        get() = if (platform == Platform.OTHER) SocialLinks.siteNameOf(host) else platform.label
}

object SocialLinks {

    /**
     * Shared text is never just a URL. Instagram and Facebook append the caption, some apps put
     * the link in quotes, and a trailing full stop is common. Pull the first http(s) URL out
     * and drop the punctuation that only belongs to the surrounding sentence.
     */
    private val URL_PATTERN = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private val TRAILING_JUNK = charArrayOf('.', ',', ';', ':', ')', ']', '}', '!', '?', '>', '"', '\'')

    fun extractUrl(sharedText: String?): String? {
        val text = sharedText?.trim().orEmpty()
        if (text.isEmpty()) return null
        val raw = URL_PATTERN.find(text)?.value ?: return null
        return normalize(raw)
    }

    /** Feed in a URL the user typed or shared; get back a canonical form, or null if it is not one. */
    fun parse(candidate: String?): SocialLink? {
        val url = normalize(candidate?.trim().orEmpty()) ?: return null
        val host = hostOf(url) ?: return null
        val platform = Platform.entries.firstOrNull { it.matches(host) } ?: Platform.OTHER
        return SocialLink(url, host, platform)
    }

    private fun normalize(candidate: String): String? {
        if (candidate.isEmpty()) return null
        val withScheme = when {
            candidate.startsWith("http://", true) || candidate.startsWith("https://", true) -> candidate
            // A bare "youtu.be/xyz" typed by hand is still a link; t.co and youtu.be are the
            // two shorteners people actually retype.
            candidate.contains("://") -> return null
            else -> return null
        }
        var url = withScheme.trim()
        while (url.isNotEmpty() && url.last() in TRAILING_JUNK) url = url.dropLast(1)
        val host = hostOf(url) ?: return null
        // A host with no dot ("localhost") is not something a social video comes from.
        if (!host.contains('.')) return null
        return url
    }

    private val MULTI_PART_SUFFIXES = setOf(
        "co.uk", "com.au", "co.nz", "co.za", "com.br", "co.jp", "com.mx", "co.in", "com.tr",
    )

    /** "www.pornhub.com" -> "Pornhub", "ew.example.co.uk" -> "Example". */
    fun siteNameOf(host: String): String {
        val parts = host.lowercase().removePrefix("www.").split('.').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "Site"
        val suffix2 = parts.takeLast(2).joinToString(".")
        val name = when {
            parts.size >= 3 && suffix2 in MULTI_PART_SUFFIXES -> parts[parts.size - 3]
            parts.size >= 2 -> parts[parts.size - 2]
            else -> parts.first()
        }
        return name.replace('-', ' ')
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
            .ifEmpty { "Site" }
    }

    private fun hostOf(url: String): String? {
        val withoutScheme = url.substringAfter("://", "")
        if (withoutScheme.isEmpty()) return null
        val authority = withoutScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty()) return null
        val hostAndPort = authority.substringAfter('@')
        val host = hostAndPort.substringBefore(':').lowercase().removeSuffix(".")
        if (host.isEmpty() || host.any { it.isWhitespace() }) return null
        return host
    }
}
