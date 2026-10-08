package com.rahga.x2rock.smapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which services the household's stored tokens actually unlock, matched to the catalogue. The
 * shapes mirror the home/office households x2rock read: an anonymous radio service, a
 * device-link service with a token, an app-link service with no token (the common dead end),
 * and two accounts of one service (Amazon Music, seen with two in the office store).
 */
class LinkedServicesTest {

    private fun service(id: String, auth: Auth) =
        Service(id = id, name = "Svc$id", uri = "https://$id/smapi", auth = auth, manifestUri = null)

    private fun account(serviceId: Long, serial: Int, token: String, nickname: String = "") =
        StoredAccount(serviceId, serial, token, key = "k", nickname = nickname, tier = "0", accountKey = "", flags = "4")

    @Test fun `a device-link service with a stored token is usable, carrying it`() {
        val services = listOf(service("31", Auth.APP_LINK)) // Qobuz: app-link by policy, token from store
        val linked = linkedServices(services, listOf(account(31, 14, "qb-token", "Qb1")), "Sonos_long.suffix")
        assertEquals(1, linked.size)
        assertEquals("qb-token", linked[0].token!!.token)
        assertEquals("Sonos_long.suffix", linked[0].token!!.household)
        assertEquals("sn_14", linked[0].accountId)
        assertEquals("Qb1", linked[0].nickname)
    }

    @Test fun `an anonymous service is usable with no credential`() {
        val linked = linkedServices(listOf(service("894", Auth.ANONYMOUS)), emptyList(), null)
        assertEquals(1, linked.size)
        assertNull(linked[0].token)
        assertNull(linked[0].accountId)
    }

    @Test fun `an app-link service with no stored token is not offered`() {
        val linked = linkedServices(listOf(service("2900", Auth.APP_LINK)), emptyList(), "hh")
        assertEquals("a login the app cannot drive, and no token to borrow", 0, linked.size)
    }

    @Test fun `an empty-token account does not count as a credential`() {
        // 80er-Radio shape: listed in the store but anonymous, with an empty token.
        val services = listOf(service("894", Auth.APP_LINK))
        val linked = linkedServices(services, listOf(account(894, 9, token = "")), "hh")
        assertEquals("an empty token unlocks nothing", 0, linked.size)
    }

    /**
     * The household added 80er-Radio harmony, so the Sonos app lists it among its connected
     * services; an anonymous service it never added is one of the hundred every household has.
     */
    @Test fun `an anonymous service the household added is its own, one it did not is not`() {
        val services = listOf(service("894", Auth.ANONYMOUS), service("895", Auth.ANONYMOUS))
        val linked = linkedServices(services, listOf(account(894, 9, token = "")), "hh")
        assertEquals(listOf(true, false), linked.map { it.added })
        assertNull("still no credential to send", linked[0].token)
    }

    @Test fun `two accounts of one service yield two usable entries`() {
        val services = listOf(service("201", Auth.APP_LINK)) // Amazon Music
        val linked = linkedServices(
            services,
            listOf(account(201, 17, "amz-a", "Amazon Music User 139f5452"), account(201, 18, "amz-b", "Amazon Music User")),
            "hh",
        )
        assertEquals(2, linked.size)
        assertEquals(setOf("sn_17", "sn_18"), linked.map { it.accountId }.toSet())
    }

    @Test fun `the list is alphabetical by service name`() {
        val services = listOf(service("3", Auth.ANONYMOUS), service("1", Auth.ANONYMOUS), service("2", Auth.ANONYMOUS))
            .map { it.copy(name = when (it.id) { "3" -> "Qobuz"; "1" -> "Deezer"; else -> "amazon" }) }
        val linked = linkedServices(services, emptyList(), null)
        assertEquals(listOf("amazon", "Deezer", "Qobuz"), linked.map { it.service.name })
    }
}
