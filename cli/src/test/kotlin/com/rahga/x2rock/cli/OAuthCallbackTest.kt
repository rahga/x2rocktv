package com.rahga.x2rock.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OAuthCallbackTest {

    @Test
    fun `parses the custom scheme the browser hands over`() {
        val code = OAuthCallback.parse("x2rock://callback?code=AUTH%2B1&state=abc_-XYZ")
        assertEquals(OAuthCallback.Code(code = "AUTH+1", state = "abc_-XYZ"), code)
    }

    @Test
    fun `also accepts the https hop before the scheme redirect`() {
        // Someone on --manual may copy the github.io URL instead of the x2rock:// one.
        val code = OAuthCallback.parse("https://rahga.github.io/x2rock/callback.html?state=s1&code=c1")
        assertEquals(OAuthCallback.Code("c1", "s1"), code)
    }

    @Test
    fun `tolerates surrounding whitespace from a paste`() {
        assertEquals(OAuthCallback.Code("c", "s"), OAuthCallback.parse("  x2rock://callback?code=c&state=s\n"))
    }

    @Test
    fun `missing code or state is null`() {
        assertNull(OAuthCallback.parse("x2rock://callback?state=only"))
        assertNull(OAuthCallback.parse("x2rock://callback?code=only"))
        assertNull(OAuthCallback.parse("not a url"))
    }
}
