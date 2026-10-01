package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * A seed that carries no household — any seed not found by SSDP — learns it from the
 * player's `/status/zp`, the long form the Control API accepts.
 *
 * The player here ignores the old way of asking, a deliberately malformed frame, as a One SL
 * on p20.96.1 did: no reply at all, so a connect that relied on it hung its timeout and
 * failed. `status.zp.xml` is the office One SL's document, redacted to the fake's ids.
 */
class HouseholdIdTest {

    private lateinit var fake: FakePlayer
    private lateinit var status: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        fake.ignoreHouseholdProbe = true
        val zp = javaClass.getResourceAsStream("/fixtures/status.zp.xml")!!.readBytes().decodeToString()
        status = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    if (request.path == "/status/zp") MockResponse().setBody(zp) else MockResponse().setResponseCode(404)
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope,
            addressBook = book,
            multicast = MulticastGate.None,
            client = LanHttp.client(book),
            port = fake.port,
            upnpPort = status.port,
        )
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
        status.shutdown()
    }

    @Test fun `a seed with no household learns the long id from status zp`() = runBlocking<Unit> {
        household.connect(Discovery.DiscoveredPlayer(fake.id, InetAddress.getByName("127.0.0.1"), householdId = null))
        val state = withTimeout(10_000) { household.state.first { it.connected } }
        assertEquals(fake.householdId, state.householdId)
    }
}
