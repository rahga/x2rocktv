package com.rahga.x2rock.lan

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The handshake, held to the two rules a real player enforces.
 *
 * Both were expensive to establish — a player answers 403 if an `Origin` header is present
 * and 400 if the API key is missing — and until now they lived only in a comment. The
 * [FakePlayer] enforces them, so a change that broke either would fail here rather than on
 * someone's television.
 */
class SonosSocketTest {

    private lateinit var fake: FakePlayer
    private lateinit var book: PlayerAddressBook

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        book = PlayerAddressBook().apply { register(fake.hostname, "127.0.0.1") }
    }

    @After fun tearDown() = fake.shutdown()

    private fun open() = runBlocking {
        withTimeout(5_000) { SonosSocket.open(LanHttp.client(book), fake.hostname, fake.port) }
    }

    @Test fun `the upgrade carries the api key and the subprotocol`() {
        open().close()
        val upgrade = requireNotNull(fake.lastUpgrade)
        assertEquals(SonosSocket.API_KEY, upgrade.headers["X-Sonos-Api-Key"])
        assertEquals(SonosSocket.SUBPROTOCOL, upgrade.headers["Sec-WebSocket-Protocol"])
    }

    /** The trap: a player answers 403 to any handshake carrying an Origin. */
    @Test fun `the upgrade sends no Origin header`() {
        open().close()
        assertNull(fake.lastUpgrade!!.headers["Origin"])
        assertTrue("the player refused a handshake", fake.rejected.isEmpty())
    }

    /** Verified by name, not by address — which is why the address book exists. */
    @Test fun `it connects to the certificate hostname`() {
        val socket = open()
        assertEquals(fake.hostname, socket.hostname)
        socket.close()
    }

    @Test fun `a refusal surfaces as SonosCommandException carrying the reason`() = runBlocking<Unit> {
        val socket = open()
        val thrown = runCatching {
            withTimeout(5_000) {
                socket.command(Frames.onPlayer("playerVolume:1", "getVolume", "RINCON_NOPE"))
            }
        }.exceptionOrNull()
        socket.close()
        // Asserted outright. An earlier version allowed `thrown == null`, which combined
        // with a fake that said yes to everything meant this passed without a refusal ever
        // happening — a test that could not fail.
        assertTrue("expected a refusal, got $thrown", thrown is SonosCommandException)
        assertTrue(
            "the player's own error code should survive: ${thrown!!.message}",
            thrown.message!!.contains("ERROR_INVALID_OBJECT_ID"),
        )
    }

    @Test fun `closing on purpose reports no failure`() = runBlocking<Unit> {
        val socket = open()
        socket.close()
        // A deliberate close must not look like a loss, or disconnect() would immediately
        // trigger the reconnect it is meant to prevent.
        val sawFailure = runCatching {
            withTimeout(1_000) { socket.failures.first() }
        }.isSuccess
        assertTrue("a close we asked for was reported as a failure", !sawFailure)
    }
}
