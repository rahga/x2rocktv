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
 * ./gradlew :core:test -Dx2rock.live=192.168.86.25     # a specific player, no SSDP needed
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
                ?: error(
                    "no players answered SSDP. Some networks drop it — one office LAN was " +
                        "found to forward mDNS and block 239.255.255.250:1900 outright, with " +
                        "port 1443 reachable the whole time. Name an address instead: " +
                        "-Dx2rock.live=<ip>"
                )
        }
        // A named address needs its id, and SSDP is *not* the only way to learn it: the
        // player publishes it in its device description on cleartext 1400, which is reachable
        // wherever the speaker is. That matters because a named address is the fallback for
        // exactly the networks where discovery does not work, so routing it back through
        // SSDP made the escape hatch useless. Household id is left null — the socket learns
        // it from the first reply header.
        val address = InetAddress.getByName(target)
        return Discovery.DiscoveredPlayer(
            id = describe(address),
            address = address,
            // Asked for here rather than left null. `SonosSocket.householdId()` is the
            // fallback when a seed carries none, and it does not work everywhere: it sends a
            // deliberately malformed frame and reads the household out of the error reply,
            // and one One SL on p20.96.1 simply ignored the frame — no reply at all, so the
            // connect hung its timeout and failed. That path is never reached at home
            // because SSDP always supplies the household, which is why it went unnoticed.
            householdId = household(address),
        )
    }

    /** The `<UDN>uuid:RINCON_…</UDN>` a player serves at `/xml/device_description.xml`. */
    private fun describe(address: InetAddress): String =
        Regex("<UDN>uuid:(RINCON_[0-9A-Fa-f]+)</UDN>")
            .find(fetch(address, "/xml/device_description.xml"))?.groupValues?.get(1)
            ?: error("$address served no player id at /xml/device_description.xml")

    /**
     * The household id, in the **full** form the Control API accepts.
     *
     * There are at least three names for this and only one works. `/status/zp` serves the
     * long one — `Sonos_xxx.yyy`, two segments either side of a dot — which is what
     * `HOUSEHOLD.SMARTSPEAKER.AUDIO` carries over SSDP and what mDNS calls `mhhid`. The
     * short `Sonos_xxx` (SSDP's `X-RINCON-HOUSEHOLD`, mDNS's `hhid`) is refused with
     * `ERROR_INVALID_OBJECT_ID` — verified against a player that answered the long one in
     * the same session.
     */
    private fun household(address: InetAddress): String =
        Regex("(Sonos_[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)")
            .find(fetch(address, "/status/zp"))?.groupValues?.get(1)
            ?: error("$address served no household id at /status/zp")

    private fun fetch(address: InetAddress, path: String): String =
        java.net.URL("http://${address.hostAddress}:${Upnp.PORT}$path")
            .openConnection().apply { connectTimeout = 5_000; readTimeout = 5_000 }
            .getInputStream().use { it.readBytes().decodeToString() }

    private fun connected() = runBlocking {
        household.connect(seed)
        withTimeout(10_000) { household.state.first { it.connected } }
    }

    // ------------------------------------------------------------ invariants

    @Test fun `discovery reports an id, an address and a household`() {
        assumeTrue("only SSDP promises a household id in the seed", target == "discover")
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
        // Wait for the volume itself, not merely for the group's key to exist: a playback
        // snapshot creates the entry with a null volume, so waiting on key presence raced
        // the groupVolume snapshot and failed intermittently.
        withTimeout(20_000) {
            household.groupStates.first { pushed -> groups.all { pushed[it.id]?.volume != null } }
        }
        groups.forEach { assertNotNull("no volume for ${it.name}", household.groupState(it.id).volume) }
    }

    /** The scoping rule, on hardware: a player-scoped command needs a real player id. */
    @Test fun `a player-scoped command with a bad id is refused by the player`() = runBlocking<Unit> {
        val player = seed!!
        val book = PlayerAddressBook().apply { register(player.hostname!!, player.address) }
        val socket = withTimeout(10_000) { SonosSocket.open(LanHttp.client(book), player.hostname!!) }
        val thrown = try {
            runCatching {
                // Sent over a socket to a real player, naming an id it does not have. Going
                // through the household instead would fail in the address book before the
                // command ever left this machine — proving only that our resolver works.
                withTimeout(10_000) {
                    socket.command(Frames.onPlayer("playerVolume:1", "getVolume", "RINCON_NOTAPLAYER"))
                }
            }.exceptionOrNull()
        } finally {
            socket.close()
        }
        assertTrue("expected the player to refuse, got $thrown", thrown is SonosCommandException)
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

        // Only *new* fields are drift. Fields the fixture has and this household does not
        // are expected: the fixture came from five rooms including a stereo pair, and these
        // assertions must also hold for a single speaker on a desk, which legitimately has
        // no `primaryDeviceId`, no second zone member, and so on.
        val added = live - recorded
        assertTrue(
            "this player sends fields the fixtures do not have — re-capture them:\n" +
                added.sorted().joinToString("\n") { "    $it" },
            added.isEmpty(),
        )

        // Separately: the fields this code actually reads must be present, whatever the
        // size of the household. This is the half that would catch a removal.
        listOf(
            ".groups[].id", ".groups[].name", ".groups[].coordinatorId",
            ".groups[].playerIds", ".groups[].playbackState",
            ".players[].id", ".players[].name", ".players[].websocketUrl",
        ).forEach { required ->
            assertTrue("this player no longer sends $required", required in live)
        }
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
