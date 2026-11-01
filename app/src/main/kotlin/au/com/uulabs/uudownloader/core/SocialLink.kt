package au.com.uulabs.uudownloader.core

/**
 * The sites the app names. Any other link still goes to the engine under [OTHER].
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
     * This is the majority of what yt-dlp handles — a thousand sites, adult ones among them —
     * and it is deliberately not an allow-list. It is still worth trying: yt-dlp knows a few
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
     * The six sites named in the app get their own label. Everything else — and that is
     * thousands of sites, adult ones included — is named after its domain,
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
    private val TRAILING_JUNK = charArrayOf('.', ',', ';', ':', '!', '?', '>', '"', '\'')
    private val BRACKETS = mapOf(')' to '(', ']' to '[', '}' to '{')

    /**
     * The link to act on. A caption can hold several, and the first is often a profile or a
     * link page, so a site the app can name beats whichever link came first.
     */
    fun extractUrl(sharedText: String?): String? {
        val text = sharedText?.trim().orEmpty()
        if (text.isEmpty()) return null
        val urls = URL_PATTERN.findAll(text).mapNotNull { normalize(it.value) }.toList()
        return urls.firstOrNull { url ->
            hostOf(url)?.let { host -> Platform.entries.any { it.matches(host) } } == true
        } ?: urls.firstOrNull()
    }

    /** Feed in a URL the user typed or shared; get back a canonical form, or null if it is not one. */
    fun parse(candidate: String?): SocialLink? {
        val url = normalize(candidate?.trim().orEmpty()) ?: return null
        val host = hostOf(url) ?: return null
        val platform = Platform.entries.firstOrNull { it.matches(host) } ?: Platform.OTHER
        return SocialLink(url, host, platform)
    }

    /** Only http and https. Anything else, including a bare "youtu.be/xyz", is not a link here. */
    private fun normalize(candidate: String): String? {
        if (!candidate.startsWith("http://", true) && !candidate.startsWith("https://", true)) return null
        val url = withoutTrailingJunk(candidate.trim())
        val host = hostOf(url) ?: return null
        // A host with no dot ("localhost") is not something a social video comes from.
        if (!host.contains('.')) return null
        return url
    }

    /**
     * Drops the punctuation of the surrounding sentence. A closing bracket goes only when it has
     * no partner in the link, because "/video_(1)" is an address and "(see /video)" is prose.
     */
    private fun withoutTrailingJunk(raw: String): String {
        var url = raw
        while (url.isNotEmpty()) {
            val last = url.last()
            val opener = BRACKETS[last]
            val strip = when {
                last in TRAILING_JUNK -> true
                opener != null -> url.count { it == last } > url.count { it == opener }
                else -> false
            }
            if (!strip) break
            url = url.dropLast(1)
        }
        return url
    }

    private val MULTI_PART_SUFFIXES = setOf(
        "co.uk", "com.au", "co.nz", "co.za", "com.br", "co.jp", "com.mx", "co.in", "com.tr",
    )

    /** "www.example.com" -> "Example", "ew.example.co.uk" -> "Example". */
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
