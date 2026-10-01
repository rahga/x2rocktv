package com.rahga.x2rock.viewmodel

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

    @Test fun `any other failure carries its own words`() {
        assertEquals(
            "Couldn't add Guest TV: Kitchen did not answer, and the change has not appeared",
            failureNotice("add Guest TV", IOException("Kitchen did not answer, and the change has not appeared")),
        )
    }

    @Test fun `cancellation is nothing to say`() {
        assertNull(failureNotice("change the volume", CancellationException("newer press")))
    }
}
