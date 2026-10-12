package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.lan.DeviceInfo
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.HouseholdState
import com.rahga.x2rock.lan.LINE_IN
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.fakeHousehold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/**
 * A tester's report (T-21): what it says about a system, and what it must never say. The household is
 * the captured one; each speaker's description is Kitchen's captured one, serial and all, so leaving
 * the serial out is something the report does rather than something the input spares it.
 */
class BetaReportTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold

    @Before fun setUp() = runBlocking<Unit> {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
        household = fakeHousehold(fake, scope)
        household.connectTo(fake, 10_000)
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    /** `device_description.kitchen.xml`, captured 2026-10-10. */
    private val kitchenDescription = DeviceInfo("Sonos One SL", "18.8", "97.1-80312", "1.28.1.6-1.1", "00-00-00-00-00-01:E")

    private fun speakers(state: HouseholdState) =
        state.groups.flatMap { it.playerIds }.distinct().map { SonosHousehold.SpeakerAbout(it, household.playerName(it), kitchenDescription, null) }

    private fun report(state: HouseholdState = household.state.value, refusal: String? = null) =
        betaReport("x2rocktv 1.0 (2)", "NVIDIA SHIELD Android TV, Android 11", state, household.groupStates.value, speakers(state), refusal)

    @Test fun `it says what each speaker is, and holds no identifier`() {
        val state = household.state.value
        val r = report(state, refusal = "Line-In (Kitchen): ERROR_NOT_CAPABLE")
        assertTrue(r.text, r.text.contains("- Kitchen: One SL 18.8 (97.1-80312)"))
        assertTrue(r.text, r.text.contains("PLAYBACK"))
        assertTrue(r.text, r.text.contains("**Last refusal:** Line-In (Kitchen): ERROR_NOT_CAPABLE"))
        val decoded = URLDecoder.decode(r.url, "UTF-8")
        for (secret in state.players.map { it.id } + listOfNotNull(state.householdId) + "00-00-00-00-00-01:E") {
            assertFalse("$secret in the report", r.text.contains(secret) || decoded.contains(secret))
        }
    }

    /**
     * The issue opens holding exactly what the screen showed, even where the text holds what a query
     * means something by: a room called "Rock & Roll" would otherwise end the body at its `&`.
     */
    @Test fun `the link carries the text as shown`() {
        val r = report(refusal = "Rock & Roll #2: a+b=c 100% ü")
        assertTrue(r.url.startsWith("$REPORT_ISSUES?title="))
        // Split as the server does, on every `&`, rather than taking everything after "body=".
        val query = r.url.substringAfter('?').split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(setOf("title", "body"), query.keys)
        assertEquals(r.text, query["body"])
    }

    /** A room with a line-in says what it is reporting now: the capture P-14 is owed. */
    @Test fun `a room with a line-in says what it reports`() {
        val kitchen = household.state.value.groups.first { it.name == "Kitchen" }
        val withLineIn = household.state.value.let { s ->
            s.copy(players = s.players.map { p -> if (p.id == kitchen.coordinatorId) p.copy(capabilities = p.capabilities + LINE_IN) else p })
        }
        val text = report(withLineIn).text
        assertTrue(text, text.contains("**Rooms with a line-in, now**\n- Kitchen: "))
        assertTrue(text, text.contains("LINE_IN"))
    }

    /** A household too big for one code loses capabilities first, then its end, and says so either way. */
    @Test fun `a large household is cut to fit the code`() {
        val state = household.state.value
        val many = (1..40).map { n -> SonosHousehold.SpeakerAbout(state.players.first().id, "Room $n", kitchenDescription, null) }
        val r = betaReport("x2rocktv 1.0 (2)", "TV", state, household.groupStates.value, many, null)
        assertTrue("${r.url.length}", r.url.length <= REPORT_URL_LIMIT)
        assertTrue(r.text, r.text.contains("(capabilities left out to fit, but a line-in)"))
        assertTrue(r.text, r.text.endsWith("(cut to fit)"))
    }
}
