package com.rahga.x2rock.lan

import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.RepeatModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * Behaviour of the live household, against a [FakePlayer] rather than real speakers.
 *
 * These are the checks that previously existed only as throwaway scripts pointed at one
 * particular set of hardware on one particular network.
 */
class SonosHouseholdTest {

    private lateinit var fake: FakePlayer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var seeds: InMemorySeedStore

    private class InMemorySeedStore(private var held: Discovery.DiscoveredPlayer? = null) : SeedStore {
        override fun load() = held
        override fun save(player: Discovery.DiscoveredPlayer) { held = player }
        override fun clear() { held = null }
        fun peek() = held
    }

    /** Always seeded: an unseeded connect would run real SSDP and find the actual house. */
    private fun seedFor(player: FakePlayer) = Discovery.DiscoveredPlayer(
        id = player.id,
        address = InetAddress.getByName("127.0.0.1"),
        householdId = player.householdId,
    )

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        scope = CoroutineScope(SupervisorJob())
        seeds = InMemorySeedStore(seedFor(fake))
        val book = PlayerAddressBook()
        household = SonosHousehold(
            scope = scope,
            addressBook = book,
            multicast = MulticastGate.None,
            // The production client: trust manager, address book, default hostname verifier.
            client = LanHttp.client(book),
            seeds = seeds,
            port = fake.port,
        )
    }

    @After fun tearDown() {
        household.disconnect()
        scope.cancel()
        fake.shutdown()
    }

    private fun connected() = runBlocking {
        household.connect(seedFor(fake))
        withTimeout(5_000) { household.state.first { it.connected } }
    }

    /**
     * The handshake is made to the `sonos-<MAC>.local` name the certificate is issued for,
     * with the address supplied by the address book — so this passing means hostname
     * verification passed honestly, not that it was disabled.
     */
    @Test fun `connects by the certificate hostname and learns the household`() {
        val state = connected()
        assertEquals(fake.householdId, state.householdId)
        // The captured topology, five groups and all, not a convenient single one.
        assertEquals(5, state.groups.size)
        assertTrue(state.groups.any { it.name == "Dining Room" })
    }

    @Test fun `subscribes to the group-scoped namespaces on the coordinator`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id
        listOf("playback:1", "playbackMetadata:1", "groupVolume:1").forEach { namespace ->
            val cmd = fake.awaitCommand {
                it.get("namespace")?.asString == namespace && it.get("command")?.asString == "subscribe"
            }
            // Group scope, addressed to the group — not a playerId.
            assertEquals(groupId, cmd.get("groupId").asString)
            assertNull(cmd.get("playerId"))
        }
    }

    @Test fun `a pushed event with no success reaches the state flow`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id

        fake.push("groupVolume:1", "groupVolume", """{"volume":37,"muted":false,"fixed":false}""", groupId)
        val volume = withTimeout(5_000) {
            household.groupStates.first { it[groupId]?.volume != null }[groupId]!!.volume!!
        }
        assertEquals(37, volume.volume)
    }

    /** Regression: absent playModes used to reset shuffle and repeat to off. */
    @Test fun `a playback event without playModes leaves the play mode alone`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id

        fake.push(
            "playback:1", "playbackStatus",
            """{"playbackState":"PLAYBACK_STATE_PLAYING","playModes":{"shuffle":true,"repeat":true}}""",
            groupId,
        )
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.playMode?.shuffle == true } }

        // A later event that says nothing about play modes must not clear them.
        fake.push("playback:1", "playbackStatus", """{"positionMillis":1234}""", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.positionMillis == 1234L } }

        val mode = household.groupState(groupId).playMode
        assertTrue("shuffle was cleared by an unrelated event", mode.shuffle)
        assertEquals(RepeatModes.ALL, mode.repeat)
    }

    /** Regression: positionUpdatedAt used to move even with no position, rewinding the UI. */
    @Test fun `an event without a position does not restart the progress clock`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id

        fake.push("playback:1", "playbackStatus", """{"positionMillis":5000}""", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.positionMillis == 5000L } }
        val stampedAt = household.groupState(groupId).positionUpdatedAt

        fake.push("playback:1", "playbackStatus", """{"playbackState":"PLAYBACK_STATE_PAUSED"}""", groupId)
        withTimeout(5_000) {
            household.groupStates.first { it[groupId]?.playbackState == PlaybackStates.PAUSED }
        }
        assertEquals(stampedAt, household.groupState(groupId).positionUpdatedAt)
    }

    /** Regression: a groups event omits playbackState, which used to arrive as null. */
    @Test fun `a groups event without playbackState never yields null`() = runBlocking {
        connected()
        fake.push(
            "groups:1", "groups",
            """{"groups":[{"id":"G:2","name":"Kitchen","coordinatorId":"${fake.id}","playerIds":["${fake.id}"]}],
                "players":[{"id":"${fake.id}","name":"Kitchen"}]}""",
        )
        val group = withTimeout(5_000) {
            household.state.first { st -> st.groups.any { it.id == "G:2" } }.groups.first { it.id == "G:2" }
        }
        assertNotNull(group.playbackState)
    }

    @Test fun `a command is addressed to the group and the player answers`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id
        household.togglePlayPause(groupId)
        val cmd = fake.awaitCommand { it.get("command")?.asString == "togglePlayPause" }
        assertEquals("playback:1", cmd.get("namespace").asString)
        assertEquals(groupId, cmd.get("groupId").asString)
    }

    /** The whole point of the seed store: a warm start skips discovery. */
    @Test fun `a successful connect is remembered`() {
        connected()
        val remembered = seeds.peek()
        assertNotNull(remembered)
        assertEquals(fake.id, remembered!!.id)
        assertEquals(fake.householdId, remembered.householdId)
    }

    /**
     * The socket dying must rebuild the session rather than leave it silently dead — the
     * behaviour that `connect` being one-shot used to get wrong.
     */
    @Test fun `a dropped socket reconnects and resubscribes`() = runBlocking<Unit> {
        connected()
        fake.received.clear()

        fake.dropConnection()

        // Backoff starts at a second, so allow for it.
        withTimeout(15_000) { household.state.first { !it.connected } }
        val state = withTimeout(15_000) { household.state.first { it.connected } }
        assertEquals(5, state.groups.size)

        // And it is a real rebuild: the subscriptions are established again.
        fake.awaitCommand(timeoutMillis = 5_000) {
            it.get("command")?.asString == "subscribe" && it.get("namespace")?.asString == "playback:1"
        }
    }

    @Test fun `disconnect stops everything and does not reconnect`() = runBlocking {
        connected()
        household.disconnect()
        assertTrue(household.state.value.groups.isEmpty())
        assertTrue(household.groupStates.value.isEmpty())

        // Nothing should be trying to come back up.
        val stillDown = withTimeout(3_000) { household.state.first { !it.connected } }
        assertTrue(!stillDown.connected)
        assertEquals(0, scope.coroutineContext[Job]!!.children.count { it.isActive && !it.isCompleted }.coerceAtMost(0))
    }
}
