package com.rahga.x2rock.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * Playing a directory station: a session, a load, and then finding out whether it played,
 * because the player accepts a URL it cannot play and says nothing.
 */
class StreamTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold

    private val url = "https://ice5.somafm.com/groovesalad-128-mp3"
    private val sessionId = "RINCON_AA02BBCCDDEE01400:1546804709@4153230933"

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope, addressBook = book, multicast = MulticastGate.None,
            client = LanHttp.client(book), port = fake.port,
        )
        runBlocking {
            household.connect(Discovery.DiscoveredPlayer(fake.id, InetAddress.getByName("127.0.0.1"), fake.householdId))
            withTimeout(10_000) { household.state.first { it.connected } }
        }
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    private val kitchen get() = household.state.value.groups.first { it.name == "Kitchen" }

    private fun push(state: String) {
        val body = FakePlayer.fixture("event.playbackStatus.json").apply { addProperty("playbackState", state) }
        fake.push("playback:1", "playbackStatus", body.toString(), kitchen.id)
    }

    /**
     * Wait for the load to reach the player, so what is pushed next comes after it. Blocking,
     * which is why each [SonosHousehold.playStream] here runs on another dispatcher.
     */
    private fun awaitLoad() = fake.awaitCommand(5_000) { it.get("command")?.asString == "loadStreamUrl" }

    @Test fun `a session is opened on the group and the stream loaded in it`() = runBlocking<Unit> {
        val started = async(Dispatchers.Default) { household.playStream(kitchen.id, url, "SomaFM Groove Salad") }
        val opened = fake.awaitCommand(5_000) { it.get("command")?.asString == "createSession" }
        assertEquals(kitchen.id, opened.get("groupId").asString)
        val load = awaitLoad()
        assertEquals("addressed by the session the player named", sessionId, load.get("sessionId").asString)
        assertFalse(load.has("groupId"))
        val body = fake.lastCommandBody("loadStreamUrl")!!
        assertEquals(url, body.get("streamUrl").asString)
        assertEquals(true, body.get("playOnCompletion").asBoolean)
        assertEquals("SomaFM Groove Salad", body.getAsJsonObject("stationMetadata").get("name").asString)

        push("PLAYBACK_STATE_BUFFERING")
        push("PLAYBACK_STATE_PLAYING")
        assertEquals(StreamStart.PLAYING, started.await())
    }

    /** The silent failure: accepted, then idle. */
    @Test fun `a stream the room cannot play is reported as silent`() = runBlocking<Unit> {
        val started = async(Dispatchers.Default) { household.playStream(kitchen.id, url, "Dead", startMillis = 1_500) }
        awaitLoad()
        push("PLAYBACK_STATE_IDLE")
        assertEquals(StreamStart.SILENT, started.await())
    }

    /** Buffering when the wait ran out is a slow stream, not a broken one. */
    @Test fun `a stream still buffering is reported as starting`() = runBlocking<Unit> {
        val started = async(Dispatchers.Default) { household.playStream(kitchen.id, url, "Slow", startMillis = 1_500) }
        awaitLoad()
        push("PLAYBACK_STATE_BUFFERING")
        assertEquals(StreamStart.STARTING, started.await())
    }

    /**
     * The room was already playing something else. That PLAYING is the old stream's, and taken
     * for the new one it would report a dead station as playing.
     */
    @Test fun `a room already playing must be seen to change before it counts`() = runBlocking<Unit> {
        push("PLAYBACK_STATE_PLAYING")
        withTimeout(5_000) { household.groupStates.first { it[kitchen.id]?.playbackState == "PLAYBACK_STATE_PLAYING" } }

        val unchanged = async(Dispatchers.Default) { household.playStream(kitchen.id, url, "Same", startMillis = 1_500) }
        awaitLoad()
        push("PLAYBACK_STATE_PLAYING")
        assertEquals(StreamStart.STARTING, unchanged.await())

        val changed = async(Dispatchers.Default) { household.playStream(kitchen.id, url, "Next") }
        awaitLoad()
        push("PLAYBACK_STATE_BUFFERING")
        push("PLAYBACK_STATE_PLAYING")
        assertEquals(StreamStart.PLAYING, changed.await())
    }
}
