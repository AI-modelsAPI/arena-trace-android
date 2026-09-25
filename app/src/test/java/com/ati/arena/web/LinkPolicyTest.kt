package com.ati.arena.web

import com.ati.arena.web.LinkPolicy.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPolicyTest {

    private fun main(
        url: String,
        gesture: Boolean = true,
        redirect: Boolean = false,
        linkClick: Boolean = true,
    ): Route {
        val scheme = url.substringBefore(':', "")
        val rest = url.substringAfter("://", "")
        val host = rest.substringBefore('/').substringBefore('?')
        val path = if ('/' in rest) "/" + rest.substringAfter('/').substringBefore('?') else ""
        return LinkPolicy.routeMain(scheme, host.ifEmpty { null }, path, gesture, redirect, linkClick)
    }

    @Test
    fun arenaAndItsSubdomainsAreInternal() {
        assertTrue(LinkPolicy.isArenaHost("arena.ai"))
        assertTrue(LinkPolicy.isArenaHost("ARENA.AI"))
        assertTrue(LinkPolicy.isArenaHost("arena.ai."))
        assertTrue(LinkPolicy.isArenaHost("auth.arena.ai"))
        assertTrue(LinkPolicy.isArenaHost("lmarena.ai"))
        assertTrue(LinkPolicy.isArenaHost("www.lmarena.ai"))
    }

    @Test
    fun lookAlikeHostsAreNotArena() {
        assertFalse(LinkPolicy.isArenaHost("evilarena.ai"))
        assertFalse(LinkPolicy.isArenaHost("arena.ai.evil.com"))
        assertFalse(LinkPolicy.isArenaHost("arena.aii"))
        assertFalse(LinkPolicy.isArenaHost(""))
        assertFalse(LinkPolicy.isArenaHost(null))
    }

    @Test
    fun externalLinkClickOpensANewTab() {
        assertEquals(Route.NEW_TAB, main("https://example.com/docs"))
        assertEquals(Route.NEW_TAB, main("http://github.com/owner/repo"))
    }

    @Test
    fun arenaNavigationStaysInPlace() {
        assertEquals(Route.IN_PLACE, main("https://arena.ai/agent/abc"))
        assertEquals(Route.IN_PLACE, main("https://arena.ai/"))
    }

    @Test
    fun onlyRealLinkTapsAreDiverted() {
        // Script navigations, redirects and gesture-less loads keep today's behaviour.
        assertEquals(Route.IN_PLACE, main("https://example.com/", linkClick = false))
        assertEquals(Route.IN_PLACE, main("https://example.com/", gesture = false))
        assertEquals(Route.IN_PLACE, main("https://example.com/", redirect = true))
    }

    @Test
    fun signInFlowsStayInTheArenaWebView() {
        assertEquals(Route.IN_PLACE, main("https://accounts.google.com/o/oauth2/v2/auth"))
        assertEquals(Route.IN_PLACE, main("https://appleid.apple.com/auth/authorize"))
        assertEquals(Route.IN_PLACE, main("https://abcd.supabase.co/auth/v1/authorize"))
        assertEquals(Route.IN_PLACE, main("https://github.com/login/oauth/authorize"))
        assertEquals(Route.IN_PLACE, main("https://challenges.cloudflare.com/turnstile"))
        // …but ordinary pages on the same sites are links like any other.
        assertEquals(Route.NEW_TAB, main("https://github.com/loginator/repo"))
        assertEquals(Route.NEW_TAB, main("https://www.google.com/search"))
    }

    @Test
    fun otherAppsOnlyOnAUserGesture() {
        assertEquals(Route.EXTERNAL_APP, main("mailto:someone@example.com"))
        assertEquals(Route.EXTERNAL_APP, main("tel:+85212345678"))
        assertEquals(Route.EXTERNAL_APP, main("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(Route.BLOCK, main("market://details?id=x", gesture = false))
    }

    @Test
    fun localAndScriptSchemesAreBlocked() {
        assertEquals(Route.BLOCK, main("file:///sdcard/secret.txt"))
        assertEquals(Route.BLOCK, main("content://com.example.provider/x"))
        assertEquals(Route.BLOCK, main("JavaScript:alert(1)"))
        assertEquals(Route.BLOCK, LinkPolicy.routeTab("file", hasGesture = true))
    }

    @Test
    fun passiveSchemesLoadInPlace() {
        assertEquals(Route.IN_PLACE, main("about:blank"))
        assertEquals(Route.IN_PLACE, main("blob:https://arena.ai/1234"))
        assertEquals(Route.IN_PLACE, LinkPolicy.routeMain(null, null, null, true, false, true))
    }

    @Test
    fun linkTabKeepsWebPagesAndHandsOffTheRest() {
        assertEquals(Route.IN_PLACE, LinkPolicy.routeTab("https", hasGesture = false))
        assertEquals(Route.IN_PLACE, LinkPolicy.routeTab("HTTP", hasGesture = true))
        assertEquals(Route.EXTERNAL_APP, LinkPolicy.routeTab("mailto", hasGesture = true))
        assertEquals(Route.BLOCK, LinkPolicy.routeTab("intent", hasGesture = false))
    }

    @Test
    fun onlyHttpUrlsCountAsWebUrls() {
        assertTrue(LinkPolicy.isWebUrl("https://example.com"))
        assertTrue(LinkPolicy.isWebUrl("HTTP://example.com/x"))
        assertFalse(LinkPolicy.isWebUrl("https://"))
        assertFalse(LinkPolicy.isWebUrl("blob:https://arena.ai/1"))
        assertFalse(LinkPolicy.isWebUrl("javascript:alert(1)"))
        assertFalse(LinkPolicy.isWebUrl(null))
    }
}
