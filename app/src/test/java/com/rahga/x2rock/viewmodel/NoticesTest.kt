package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.lan.AUTHENTICATION_REQUIRED
import com.rahga.x2rock.lan.SonosCommandException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NoticesTest {

    /**
     * With Authentication switched on in the Sonos app, every Control API command answers
     * `ERROR_NO_PERMISSION` (x2rock, verified 2026-09-26). The raw code says nothing a
     * viewer can act on; the switch's name does.
     */
    @Test fun `a permission refusal names the switch that causes it`() {
        val notice = failureNotice("skip", SonosCommandException("skipToNextTrack", "ERROR_NO_PERMISSION"))!!
        assertTrue(notice, notice.startsWith("Couldn't skip: this system requires authentication"))
        assertTrue(notice, "Connection Security" in notice)
    }

    /** P-56: the connection's own text was shown as it was, lowercase and naming a speaker's MAC. */
    @Test fun `a connection failure is said in plain words, with no speaker named`() {
        val shown = plainConnectionProblem("no reply from sonos-48A6B81853E0.local within 5000ms", choosing = false)
        assertTrue(shown, shown.startsWith("Couldn't reach the Sonos speakers."))
        assertTrue(shown, "sonos-" !in shown)
        // Seen on a Chromecast with Google TV with its Wi-Fi switched off, on 0.1.3 (2026-10-19).
        val offline = plainConnectionProblem("sendto failed: ENETUNREACH (Network is unreachable)", choosing = false)
        assertTrue(offline, offline.startsWith("Couldn't reach the Sonos speakers."))
    }

    @Test fun `authentication and a choice of systems keep their own words`() {
        assertEquals(AUTHENTICATION_REQUIRED, plainConnectionProblem(AUTHENTICATION_REQUIRED, choosing = false))
        val choose = "2 Sonos systems answer on this network. Choose one."
        assertEquals(choose, plainConnectionProblem(choose, choosing = true))
    }

    @Test fun `any other failure carries its own words`() {
        assertEquals(
            "Couldn't add Guest TV: Kitchen did not answer, and the change has not appeared",
            failureNotice("add Guest TV", IOException("Kitchen did not answer, and the change has not appeared")),
        )
    }

    @Test fun `cancellation is nothing to say`() {
        assertNull(failureNotice("change the volume", CancellationException("newer press")))
    }

    /** The codes met on hardware read as words; the code itself is not shown. */
    @Test fun `a refusal seen on hardware is said in words`() {
        assertEquals("Couldn't skip: this source doesn't allow it",
            failureNotice("skip", SonosCommandException("skipToNextTrack", "ERROR_DISALLOWED_BY_POLICY")))
        assertEquals("Couldn't play: nothing is loaded",
            failureNotice("play", SonosCommandException("play", "ERROR_PLAYBACK_NO_CONTENT")))
        assertEquals("Couldn't play that again: it isn't available from this system's accounts",
            failureNotice("play that again", SonosCommandException("loadContent", "ERROR_ACCOUNT_INVALID_ID")))
    }

    /** With the player's reason after it, as `errorOrNull` joins them. */
    @Test fun `a mapped code is worded whatever reason follows it`() {
        assertEquals("Couldn't seek: this source doesn't allow it",
            failureNotice("seek", SonosCommandException("seek", "ERROR_DISALLOWED_BY_POLICY: History is disabled")))
    }

    @Test fun `a code nobody has met stays as the player sent it`() {
        assertEquals("Couldn't add Guest TV: ERROR_COMMAND_FAILED: busy",
            failureNotice("add Guest TV", SonosCommandException("modifyGroupMembers", "ERROR_COMMAND_FAILED: busy")))
    }
}
