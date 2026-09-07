package com.rahga.x2rock.lan

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * The other half of the story: real speakers.
 *
 * [FakePlayer] catches regressions in this code, but it only knows what it was told, so it
 * cannot discover protocol truth. Everything this project learned the hard way came from
 * hardware. These are the checks that used to be written by hand and thrown away each time;
 * committed, they make a real household a repeatable instrument.
 *
 * ```sh
 * ./gradlew :core:test -Dx2rock.live=discover          # find a player, read-only
 * ./gradlew :core:test -Dx2rock.live=192.168.86.25     # a specific player
 * ./gradlew :core:test -Dx2rock.live=discover -Dx2rock.live.room=Kitchen
 * ```
 *
 * **Read-only unless a room is named.** These may be run against a household someone is
 * listening to; nothing here changes playback or volume without `x2rock.live.room`, and
 * what does is restored afterwards.
 *
 * **Assertions are about the protocol, not about one house.** They have to hold for a
 * single Sonos One SL on a desk as much as for five rooms with a soundbar, so nothing here
 * assumes a room name, a group count, or a populated queue.
 */
class LiveHouseholdTest {

    private val target: String? = System.getProperty("x2rock.live")?.takeIf { it.isNotBlank() }
    private val mutableRoom: String? = System.getProperty("x2rock.live.room")?.takeIf { it.isNotBlank() }

    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private var seed: Discovery.DiscoveredPlayer? = null

    @Before fun requireHardware() {
        assumeTrue("set -Dx2rock.live=<ip|discover> to run against real speakers", target != null)
        scope = CoroutineScope(SupervisorJob())
        household = SonosHousehold(scope)
        seed = runBlocking { locate() }
    }

    @After fun tearDown() {
        if (target == null) return
        household.disconnect()
        scope.cancel()
    }

    private suspend fun locate(): Discovery.DiscoveredPlayer {
        if (target == "discover") {
            return Discovery.findPlayers(3_000).firstOrNull()
                ?: error("no players answered; is this machine on the speakers' network?")
        }
        // A named address still needs its id, and SSDP is how that is learned.
        val address = InetAddress.getByName(target)
        return Discovery.findPlayers(3_000).firstOrNull { it.address == address }
            ?: error("$target did not answer discovery")
    }

    private fun connected() = runBlocking {
        household.connect(seed)
        withTimeout(10_000) { household.state.first { it.connected } }
    }

    // ------------------------------------------------------------ invariants

    @Test fun `discovery reports an id, an address and a household`() {
        val player = seed!!
        assertTrue("player id is not a RINCON id: ${player.id}", player.id.startsWith("RINCON_"))
        assertNotNull("no hostname derivable from ${player.id}", player.hostname)
        assertNotNull("SSDP carried no HOUSEHOLD.SMARTSPEAKER.AUDIO", player.householdId)
    }

    /**
     * The load-bearing one. This connects by the `.local` name with OkHttp's default
     * hostname verifier, so it passing means the certificate really does carry that name —
     * the assumption the whole trust design rests on, checked against a real speaker.
     */
    @Test fun `a real player answers on its certificate hostname`() {
        val state = connected()
        assertNotNull(state.householdId)
        assertTrue("no groups", state.groups.isNotEmpty())
    }

    @Test fun `every group has a coordinator that is a known player`() {
        val state = connected()
        val playerIds = state.players.map { it.id }.toSet()
        state.groups.forEach { group ->
            assertTrue(
                "coordinator ${group.coordinatorId} of ${group.name} is not in players",
                group.coordinatorId in playerIds,
            )
            assertNotNull("group ${group.name} has no playbackState", group.playbackState)
        }
    }

    @Test fun `every player id yields a certificate hostname and an address`() {
        val state = connected()
        state.players.forEach { player ->
            assertNotNull("no hostname for ${player.id}", PlayerNames.localHostname(player.id))
            assertNotNull("no websocketUrl for ${player.name}", player.websocketUrl)
        }
    }

    /** Subscribing must deliver state without anything being asked for a second time. */
    @Test fun `subscriptions push a snapshot for every group`() = runBlocking<Unit> {
        connected()
        val groups = household.state.value.groups
        withTimeout(15_000) {
            household.groupStates.first { pushed -> groups.all { it.id in pushed.keys } }
        }
        groups.forEach { assertNotNull("no volume for ${it.name}", household.groupState(it.id).volume) }
    }

    /** The scoping rule, on hardware: a player-scoped command needs a real player id. */
    @Test fun `a player-scoped command with a bad id is refused, not ignored`() = runBlocking<Unit> {
        connected()
        val thrown = runCatching {
            household.setPlayerVolume("RINCON_000000000000" + "01400", 10)
        }.exceptionOrNull()
        assertNotNull("a bad playerId was accepted", thrown)
    }

    // ------------------------------------------------------------ fixture drift

    /**
     * The check that stops a green CI becoming a lie.
     *
     * [FakePlayer] replays captured payloads, so if a firmware update changes their shape,
     * every fake-backed test keeps passing while the app breaks against real speakers. This
     * compares the structure a real player sends now against the recorded fixture and fails
     * when they diverge — the signal to re-capture.
     */
    @Test fun `real payloads still match the recorded fixtures`() = runBlocking<Unit> {
        // Its own socket, so this needs no production API widened for a test's benefit.
        val player = seed!!
        val book = PlayerAddressBook().apply { register(player.hostname!!, player.address) }
        val socket = withTimeout(10_000) { SonosSocket.open(LanHttp.client(book), player.hostname!!) }
        val topology = try {
            withTimeout(10_000) {
                socket.command(Frames.onHousehold("groups:1", "getGroups", player.householdId!!))
            }
        } finally {
            socket.close()
        }

        val recorded = shapeOf(FakePlayer.fixture("getGroups.reply.json"))
        val live = shapeOf(topology)

        val missing = recorded - live
        val added = live - recorded
        assertTrue(
            "fixtures have drifted from what this player sends — re-capture them.\n" +
                "  no longer sent: ${missing.sorted()}\n" +
                "  newly sent:     ${added.sorted()}",
            missing.isEmpty() && added.isEmpty(),
        )
    }

    /**
     * Every key path in a document, ignoring values and array length.
     *
     * Unions across *all* array elements rather than sampling the first. Players differ
     * from one another — a stereo pair carries fields a single speaker does not — and the
     * order they arrive in is not stable, so sampling one made the comparison report
     * ordering as drift. That was this test's own first finding, about itself.
     */
    private fun shapeOf(element: JsonElement, prefix: String = ""): Set<String> = when {
        element.isJsonObject -> element.asJsonObject.entrySet().flatMap { (key, value) ->
            listOf("$prefix.$key") + shapeOf(value, "$prefix.$key")
        }.toSet()
        element.isJsonArray -> element.asJsonArray.flatMap { shapeOf(it, "$prefix[]") }.toSet()
        else -> emptySet()
    }

    // ------------------------------------------------------------ opt-in mutation

    /**
     * Only with `-Dx2rock.live.room=<name>`, and restored afterwards. A command's effect
     * arriving as an event is the entire premise of the push design, and it cannot be
     * observed without changing something.
     */
    @Test fun `a volume change comes back as an event`() = runBlocking<Unit> {
        assumeTrue("set -Dx2rock.live.room=<room> to allow changing a speaker", mutableRoom != null)
        connected()
        val group = household.state.value.groups.firstOrNull { it.name == mutableRoom }
            ?: error("no room named $mutableRoom in this household")

        withTimeout(10_000) { household.groupStates.first { it[group.id]?.volume != null } }
        val before = household.groupState(group.id).volume!!.volume
        val target = if (before >= 50) before - 3 else before + 3
        try {
            household.setGroupVolume(group.id, target)
            val observed = withTimeout(10_000) {
                household.groupStates.first { it[group.id]?.volume?.volume == target }
            }
            assertEquals(target, observed[group.id]!!.volume!!.volume)
        } finally {
            runCatching { household.setGroupVolume(group.id, before) }
        }
    }
}
