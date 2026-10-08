package com.rahga.x2rock.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** [serviceLogo]: the players' service names against the names Sonos lists logos under. */
class ServiceLogoTest {
    @Test fun `a name matches whatever its case and punctuation`() {
        assertEquals(SERVICE_LOGOS.getValue("Tidal") + "?w=112&fm=png", serviceLogo("TIDAL"))
        assertNotNull(serviceLogo("Deezer"))
    }

    @Test fun `a player's longer name finds the listed name it begins with`() {
        assertEquals(SERVICE_LOGOS.getValue("TuneIn") + "?w=112&fm=png", serviceLogo("TuneIn (New)"))
        assertEquals(SERVICE_LOGOS.getValue("iChill Music") + "?w=112&fm=png", serviceLogo("iChill Music Service"))
    }

    @Test fun `a name with no listed match gets no logo rather than a guess`() {
        assertNull(serviceLogo("Spectre"))
        assertNull(serviceLogo("80s80s - REAL 80s Radio"))
    }
}
