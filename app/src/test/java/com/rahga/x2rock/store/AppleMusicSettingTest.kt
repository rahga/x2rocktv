package com.rahga.x2rock.store

import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.fakeHousehold
import com.rahga.x2rock.smapi.TestEnvelope
import com.rahga.x2rock.viewmodel.FakePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Apple Music is listed where the speakers store its sign-in, and otherwise only when the Advanced
 * switch is on (owner's rule, 2026-10-18).
 */
class AppleMusicSettingTest {
    private val fake = FakePlayer().also { it.start() }
    private val scope = CoroutineScope(SupervisorJob())
    private var household: SonosHousehold? = null

    private fun connected(appleStored: Boolean): SonosHousehold = runBlocking {
        val xml = if (appleStored) """<ThirdPartyMediaServers><MediaServer UDN="SA_RINCON52231_X" SerialNum0="19" Token0="t" Key0="" Nickname0="R"/></ThirdPartyMediaServers>"""
        else """<ThirdPartyMediaServers><MediaServer UDN="SA_RINCON7943_X" SerialNum0="14" Token0="t" Key0="k" Nickname0="Q"/></ThirdPartyMediaServers>"""
        val envelope = TestEnvelope.seal(xml, fake.householdId.substringBefore('.'))
        fakeHousehold(fake, scope, addressBook = PlayerAddressBook(), accountCapture = { envelope })
            .also { household = it; it.connectTo(fake) }
    }

    @After fun tearDown() {
        household?.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    @Test fun `a stored Apple Music sign-in lists it, switch or not`() = runBlocking {
        val setting = AppleMusicSetting(FakePreferences(), connected(appleStored = true))
        assertTrue(setting.stored())
        assertTrue(setting.shown())
    }

    @Test fun `without one it is listed only when the switch is on`() = runBlocking {
        val setting = AppleMusicSetting(FakePreferences(), connected(appleStored = false))
        assertFalse(setting.stored())
        assertFalse(setting.shown())
        setting.setForced(true)
        assertTrue(setting.shown())
    }
}
