package com.rahga.x2rock.lan

import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.RepeatModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private companion object {
        const val VOLUME_42 = "{\"volume\":42,\"muted\":false,\"fixed\":false}"
        const val VOLUME_9 = "{\"volume\":9,\"muted\":false,\"fixed\":false}"
    }


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

    /**
     * A stream loaded by URL, captured off SomaFM: no `currentItem`, no track object, and a
     * `streamInfo` that is the only thing it can say it is playing.
     */
    @Test fun `a URL stream reports its now-playing through streamInfo`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id

        fake.pushFixture("radioMetadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.streamInfo != null } }
        val state = household.groupState(groupId)
        assertEquals("Blancmange - Don't Tell Me", state.streamInfo)
        // The premise of the whole field: the capture really does carry no track, so nothing
        // else in the app could have named what is playing.
        assertNull("the fixture must carry no track, or this proves nothing", state.track)
        assertEquals("ice1.somafm.com", state.container?.name)
    }

    /** A station between titles sends an empty string, which must read as absent. */
    @Test fun `a blank streamInfo is treated as absent`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id

        fake.pushFixture("radioMetadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.streamInfo != null } }
        fake.push(
            "playbackMetadata:1", "metadataStatus",
            """{"container":{"name":"ice1.somafm.com"},"streamInfo":"   "}""",
            groupId,
        )
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.streamInfo == null } }
        assertNull(household.groupState(groupId).streamInfo)
    }

    @Test fun `a soundbar's home theatre options are read from the settings namespace`() = runBlocking {
        val state = connected()
        val soundbar = state.players.first { TvSoundbar.hasHdmi(it.id, state) }
        val ht = household.playerSettings(soundbar.id).homeTheater
        assertNotNull(ht)
        // The values the captured Beam actually held, not a convenient pair: enhancement on
        // with a level behind it, which is the case a plain Boolean would have flattened.
        assertFalse(ht!!.nightMode)
        assertTrue(ht.enhanceDialog)
        assertEquals(1, ht.enhanceDialogLevel)
    }

    @Test fun `writing a home theatre setting is refused for a speaker with no TV input`() = runBlocking {
        val state = connected()
        val plain = state.players.first { !TvSoundbar.hasHdmi(it.id, state) }
        var refused = false
        try {
            household.setNightMode(plain.id, true)
        } catch (e: IllegalArgumentException) {
            refused = true
        }
        assertTrue("a speaker with no HDMI socket was allowed to set night mode", refused)
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

    /**
     * Regression, seen on the Google TV Streamer: switching a room to its TV input left the
     * player pane showing the progress bar of the song it had just interrupted, counting up
     * towards a duration nothing was playing.
     *
     * A TV input carries no track at all — the captured fixture below has no `currentItem`
     * — and the duration was being carried over from the previous metadata rather than
     * cleared. Unlike a `playback:1` event, a metadata event states the whole of what is
     * loaded, so absent means gone.
     */
    @Test fun `switching to a TV input clears the interrupted track's duration`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id

        fake.pushFixture("metadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.track != null } }
        fake.push("playback:1", "playbackStatus", """{"positionMillis":42000}""", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.positionMillis == 42_000L } }
        assertTrue(
            "the fixture must carry a duration, or this proves nothing",
            household.groupState(groupId).durationMillis > 0,
        )

        fake.pushFixture("tvMetadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.onTvInput == true } }

        val state = household.groupState(groupId)
        assertEquals("a TV input has no track to have a duration", 0L, state.durationMillis)
        assertEquals("nor a position in one", 0L, state.positionMillis)
    }

    /**
     * `metadataSeen` says a group has actually reported, which having a map entry does not.
     *
     * Any of the three subscriptions creates the entry, and `onTvInput` is read from metadata
     * alone — so anything judging "is this room on a TV input" from entry presence would read
     * "no" from a group that had only sent its playback snapshot. That is enough to make the
     * two-televisions ambiguity guard pass on a household where it should not.
     */
    @Test fun `a playback event alone does not count as metadata having arrived`() = runBlocking<Unit> {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id

        fake.push("playback:1", "playbackStatus", """{"positionMillis":1000}""", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.positionMillis == 1000L } }
        assertFalse(
            "a playback event was taken for metadata",
            household.groupState(groupId).metadataSeen,
        )

        fake.pushFixture("metadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.metadataSeen == true } }
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

    /** A player-scoped command naming an unknown id must be refused, not quietly accepted. */
    @Test fun `an unknown playerId is refused`() = runBlocking<Unit> {
        connected()
        val thrown = runCatching {
            household.setPlayerVolume(FakePlayer.fixtureCoordinatorId().replace("AA0", "ZZ0"), 10)
        }.exceptionOrNull()
        assertNotNull("an unknown playerId was accepted", thrown)
    }

    @Test fun `a command is addressed to the group and the player answers`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id
        household.togglePlayPause(groupId)
        val cmd = fake.awaitCommand { it.get("command")?.asString == "togglePlayPause" }
        assertEquals("playback:1", cmd.get("namespace").asString)
        assertEquals(groupId, cmd.get("groupId").asString)
    }

    /**
     * Album art for LAN content is served by the speaker as `http://<ip>:1400/getaa?…`.
     * Android's cleartext exemption covers `.local` names only — deliberately — so left as
     * an address every image silently fails to load.
     */
    @Test fun `player-served art is pointed at the local name`() {
        connected()
        val player = household.state.value.players.first()
        val address = java.net.URI(player.websocketUrl!!).host
        val hostname = PlayerNames.localHostname(player.id)!!

        val rewritten = household.reachableArt("http://$address:1400/getaa?s=1&u=track")
        assertEquals("http://$hostname:1400/getaa?s=1&u=track", rewritten)
    }

    @Test fun `art hosted anywhere else is left alone`() {
        connected()
        val service = "https://sonos.plex.tv/img?width=300"
        assertEquals(service, household.reachableArt(service))
        // An address belonging to no player is not ours to rewrite.
        val stranger = "http://198.51.100.7:1400/getaa?s=1"
        assertEquals(stranger, household.reachableArt(stranger))
        assertEquals(null, household.reachableArt(null))
    }

    /**
     * Which rooms can take a TV input, from the captured topology.
     *
     * The household this was captured from is a good test of the distinction, because
     * "several speakers" and "has an HDMI socket" cut across each other:
     *
     * ```
     *   Living Room  Beam + Sub + 2x Play:1   bonded, soundbar
     *   Bedroom      Beam + 2x One SL         bonded, soundbar
     *   Guest TV     Beam                     single, soundbar
     *   Dining Room  2x Symfonisk bookshelf   bonded, no HDMI
     *   Kitchen      One SL                   single, no HDMI
     * ```
     *
     * So a bonded set is not a soundbar, and a soundbar need not be bonded. Only the
     * HT_PLAYBACK capability tells them apart.
     */
    @Test fun `a TV input follows the soundbar, not the number of speakers`() {
        val state = connected()
        val withTv = state.groups.filter { state.hasTvInput(it) }.map { it.name }.toSet()

        assertEquals(setOf("Living Room", "Bedroom", "Guest TV"), withTv)
    }

    /**
     * The HDMI socket belongs to a player, not to whichever one is coordinating: a soundbar
     * that joins a speaker's group still has its input.
     *
     * Needs a group with more than one player, and every group in the capture has exactly
     * one — so a check that only looked at the coordinator would pass the test above
     * unchanged. Here the coordinator is a Sonos One SL with no HDMI and the *member* is a
     * Beam, so only asking every member gets the right answer.
     */
    @Test fun `a soundbar that joined another room's group still offers its input`() = runBlocking<Unit> {
        connected()
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))

        val state = withTimeout(5_000) {
            household.state.first { s -> s.groups.firstOrNull { it.name == "Kitchen" }?.playerIds?.size == 2 }
        }
        val kitchen = state.groups.first { it.name == "Kitchen" }
        assertTrue("the Beam's input was lost when it joined a speaker", state.hasTvInput(kitchen))
    }

    /**
     * Anything on the network can regroup this household at any moment — the Sonos app, a
     * voice assistant, another controller — and a regroup mints *new* group ids.
     *
     * Subscribing only at connect left every group formed afterwards with no playback,
     * metadata or volume subscription: listed, and permanently frozen.
     */
    @Test fun `a group formed by someone else gets subscribed`() = runBlocking<Unit> {
        connected()
        fake.clearHistory()
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))

        val merged = withTimeout(10_000) {
            household.state.first { s -> s.groups.firstOrNull { it.name == "Kitchen" }?.playerIds?.size == 2 }
        }.groups.first { it.name == "Kitchen" }

        // The new id must be subscribed, not merely listed.
        listOf("playback:1", "playbackMetadata:1", "groupVolume:1").forEach { namespace ->
            fake.awaitCommand(timeoutMillis = 5_000) {
                it.get("namespace")?.asString == namespace &&
                    it.get("command")?.asString == "subscribe" &&
                    it.get("groupId")?.asString == merged.id
            }
        }

        // And it then receives state, which is the thing that was actually broken.
        fake.push("groupVolume:1", "groupVolume", VOLUME_42, merged.id)
        val volume = withTimeout(5_000) {
            household.groupStates.first { it[merged.id]?.volume != null }[merged.id]!!.volume!!
        }
        assertEquals(42, volume.volume)
    }

    /** A group that no longer exists should not keep occupying state. */
    @Test fun `a group that disappears is forgotten`() = runBlocking<Unit> {
        connected()
        val doomed = household.state.value.groups.first { it.name == "Guest TV" }
        fake.push("groupVolume:1", "groupVolume", VOLUME_9, doomed.id)
        withTimeout(5_000) { household.groupStates.first { it[doomed.id]?.volume != null } }

        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))
        withTimeout(10_000) { household.groupStates.first { doomed.id !in it.keys } }
        assertTrue(doomed.id !in household.groupStates.value)
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
        fake.clearHistory()

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

    @Test fun `disconnect stops everything and does not reconnect`() = runBlocking<Unit> {
        connected()
        household.disconnect()
        assertTrue(household.state.value.groups.isEmpty())
        assertTrue(household.groupStates.value.isEmpty())

        // And nothing is trying to come back up. Asserted behaviourally rather than by
        // counting jobs: wait past the first backoff and check the player is left alone.
        fake.clearHistory()
        delay(MIN_BACKOFF_MILLIS + 1_500)
        assertTrue(
            "disconnect() was followed by ${fake.received.size} commands — it reconnected",
            fake.received.isEmpty(),
        )
        assertTrue(!household.state.value.connected)
    }
}
