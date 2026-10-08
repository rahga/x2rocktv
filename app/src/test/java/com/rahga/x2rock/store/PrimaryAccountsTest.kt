package com.rahga.x2rock.store

import com.rahga.x2rock.smapi.Auth
import com.rahga.x2rock.smapi.LinkedService
import com.rahga.x2rock.smapi.Service
import com.rahga.x2rock.smapi.Token
import com.rahga.x2rock.viewmodel.FakePreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** A service's primary account on this device: kept, listed first, and the one Search asks. */
class PrimaryAccountsTest {
    private fun linked(id: String, name: String, selector: String, serial: Int) = LinkedService(
        service = Service(id, name, "https://example/$id", Auth.APP_LINK, null),
        token = Token("t", "k", "hh"), accountId = "sn_$serial", selector = selector, nickname = "$name $selector",
    )

    // The office household's shape: Amazon Music twice, between services with one account each.
    private val audible = linked("239", "Audible", "889b2ea5", 21)
    private val amazonA = linked("201", "Amazon Music", "139f5452", 17)
    private val amazonB = linked("201", "Amazon Music", "fe30b018", 18)
    private val deezer = linked("2", "Deezer", "0", 10)
    private val all = listOf(amazonA, amazonB, audible, deezer)

    @Test fun `the primary leads its own service's accounts and nothing else moves`() {
        val primaries = mapOf("201" to "fe30b018")
        assertEquals(listOf(amazonB, amazonA, audible, deezer), withPrimariesFirst(all, primaries))
        assertEquals(all, withPrimariesFirst(all, emptyMap()))
    }

    @Test fun `search asks a service's primary alone, and every account where none is set`() {
        assertEquals(listOf(amazonB, audible, deezer), searchedAccounts(all, mapOf("201" to "fe30b018")))
        assertEquals(all, searchedAccounts(all, emptyMap()))
    }

    /** An account removed in the Sonos app must not take its service out of search with it. */
    @Test fun `a primary no longer stored leaves every account searched`() {
        assertEquals(all, searchedAccounts(all, mapOf("201" to "gone")))
    }

    @Test fun `the choice is kept on the device, by selector`() {
        val prefs = FakePreferences()
        PrimaryAccounts(prefs).makePrimary(amazonA)
        assertEquals(mapOf("201" to "139f5452"), PrimaryAccounts(prefs).primaries.value)
    }
}
