package com.rahga.x2rock.lan

import com.rahga.x2rock.model.PlaybackStates
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    /** What this suite itself leaves in the household's history. */
    private val LEFTOVERS = setOf("x2rock live test", "Dead")

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
    /**
     * Read-only. A seed with no household — what any non-SSDP way of finding a player gives —
     * must still connect: the household reads `/status/zp` for the long id. The old way, a
     * malformed frame, got no answer at all from a One SL on p20.96.1.
     */
    @Test fun `a seed with no household still connects`() = runBlocking<Unit> {
        household.connect(seed!!.copy(householdId = null))
        val state = withTimeout(10_000) { household.state.first { it.connected } }
        assertTrue("not the long form: ${state.householdId}", state.householdId!!.matches(Regex("Sonos_[^.]+\\..+")))
    }

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
    /**
     * Read-only. The household's UPnP switch, as `SonosHousehold` reads it off the seed:
     * captured on the office One SL, 2026-10-01. Nothing asserts which way it is set.
     */
    @Test fun `the UPnP switch read still matches its fixture`() = runBlocking<Unit> {
        val player = seed!!
        val book = PlayerAddressBook().apply { register(player.hostname!!, player.address) }
        val socket = withTimeout(10_000) { SonosSocket.open(LanHttp.client(book), player.hostname!!) }
        val security = try {
            withTimeout(10_000) {
                socket.command(
                    Frames.onPlayer("effectiveSettings:1", "getSettingsGroup", player.id),
                    JsonObject().apply { addProperty("groupName", "security") },
                )
            }
        } finally {
            socket.close()
        }
        val live = shapeOf(security)
        val added = live - shapeOf(FakePlayer.fixture("getSettingsGroup.security.reply.json"))
        assertTrue(
            "the security settings carry fields the fixture does not — re-capture it:\n" +
                added.sorted().joinToString("\n") { "    $it" },
            added.isEmpty(),
        )
        listOf(".attributes.allowInsecureUPnP", ".timestamp").forEach { required ->
            assertTrue("this player no longer sends $required", required in live)
        }
    }

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
            restoreVolume(group.id, before)
        }
    }

    /**
     * Only with `-Dx2rock.live.room=<room>`, and the room's source is put back afterwards —
     * the URI and its metadata from `GetMediaInfo`, then play if it was playing.
     *
     * The case this exists for is a room on a station with tracks queued behind it, where a
     * bare `Seek` answers 701. On a room already playing its queue it checks the other path,
     * which must not re-set the source. Skipped on an empty queue: nothing here may assume
     * one is populated.
     */
    @Test fun `a queue track plays whatever the room was playing`() = runBlocking<Unit> {
        assumeTrue("set -Dx2rock.live.room=<room> to allow changing a speaker", mutableRoom != null)
        connected()
        val group = household.state.value.groups.firstOrNull { it.name == mutableRoom }
            ?: error("no room named $mutableRoom in this household")
        assumeTrue("$mutableRoom has nothing queued", household.queue(group.id).items.isNotEmpty())
        withTimeout(10_000) { household.groupStates.first { it[group.id]?.metadataSeen == true } }
        val before = household.mediaInfo(group.id)
        val wasPlaying = household.groupState(group.id).playbackState == PlaybackStates.PLAYING
        try {
            household.skipToQueueItem(group.id, 1)
            assertTrue("the queue is not the source after playing from it", household.playingFromQueue(group.id))
            withTimeout(15_000) {
                household.groupStates.first { it[group.id]?.playbackState == PlaybackStates.PLAYING }
            }
        } finally {
            runCatching {
                household.restoreSource(group.id, before)
                if (wasPlaying) resume(group.id) else household.pause(group.id)
            }
        }
    }

    /**
     * Only with `-Dx2rock.live.room=<room>`, and restored afterwards. The Vol −/+ buttons send
     * `setRelativeVolume`, so the step is checked against what the player then reports.
     */
    @Test fun `a relative volume step comes back as an event`() = runBlocking<Unit> {
        assumeTrue("set -Dx2rock.live.room=<room> to allow changing a speaker", mutableRoom != null)
        connected()
        val group = household.state.value.groups.firstOrNull { it.name == mutableRoom }
            ?: error("no room named $mutableRoom in this household")
        withTimeout(10_000) { household.groupStates.first { it[group.id]?.volume != null } }
        val before = household.groupState(group.id).volume!!
        val step = if (before.volume >= 50) -3 else 3
        try {
            household.adjustGroupVolume(group.id, step)
            withTimeout(10_000) {
                household.groupStates.first { it[group.id]?.volume?.volume == before.volume + step }
            }
        } finally {
            restoreVolume(group.id, before.volume)
            if (before.muted) runCatching { household.setGroupMute(group.id, true) }
        }
    }

    /**
     * Press play until the room is playing again. Once is not enough straight after a source
     * is set: on the office One SL a play sent at once was lost and the room sat IDLE.
     */
    private suspend fun resume(groupId: String) {
        // The source change shows as a stop first; until it does, "playing" is the item just
        // replaced, and the loop below would see it and press nothing.
        withTimeoutOrNull(3_000) {
            household.groupStates.first { it[groupId]?.playbackState != PlaybackStates.PLAYING }
        }
        withTimeoutOrNull(15_000) {
            while (household.groupState(groupId).playbackState != PlaybackStates.PLAYING) {
                runCatching { household.play(groupId) }
                withTimeoutOrNull(1_500) {
                    household.groupStates.first { it[groupId]?.playbackState == PlaybackStates.PLAYING }
                }
            }
        }
    }

    /**
     * Put a level back and wait until the player says it is back. Not just send it: a second
     * volume command inside ~260ms is deferred and reads in that window are stale (x2rock,
     * "Volume handling"), so the next test could read a half-restored level and "restore" to
     * that — seen on the office One SL, which finished a run at 4 having started at 0.
     */
    private suspend fun restoreVolume(groupId: String, level: Int) {
        runCatching {
            household.setGroupVolume(groupId, level)
            withTimeout(10_000) { household.groupStates.first { it[groupId]?.volume?.volume == level } }
        }
    }

    /**
     * Only with `-Dx2rock.live.room=<room>`. Arms the room's own sleep timer, reads it back
     * and cancels it; a timer already running is put back with what it had left.
     */
    @Test fun `the room's sleep timer arms, reads back and cancels`() = runBlocking<Unit> {
        assumeTrue("set -Dx2rock.live.room=<room> to allow changing a speaker", mutableRoom != null)
        connected()
        val group = household.state.value.groups.firstOrNull { it.name == mutableRoom }
            ?: error("no room named $mutableRoom in this household")
        val before = household.sleepTimer(group.id)
        try {
            household.setSleepTimer(group.id, 60)
            val armed = household.sleepTimer(group.id)
            assertTrue("armed for an hour, read back $armed", armed != null && armed in 59 * 60_000L..60 * 60_000L)
            household.setSleepTimer(group.id, null)
            assertEquals(null, household.sleepTimer(group.id))
        } finally {
            runCatching {
                if (before != null) household.setSleepTimer(group.id, (before / 60_000L).toInt().coerceAtLeast(1))
                else household.setSleepTimer(group.id, null)
            }
        }
    }

    /**
     * Only with `-Dx2rock.live.room=<room>`, and the source put back afterwards. Plays the
     * household's most recent playable item again, through the load-then-press-play that
     * `loadContent` needs. Skipped with history empty or switched off.
     */
    @Test fun `the most recent item replays and plays`() = runBlocking<Unit> {
        assumeTrue("set -Dx2rock.live.room=<room> to allow changing a speaker", mutableRoom != null)
        connected()
        val group = household.state.value.groups.firstOrNull { it.name == mutableRoom }
            ?: error("no room named $mutableRoom in this household")
        // Not this suite's own leftovers: the playlist the queue test saves and deletes, and the
        // dead stream the directory test plays, both sit at the top of the history afterwards.
        val item = runCatching { household.history() }.getOrDefault(emptyList())
            .firstOrNull { it.playable && it.name !in LEFTOVERS }
        assumeTrue("this household has no recently played item", item != null)
        withTimeout(10_000) { household.groupStates.first { it[group.id]?.metadataSeen == true } }
        val before = household.mediaInfo(group.id)
        val wasPlaying = household.groupState(group.id).playbackState == PlaybackStates.PLAYING
        try {
            household.replay(group.id, item!!)
            assertEquals(PlaybackStates.PLAYING, household.groupState(group.id).playbackState)
        } finally {
            runCatching {
                household.restoreSource(group.id, before)
                if (wasPlaying) resume(group.id) else household.pause(group.id)
            }
        }
    }

    /**
     * Only with `-Dx2rock.live.room=<room>`, at volume 2, and put back. A real station from the
     * directory must be reported PLAYING, and a URL that cannot resolve SILENT: the player takes
     * both without complaint, so the difference is the whole point of [SonosHousehold.playStream].
     * Prints what the room named the station, which is not what `stationMetadata` said.
     */
    @Test fun `a directory station plays, and a dead URL is reported silent`() = runBlocking<Unit> {
        assumeTrue("set -Dx2rock.live.room=<room> to allow changing a speaker", mutableRoom != null)
        connected()
        val group = household.state.value.groups.firstOrNull { it.name == mutableRoom }
            ?: error("no room named $mutableRoom in this household")
        // A directory row is a stranger's URL and the directory's own check of it is days old:
        // the top one was dead on 2026-10-04, having played the day before. So up to five are
        // tried, and only all five silent is a failure.
        val stations = com.rahga.x2rock.radio.RadioDirectory(okhttp3.OkHttpClient()).stations(tag = "ambient", limit = 5)
        withTimeout(10_000) { household.groupStates.first { it[group.id]?.metadataSeen == true } }
        val before = household.mediaInfo(group.id)
        val wasPlaying = household.groupState(group.id).playbackState == PlaybackStates.PLAYING
        val level = household.groupState(group.id).volume?.volume
        try {
            household.setGroupVolume(group.id, 2)
            // The dead one first, so a room that had nothing to restore is left naming a real
            // station rather than "Dead".
            assertEquals(StreamStart.SILENT, household.playStream(group.id, "https://stream.invalid/dead.mp3", "Dead"))
            val played = stations.firstOrNull { household.playStream(group.id, it.url, it.name) == StreamStart.PLAYING }
            assertTrue("none of ${stations.map { it.name }} played", played != null)
            val playing = household.groupState(group.id)
            println("live: \"${played!!.name}\" played; the room calls it ${playing.container?.name} (${playing.container?.type})")
        } finally {
            runCatching {
                household.restoreSource(group.id, before)
                if (wasPlaying) resume(group.id) else household.pause(group.id)
            }
            level?.let { restoreVolume(group.id, it) }
        }
    }

    /**
     * Only with `-Dx2rock.live.room=<room>`, and everything put back: the queue is saved as a
     * playlist first, restored from it at the end, and the playlist deleted. Checks the save's
     * real reply (the unit test's is built), a move and its undo, and a clear. Tracks are told
     * apart by their art URLs, which carry each track's own id — item ids are positions.
     *
     * One thing it cannot put back: the queue's container, which afterwards names the deleted
     * "x2rock live test" playlist it was restored from, until the room loads something else.
     */
    @Test fun `the queue saves, moves, clears and is put back`() = runBlocking<Unit> {
        assumeTrue("set -Dx2rock.live.room=<room> to allow changing a speaker", mutableRoom != null)
        connected()
        val group = household.state.value.groups.firstOrNull { it.name == mutableRoom }
            ?: error("no room named $mutableRoom in this household")
        val read = household.queue(group.id)
        val original = read.items.map { it.track?.imageUrl }
        assumeTrue("$mutableRoom needs two queued tracks", original.size >= 2)
        withTimeout(10_000) { household.groupStates.first { it[group.id]?.metadataSeen == true } }
        val source = household.mediaInfo(group.id)
        val wasPlaying = household.groupState(group.id).playbackState == PlaybackStates.PLAYING

        val saved = household.saveQueue(group.id, "x2rock live test")
        assertTrue("SaveQueue answered no id: '$saved'", saved.startsWith("SQ:"))
        val bareId = saved.removePrefix("SQ:")
        try {
            household.moveInQueue(group.id, from = 1, to = 2, updateId = read.updateId!!)
            val afterMove = household.queue(group.id)
            val moved = afterMove.items.map { it.track?.imageUrl }
            assertEquals(listOf(original[1], original[0]) + original.drop(2), moved)
            // The id the first read gave is stale now; the player must refuse it, not move again.
            val stale = runCatching { household.moveInQueue(group.id, from = 1, to = 2, updateId = read.updateId!!) }.exceptionOrNull()
            assertEquals("a stale UpdateID must be refused with 1028, got $stale", "1028", (stale as? UpnpRefusedException)?.upnpCode)
            household.moveInQueue(group.id, from = 2, to = 1, updateId = afterMove.updateId!!)
            assertEquals(original, household.queue(group.id).items.map { it.track?.imageUrl })

            household.clearQueue(group.id)
            assertTrue(household.queue(group.id).items.isEmpty())
        } finally {
            runCatching {
                if (household.queue(group.id).items.isEmpty()) {
                    household.loadPlaylist(group.id, bareId)
                    withTimeoutOrNull(10_000) {
                        while (household.queue(group.id).items.size < original.size) kotlinx.coroutines.delay(300)
                    }
                }
                household.destroyPlaylist(group.id, saved)
                household.restoreSource(group.id, source)
                if (wasPlaying) resume(group.id) else household.pause(group.id)
            }
        }
        assertEquals("the queue was not put back", original, household.queue(group.id).items.map { it.track?.imageUrl })
    }

    /**
     * Read-only on the household; asks the track's own service for its rating state, which is
     * a read there too. Says what it found rather than asserting it: whether a track can be
     * rated is the content's business, not the protocol's.
     */
    @Test fun `the room's track reports a rating state or none`() = runBlocking<Unit> {
        assumeTrue("set -Dx2rock.live.room=<room> to name the room", mutableRoom != null)
        connected()
        val group = household.state.value.groups.firstOrNull { it.name == mutableRoom }
            ?: error("no room named $mutableRoom in this household")
        withTimeout(10_000) { household.groupStates.first { it[group.id]?.metadataSeen == true } }
        val track = household.groupState(group.id).track
        println("LIVE rating: ${track?.name} id=${track?.id} -> ${household.ratingState(group.id)}")
    }

    /**
     * Only with `-Dx2rock.live.room=<room>`, and put back. Bass moves by one and loudness
     * flips, each written over UPnP and read back over settings:1 — the two halves that never
     * meet in a unit test. TruePlay is left alone: turning a room's calibration off is more
     * than a test should risk.
     */
    @Test fun `bass and loudness write over UPnP and read back over settings`() = runBlocking<Unit> {
        assumeTrue("set -Dx2rock.live.room=<room> to allow changing a speaker", mutableRoom != null)
        connected()
        val group = household.state.value.groups.firstOrNull { it.name == mutableRoom }
            ?: error("no room named $mutableRoom in this household")
        val player = group.coordinatorId
        val before = household.playerSettings(player).eq ?: error("$mutableRoom reported no eq")
        val bass = if (before.bass >= 10) before.bass - 1 else before.bass + 1
        try {
            household.setBass(player, bass)
            household.setLoudness(player, !before.loudness)
            val after = household.playerSettings(player).eq!!
            assertEquals(bass, after.bass)
            assertEquals(!before.loudness, after.loudness)
        } finally {
            runCatching {
                household.setBass(player, before.bass)
                household.setLoudness(player, before.loudness)
            }
        }
        assertEquals("the tone was not put back", before, household.playerSettings(player).eq)
    }

    /**
     * Read-only, and about the protocol rather than this house: a soundbar answers
     * `settings:1 getPlayerSettings` with a `homeTheater` block. Nothing asserts which way
     * the toggles are set — a household where both happen to be off must pass too.
     *
     * Skipped where no player has an HDMI socket, which is the single-One-SL case.
     */
    @Test fun `a soundbar reports its home theatre options`() = runBlocking<Unit> {
        connected()
        val soundbar = household.state.value.players.firstOrNull {
            TvSoundbar.hasHdmi(it.id, household.state.value)
        }
        assumeTrue("no player in this household has an HDMI input", soundbar != null)

        val body = household.playerSettingsBody(soundbar!!.id)
        println("getPlayerSettings body for a soundbar: $body")
        assertTrue("no homeTheater block in $body", body.asJsonObject.has("homeTheater"))

        val settings = household.playerSettings(soundbar.id)
        assertNotNull("homeTheater did not deserialize", settings.homeTheater)
    }

    /**
     * Only with `-Dx2rock.live.room=<room>`, and restored afterwards.
     *
     * The write leaves the Control API for UPnP `SetEQ`, so this is the one command whose
     * effect is *not* pushed — which is exactly what makes it worth running: the re-read is
     * the only way to know it landed, and if a future firmware starts announcing it this
     * test is where that shows up.
     */
    @Test fun `night mode is written over UPnP and read back over the Control API`() = runBlocking<Unit> {
        assumeTrue("set -Dx2rock.live.room=<room> to allow changing a speaker", mutableRoom != null)
        connected()
        val group = household.state.value.groups.firstOrNull { it.name == mutableRoom }
            ?: error("no room named $mutableRoom in this household")
        val soundbar = group.playerIds.firstOrNull { TvSoundbar.hasHdmi(it, household.state.value) }
        assumeTrue("$mutableRoom has no speaker with an HDMI input", soundbar != null)

        val before = household.playerSettings(soundbar!!).homeTheater!!.nightMode
        try {
            household.setNightMode(soundbar, !before)
            assertEquals(!before, household.playerSettings(soundbar).homeTheater!!.nightMode)
        } finally {
            runCatching { household.setNightMode(soundbar, before) }
        }
    }

    /**
     * The open question the write test cannot answer: `SetEQ` goes over UPnP, so does
     * `settings:1` announce the result to a subscriber, or must it be re-read?
     *
     * Written as an experiment rather than an assertion of either answer — it records what
     * the player did, and only fails if the subscribe itself is refused, which would mean
     * the question is not even well formed. A change of behaviour in a future firmware shows
     * up here as a changed message, not a red build.
     */
    @Test fun `whether the settings namespace pushes a home theatre change`() = runBlocking<Unit> {
        assumeTrue("set -Dx2rock.live.room=<room> to allow changing a speaker", mutableRoom != null)
        connected()
        val state = household.state.value
        val group = state.groups.firstOrNull { it.name == mutableRoom }
            ?: error("no room named $mutableRoom in this household")
        val soundbarId = group.playerIds.firstOrNull { TvSoundbar.hasHdmi(it, state) }
        assumeTrue("$mutableRoom has no speaker with an HDMI input", soundbarId != null)
        val player = state.players.first { it.id == soundbarId }

        val hostname = PlayerNames.localHostname(player.id)!!
        val address = java.net.URI(player.websocketUrl!!).host
        val book = PlayerAddressBook().apply { register(hostname, address) }
        val socket = withTimeout(10_000) { SonosSocket.open(LanHttp.client(book), hostname) }

        val before = household.playerSettings(player.id).homeTheater!!.nightMode
        try {
            val subscribed = runCatching {
                withTimeout(10_000) {
                    socket.subscribe(Frames.onPlayer("settings:1", "subscribe", player.id))
                }
            }
            assertTrue(
                "settings:1 refused a subscription: ${subscribed.exceptionOrNull()}",
                subscribed.isSuccess,
            )

            val pushed = async { runCatching { withTimeout(8_000) { socket.events.first() } } }
            delay(500)
            household.setNightMode(player.id, !before)
            val event = pushed.await().getOrNull()

            println(
                if (event == null) "settings:1: no event in 8s after a SetEQ write — re-read is required"
                else "settings:1 pushed ${event.namespace}/${event.type}: ${event.body}"
            )
            // The re-read must see it whatever the subscription did.
            assertEquals(!before, household.playerSettings(player.id).homeTheater!!.nightMode)
        } finally {
            runCatching { household.setNightMode(player.id, before) }
            socket.close()
        }
    }

    /**
     * Records what a room is actually playing, so a fixture can be captured rather than
     * invented. Read-only, and asserts nothing about content — it is a capture tool.
     */
    @Test fun `capture the metadata a room reports`() = runBlocking<Unit> {
        assumeTrue("set -Dx2rock.live.room=<room> to name the room to capture", mutableRoom != null)
        connected()
        val group = household.state.value.groups.firstOrNull { it.name == mutableRoom }
            ?: error("no room named $mutableRoom in this household")
        println("metadataStatus for $mutableRoom: ${household.metadataStatusBody(group.id)}")
        println("playbackStatus for $mutableRoom: ${household.playbackStatusBody(group.id)}")
    }

    /** A speaker with no HDMI socket is refused here rather than by a bare UPnP 402. */
    @Test fun `night mode is refused for a speaker with no TV input`() = runBlocking<Unit> {
        connected()
        val plain = household.state.value.players.firstOrNull {
            !TvSoundbar.hasHdmi(it.id, household.state.value)
        }
        assumeTrue("every player in this household has an HDMI input", plain != null)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { household.setNightMode(plain!!.id, true) }
        }
    }
}
