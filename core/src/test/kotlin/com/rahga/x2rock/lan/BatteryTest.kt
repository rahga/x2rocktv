package com.rahga.x2rock.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A portable's battery off its `/status/batterystatus` page. */
class BatteryTest {

    /** Kitchen's, 2026-10-10: a speaker on mains answers 200 with an empty document. */
    @Test fun `a speaker on mains has no battery`() {
        assertNull(parseBattery(com.rahga.x2rock.lan.FakePlayer.fixtureText("status.batterystatus.none.xml")))
    }

    /**
     * **Not a capture**: x2rock's example of a portable's block, used because no household here has a
     * portable (PUNCH P-16). It pins the parse, not the protocol; replace it with a Roam's reply.
     */
    @Test fun `a portable's pack is read, and charging unless on the pack`() {
        val body = """<?xml version="1.0" ?><ZPSupportInfo><LocalBatteryStatus>
            <Data name="Health">GREEN</Data><Data name="Level">83</Data>
            <Data name="Temperature">NORMAL</Data><Data name="PowerSource">SONOS_CHARGING_RING</Data>
            </LocalBatteryStatus></ZPSupportInfo>"""
        val battery = parseBattery(body)!!
        assertEquals(83, battery.level)
        assertEquals("GREEN", battery.health)
        assertEquals(true, battery.charging)
        assertEquals(false, parseBattery("""<ZPSupportInfo><LocalBatteryStatus><Data name="Level">12</Data><Data name="PowerSource">BATTERY</Data></LocalBatteryStatus></ZPSupportInfo>""")!!.charging)
    }
}
