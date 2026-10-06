package com.rahga.x2rock.smapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the household-token decrypt, ported from x2rock's `sonos/stored.rs` test module.
 *
 * The decrypt is exercised against its own inverse: [seal] builds a real `2:` envelope from
 * account XML with the same salt and key derivation, so the whole path is tested with **no
 * live household and no real token** — the discipline the project keeps, the crypto proven by
 * round trip rather than by a captured secret no test file should hold. The account-XML shapes
 * are the ones x2rock recorded off real households (the two-account iHeartRadio element, the
 * empty-token anonymous placeholder); only the token strings are synthetic.
 */
class StoredAccountsTest {

    private companion object {
        // A household id with no special bytes; the real ones look like `Sonos_...`.
        const val HH = "Sonos_TestHouseholdId0000000000"

        // 31 * 256 + 7 = 7943 (Qobuz-shaped), 894 * 256 = 228864 (80er-Radio), 164 * 256 (Saavn).
        const val XML = """<ThirdPartyMediaServers>
            <MediaServer UDN="SA_RINCON7943_X" SerialNum0="14" Token0="qb-token" Key0="qb-key" Nickname0="Qb1" Tier0="0"/>
            <MediaServer UDN="SA_RINCON228864_X" SerialNum0="15" Token0="ytm-token" Key0="ytm-key" Nickname0="Hhh"/>
            <MediaServer UDN="SA_RINCON41984_X" SerialNum0="9" Token0="" Key0="" Nickname0=""/>
        </ThirdPartyMediaServers>"""

        /** Sealing lives in [TestEnvelope] now, shared with the integration tests. */
        fun seal(accountXml: String, householdId: String, iv: ByteArray): String =
            TestEnvelope.seal(accountXml, householdId, iv)
    }

    @Test fun `a sealed envelope round trips to its accounts`() {
        val accounts = StoredAccounts.decryptAccounts(seal(XML, HH, ByteArray(16) { 7 }), HH)
        assertEquals(3, accounts.size)
        val qobuz = accounts[0]
        assertEquals(31L, qobuz.serviceId)
        assertEquals(14, qobuz.serial)
        assertEquals("qb-token", qobuz.token)
        assertEquals("qb-key", qobuz.key)
        assertEquals("Qb1", qobuz.nickname)
        assertTrue(qobuz.hasToken)
        // No Username0 in this vector, so no account key — absent, not defaulted.
        assertEquals("", qobuz.accountKey)
    }

    @Test fun `the service id is decoded from the udn`() {
        val accounts = StoredAccounts.decryptAccounts(seal(XML, HH, ByteArray(16) { 1 }), HH)
        assertEquals(894L, accounts[1].serviceId)
        assertEquals("ytm-token", accounts[1].token)
    }

    @Test fun `an empty token account is kept but flagged`() {
        val accounts = StoredAccounts.decryptAccounts(seal(XML, HH, ByteArray(16) { 2 }), HH)
        val anon = accounts[2]
        assertEquals(9, anon.serial)
        assertFalse("empty Token0 is not a usable credential", anon.hasToken)
    }

    @Test fun `an element holding two accounts yields both`() {
        // What NumAccounts is for — pinned with a synthetic vector, since the two iHeartRadio
        // accounts x2rock saw arrived as two elements rather than this shape.
        val two = """<ThirdPartyMediaServers>
            <MediaServer UDN="SA_RINCON1543_X" NumAccounts="2"
              SerialNum0="24" Token0="ihr-a" Key0="" Nickname0="iHeartRadio"
              Username0="X_#Svc1543-81dee58d-Token" Flags0="4" Tier0="0"
              SerialNum1="25" Token1="ihr-b" Key1="" Nickname1="iHeartRadio 885ebbcc"
              Username1="X_#Svc1543-885ebbcc-Token" Flags1="4" Tier1="0"/>
        </ThirdPartyMediaServers>"""
        val accounts = StoredAccounts.decryptAccounts(seal(two, HH, ByteArray(16) { 3 }), HH)
        assertEquals("both indices are read", 2, accounts.size)
        assertEquals(24, accounts[0].serial)
        assertEquals("ihr-a", accounts[0].token)
        assertEquals("81dee58d", accounts[0].accountKey)
        assertEquals(25, accounts[1].serial)
        assertEquals("iHeartRadio 885ebbcc", accounts[1].nickname)
        assertEquals("885ebbcc", accounts[1].accountKey)
        assertEquals(accounts[0].serviceId, accounts[1].serviceId)
    }

    @Test fun `a count larger than what is there stops at the real end`() {
        val over = """<ThirdPartyMediaServers>
            <MediaServer UDN="SA_RINCON7943_X" NumAccounts="3"
              SerialNum0="14" Token0="qb-token" Key0="qb-key" Nickname0="Qb1"/>
        </ThirdPartyMediaServers>"""
        val accounts = StoredAccounts.decryptAccounts(seal(over, HH, ByteArray(16) { 5 }), HH)
        assertEquals("no empty records padded onto the end", 1, accounts.size)
        assertEquals("qb-token", accounts[0].token)
    }

    @Test fun `an account key is read out of the username`() {
        assertEquals("885ebbcc", StoredAccounts.accountKeyIn("X_#Svc1543-885ebbcc-Token"))
        // A service with no per-account key writes a literal zero there.
        assertEquals("0", StoredAccounts.accountKeyIn("X_#Svc44551-0-Token"))
        // An unfamiliar shape is carried whole rather than silently emptied.
        assertEquals("something-else", StoredAccounts.accountKeyIn("something-else"))
    }

    @Test fun `the raw view keeps attributes nothing models`() {
        val odd = """<ThirdPartyMediaServers>
            <MediaServer UDN="SA_RINCON7943_X" SerialNum0="14" Token0="t" Key0="k"
              Md0="" Flags0="4" Whatever0="new-in-some-firmware"/>
            <NotAnAccount Name="no UDN, not a record"/>
        </ThirdPartyMediaServers>"""
        val raw = StoredAccounts.decryptElements(seal(odd, HH, ByteArray(16) { 9 }), HH)
        assertEquals("only elements carrying a UDN are records", 1, raw.size)
        assertEquals("MediaServer", raw[0].tag)
        val attrs = raw[0].attributes.toMap()
        assertEquals("4", attrs["Flags0"])
        assertEquals("", attrs["Md0"])
        assertEquals("new-in-some-firmware", attrs["Whatever0"])
    }

    @Test fun `the wrong household id fails the integrity check not silently`() {
        val envelope = seal(XML, HH, ByteArray(16) { 3 })
        val err = runCatching { StoredAccounts.decryptAccounts(envelope, "Sonos_ADifferentHousehold000000") }
            .exceptionOrNull()
        val msg = (err?.message ?: "").lowercase()
        assertTrue("a wrong key should be named as such, got: $msg", "integrity" in msg || "padding" in msg)
    }

    @Test fun `a non envelope is refused by its prefix`() {
        val err = runCatching { StoredAccounts.decryptAccounts("not-a-2-colon-thing", HH) }.exceptionOrNull()
        assertTrue((err?.message ?: "").contains("envelope version"))
    }
}
