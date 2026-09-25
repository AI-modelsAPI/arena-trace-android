package com.ati.arena.web

/**
 * Where a navigation should go. Pure (no Android types) so every rule is unit-tested.
 *
 * The Arena WebView must never be navigated away from the conversation by a link:
 *  - links the page opens in a new window (target=_blank / window.open) always go
 *    to the in-app link tab (see [com.ati.arena.ui.LinkTab]);
 *  - a plain link click to another site (same window) also goes to the link tab;
 *  - everything else keeps loading in place, so Arena's own navigation, server
 *    redirects and sign-in flows behave exactly as before;
 *  - non-web schemes (mailto:, tel:, intent:, market: …) are handed to other apps,
 *    but only on a user gesture; file:/content: are never loaded.
 */
object LinkPolicy {

    enum class Route {
        /** Let the WebView load it where it is. */
        IN_PLACE,

        /** Open it in the in-app link tab, over the conversation. */
        NEW_TAB,

        /** Hand it to another app (mail, dialer, store, intent: deep link …). */
        EXTERNAL_APP,

        /** Swallow it. */
        BLOCK,
    }

    private val ARENA_DOMAINS = listOf("arena.ai", "lmarena.ai")

    private val WEB_SCHEMES = setOf("http", "https")

    // Loaded in place: about:blank, and blob:/data: navigations (downloads, previews).
    private val PASSIVE_SCHEMES = setOf("about", "blob", "data")

    // Never loaded from a page: local files, content providers, script URLs.
    private val FORBIDDEN_SCHEMES = setOf("file", "content", "javascript", "vbscript")

    // Sign-in / challenge hosts: a link to these must stay in the Arena WebView so
    // the flow can finish there (cookies and redirects back to Arena).
    private val AUTH_HOSTS = setOf(
        "accounts.google.com",
        "appleid.apple.com",
        "login.microsoftonline.com",
        "login.live.com",
        "challenges.cloudflare.com",
    )
    private val AUTH_SUFFIXES = listOf(".supabase.co", ".auth0.com", ".clerk.accounts.dev", ".firebaseapp.com")
    private val AUTH_PATHS = mapOf(
        "github.com" to listOf("/login", "/session"),
        "discord.com" to listOf("/oauth2", "/api/oauth2"),
        "x.com" to listOf("/i/oauth2"),
        "twitter.com" to listOf("/i/oauth2"),
    )

    fun normalizeHost(host: String?): String = host.orEmpty().trim().trimEnd('.').lowercase()

    /** arena.ai (or lmarena.ai) itself or one of its subdomains — not look-alikes. */
    fun isArenaHost(host: String?): Boolean {
        val h = normalizeHost(host)
        return h.isNotEmpty() && ARENA_DOMAINS.any { h == it || h.endsWith(".$it") }
    }

    fun isAuthFlow(host: String?, path: String?): Boolean {
        val h = normalizeHost(host)
        if (h.isEmpty()) return false
        if (h in AUTH_HOSTS || AUTH_SUFFIXES.any { h.endsWith(it) }) return true
        val p = path.orEmpty()
        return AUTH_PATHS[h.removePrefix("www.")]?.any { p == it || p.startsWith("$it/") } == true
    }

    /**
     * A main-frame navigation in the Arena WebView (same window).
     *
     * @param isLinkClick the navigation comes from tapping an `<a href>` (WebView hit test).
     */
    fun routeMain(
        scheme: String?,
        host: String?,
        path: String?,
        hasGesture: Boolean,
        isRedirect: Boolean,
        isLinkClick: Boolean,
    ): Route {
        val s = scheme.orEmpty().lowercase()
        return when {
            s.isEmpty() || s in PASSIVE_SCHEMES -> Route.IN_PLACE
            s in FORBIDDEN_SCHEMES -> Route.BLOCK
            s in WEB_SCHEMES -> when {
                isArenaHost(host) || isRedirect || isAuthFlow(host, path) -> Route.IN_PLACE
                hasGesture && isLinkClick -> Route.NEW_TAB
                else -> Route.IN_PLACE
            }
            hasGesture -> Route.EXTERNAL_APP
            else -> Route.BLOCK
        }
    }

    /** A main-frame navigation inside the link tab: web pages stay in the tab. */
    fun routeTab(scheme: String?, hasGesture: Boolean): Route {
        val s = scheme.orEmpty().lowercase()
        return when {
            s.isEmpty() || s in WEB_SCHEMES || s in PASSIVE_SCHEMES -> Route.IN_PLACE
            s in FORBIDDEN_SCHEMES -> Route.BLOCK
            hasGesture -> Route.EXTERNAL_APP
            else -> Route.BLOCK
        }
    }

    /** Only http(s) URLs are ever handed to a browser, copied or shared. */
    fun isWebUrl(url: String?): Boolean {
        val u = url.orEmpty().trim()
        val colon = u.indexOf(':')
        return colon > 0 && u.substring(0, colon).lowercase() in WEB_SCHEMES && u.length > colon + 3
    }
}
