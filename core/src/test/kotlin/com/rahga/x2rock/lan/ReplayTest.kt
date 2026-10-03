package com.rahga.x2rock.lan

import com.rahga.x2rock.model.PlaybackStates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

/**
 * Recently played, and playing one again. The list is the office One SL's own history,
 * captured 2026-10-01: eight items, Radio Paradise programs, an album, and a playlist that
 * had been deleted by then.
 */
class ReplayTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope, addressBook = book, multicast = MulticastGate.None,
            client = LanHttp.client(book), port = fake.port, settleMillis = 2_000,
        )
        runBlocking {
            household.connect(Discovery.DiscoveredPlayer(fake.id, InetAddress.getByName("127.0.0.1"), fake.householdId))
            withTimeout(5_000) { household.state.first { it.connected } }
        }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    private fun group() = household.state.value.groups.first { it.coordinatorId == fake.id }.id

    @Test fun `history lists the household's items with their ids and reachable art`() = runBlocking<Unit> {
        val items = household.history()
        assertEquals(8, items.size)
        val first = items.first()
        assertEquals("Mellow Mix", first.name)
        assertEquals("program", first.type)
        assertEquals("308", first.id.serviceId)
        val art = items.first { it.images.isNotEmpty() }.images.first().url!!
        assertTrue("player-relative art was not made reachable: $art", art.startsWith("http://sonos-") && ".local:1400/getaa" in art)
    }

    /** Loaded, then pressed to play until the room says it is playing — not before. */
    @Test fun `replaying loads the item and presses play until it plays`() = runBlocking<Unit> {
        val item = household.history().first()
        val groupId = group()
        val call = scope.async(Dispatchers.IO) { household.replay(groupId, item) }
        fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "loadContent" }
        val body = fake.lastCommandBody("loadContent")!!
        assertEquals("program", body.get("type").asString)
        assertEquals("channel:1:3:resume", body.getAsJsonObject("id").get("objectId").asString)
        assertEquals("sn_20", body.getAsJsonObject("id").get("accountId").asString)
        fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "play" }
        assertTrue("finished before the room said it was playing", !call.isCompleted)
        fake.pushFixture("radioPlaybackStatus", groupId)
        withTimeout(5_000) { call.await() }
        assertEquals(PlaybackStates.PLAYING, household.groupState(groupId).playbackState)
    }

    /** A press that lands mid-load is refused that way, and is pressed again, not given up on. */
    @Test fun `a play refused while the load is under way is pressed again`() = runBlocking<Unit> {
        val item = household.history().first()
        val groupId = group()
        fake.refuse("play", "ERROR_PLAYBACK_NO_CONTENT")
        val call = scope.async(Dispatchers.IO) { runCatching { household.replay(groupId, item) } }
        fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "play" }
        fake.allow("play")
        fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "play" }
        fake.pushFixture("radioPlaybackStatus", groupId)
        val result = withTimeout(5_000) { call.await() }
        assertTrue("gave up on a mid-load refusal: ${result.exceptionOrNull()}", result.isSuccess)
    }

    /**
     * No reply is not no, here as for a regroup: a coordinator busy with a load can answer the
     * load late or never and still do it. The reply timeout is the socket's five seconds, so
     * this test waits them out; what matters is that the room playing afterwards is a success.
     */
    @Test fun `a load the coordinator never answers is judged by whether the room plays`() = runBlocking<Unit> {
        val item = household.history().first()
        val groupId = group()
        fake.holdRepliesTo("loadContent")
        val call = scope.async(Dispatchers.IO) { runCatching { household.replay(groupId, item) } }
        fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "loadContent" }
        // The hold also stops the fake reading further commands, so it is lifted once the
        // client has given up waiting; the late reply then arrives for a request nobody holds.
        delay(5_500)
        fake.releaseReplies()
        fake.awaitCommand(timeoutMillis = 10_000) { it.get("command")?.asString == "play" }
        fake.pushFixture("radioPlaybackStatus", groupId)
        val result = withTimeout(10_000) { call.await() }
        assertTrue("a lost reply was taken for a refusal: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test fun `an item that loads and never plays is a failure`() = runBlocking<Unit> {
        val item = household.history().first()
        val failure = runCatching { household.replay(group(), item) }.exceptionOrNull()
        assertTrue("expected a failure, got $failure", failure is IOException && "did not start" in failure.message!!)
        assertTrue("play was pressed once and left", fake.commandsNamed("play") > 1)
    }

    /** A refusal is the player's answer, said at once — a deleted playlist, an anonymous service. */
    @Test fun `a refused load fails with the player's words`() = runBlocking<Unit> {
        fake.refuse("loadContent", "ERROR_ACCOUNT_INVALID_ID")
        val item = household.history().first { it.type == "playlist" }
        val failure = runCatching { household.replay(group(), item) }.exceptionOrNull()
        assertTrue("expected the refusal, got $failure", failure is SonosCommandException && "ERROR_ACCOUNT_INVALID_ID" in failure.message!!)
        assertEquals(0, fake.commandsNamed("play"))
    }
}
