package com.rahga.x2rock.lan

import com.rahga.x2rock.model.PlaybackStates
import com.rahga.x2rock.model.RepeatModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
     * Both captures are real: the first off a queue of tracks, the second off a live stream.
     * Contrasted deliberately — asserting the stream's falses alone would pass just as well
     * against a parser that returned false for everything.
     */
    @Test fun `a live stream permits neither skipping nor pausing`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id

        fake.pushFixture("playbackStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.actions?.canSkip == true } }
        assertTrue("the queue capture must permit skipping, or the rest proves nothing",
            household.groupState(groupId).actions.canSkip)
        assertTrue(household.groupState(groupId).actions.canPause)

        fake.pushFixture("radioPlaybackStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.actions?.canSkip == false } }
        val actions = household.groupState(groupId).actions
        assertFalse(actions.canSkip)
        assertFalse(actions.canSkipToPrevious)
        assertFalse(actions.canSeek)
        assertFalse(actions.canShuffle)
        assertFalse(actions.canRepeat)
        assertFalse(actions.canCrossfade)
        // The one that is easy to miss: a live stream stops, it does not pause.
        assertFalse(actions.canPause)
        assertTrue(actions.canStop)
    }

    /**
     * The two kinds of radio are not the same shape, which is why the artwork and the
     * controls are decided separately.
     *
     * A service station — captured off iHeartRadio — carries a real track, an artist and a
     * station logo, and no `streamInfo` whatever. A stream loaded by URL carries the
     * opposite: `streamInfo` and no track at all. Both are stations, and both refuse to
     * skip; only one of them has anything to draw.
     */
    @Test fun `a service station carries a track and a logo where a URL stream carries neither`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id

        fake.pushFixture("stationMetadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.isRadio == true } }
        val station = household.groupState(groupId)
        assertEquals("You're Still The One", station.track?.name)
        assertEquals("Shania Twain", station.track?.artist?.name)
        assertNotNull("a service station has a logo to draw", station.container?.imageUrl)
        assertNull("and says nothing in streamInfo", station.streamInfo)

        fake.pushFixture("radioMetadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.streamInfo != null } }
        val stream = household.groupState(groupId)
        assertTrue("a URL stream is radio too", stream.isRadio)
        assertNull("but has no track", stream.track)
        assertNull("and nothing to draw", stream.container?.imageUrl)
    }

    /** What a source *is*, which decides its artwork rather than its controls. */
    @Test fun `a station is recognised as radio`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id

        fake.pushFixture("metadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.track != null } }
        assertFalse("an album is not radio", household.groupState(groupId).isRadio)

        fake.pushFixture("radioMetadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.isRadio == true } }
        assertTrue(household.groupState(groupId).isRadio)
    }

    /**
     * Both captures are real, contrasted deliberately — asserting the Live station's `false`
     * alone would pass just as well against a parser that returned `false` for everything.
     *
     * A Plex queue track (`metadataStatus`) carries a real `id`; iHeartRadio's own "Love
     * Songs Radio" (`stationMetadataStatus`) — a Live broadcast, the household's actual
     * ordinary listening — has a track with a name and an artist but **no `id` field at
     * all**, verified against the real household 2026-09-12. `hasTrackId` is where a rating
     * check starts, so a fixture set with only the unrateable case could never fail it.
     */
    @Test fun `hasTrackId follows the track's own id, not whether it is radio`() = runBlocking {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id

        fake.pushFixture("metadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.track != null } }
        assertTrue("a real service track id must read as rateable",
            household.groupState(groupId).hasTrackId)

        fake.pushFixture("stationMetadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.isRadio == true } }
        assertFalse("a Live broadcast's track carries no id and cannot be rated",
            household.groupState(groupId).hasTrackId)
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
    /**
     * The captured error, between two captured statuses. It must change nothing a status
     * would — x2rock's version reset every capability to false on each failed stream — and
     * it must outlast the IDLE statuses a failed stream sends, or it is gone before anyone
     * reads it. Only playing again clears it.
     */
    @Test fun `a playback error is kept as an error, not read as a status`() = runBlocking<Unit> {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id
        fake.pushFixture("radioPlaybackStatus", groupId)
        val playing = withTimeout(5_000) {
            household.groupStates.first { it[groupId]?.playbackState == PlaybackStates.PLAYING }
        }.getValue(groupId)

        fake.pushFixture("playbackError", groupId)
        val failed = withTimeout(5_000) { household.groupStates.first { it[groupId]?.lastError != null } }
            .getValue(groupId)
        assertEquals("ERROR_NO_PLAYABLE_CONTENT", failed.lastError?.reason)
        assertEquals("Couldn't play this: found nothing it could play", failed.lastError?.describe())
        assertEquals("the error must not touch what the source permits", playing.actions, failed.actions)
        assertEquals(playing.playMode, failed.playMode)
        assertEquals(playing.playbackState, failed.playbackState)

        fake.pushFixture("playbackStatus", groupId)
        val idle = withTimeout(5_000) {
            household.groupStates.first { it[groupId]?.playbackState == PlaybackStates.IDLE }
        }.getValue(groupId)
        assertNotNull("an idle status after a failure does not answer it", idle.lastError)

        fake.pushFixture("radioPlaybackStatus", groupId)
        withTimeout(5_000) {
            household.groupStates.first {
                it[groupId]?.playbackState == PlaybackStates.PLAYING && it[groupId]?.lastError == null
            }
        }
    }

    /**
     * Something else loaded answers a failure too: on the Shield, a room put back on its own
     * queue after a dead stream, still idle, went on saying "Couldn't play this". The same
     * source arriving again must not clear it — the failed stream's own metadata comes first.
     */
    @Test fun `a playback error clears when the source changes, not when it repeats`() = runBlocking<Unit> {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id
        fake.pushFixture("stationMetadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.isRadio == true } }
        fake.pushFixture("playbackError", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.lastError != null } }

        fake.pushFixture("stationMetadataStatus", groupId)
        delay(300)
        assertNotNull("the same source again is not an answer", household.groupState(groupId).lastError)

        fake.pushFixture("metadataStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.lastError == null } }
    }

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

    // ------------------------------------------------------------- losing one socket
    //
    // Guest TV joins Kitchen's group, so Guest TV's player is a member and nothing more: its
    // socket carries only its own volume. Kitchen's is a coordinator's.

    private fun guestTvJoinsKitchen(): Pair<String, String> = runBlocking {
        connected()
        val before = household.state.value
        val member = before.groups.first { it.name == "Guest TV" }.coordinatorId
        val coordinator = before.groups.first { it.name == "Kitchen" }.coordinatorId
        fake.pushTopology(FakePlayer.groupedTopology(coordinatorRoom = "Kitchen", memberRoom = "Guest TV"))
        withTimeout(10_000) {
            household.state.first { s -> s.groups.none { it.coordinatorId == member } }
        }
        // The catch-up has finished once the new group is subscribed on Kitchen.
        val joined = household.state.value.groups.first { it.coordinatorId == coordinator }.id
        fake.push("groupVolume:1", "groupVolume", VOLUME_42, joined)
        withTimeout(10_000) { household.groupStates.first { it[joined]?.volume != null } }
        fake.pushPlayerVolume(member, volume = 9)
        withTimeout(5_000) { household.playerVolumes.first { it[member]?.volume == 9 } }
        member to coordinator
    }

    /**
     * One speaker in a group losing power must cost that speaker's level and nothing else.
     * It used to rebuild the household — every room blank, every subscription redone — and
     * again on every reconnect it then failed.
     */
    @Test fun `a member's lost socket leaves the household standing`() = runBlocking<Unit> {
        val (member, _) = guestTvJoinsKitchen()
        val groupsBefore = household.groupStates.value.keys
        assertTrue(fake.isConnected(member))

        fake.dropConnection(member)

        withTimeout(5_000) { household.playerVolumes.first { member !in it } }
        val disconnected = kotlinx.coroutines.withTimeoutOrNull(3_000) {
            household.state.first { !it.connected }
        }
        assertNull("losing a member's socket must not drop the session", disconnected)
        assertEquals(groupsBefore, household.groupStates.value.keys)

        // Evicted, not kept: the next command to that speaker opens a fresh socket rather
        // than failing on the dead one.
        household.setPlayerVolume(member, 10)
        assertTrue("no new socket was opened to the member", fake.isConnected(member))
    }

    /** The other half: a coordinator's socket carries its group's subscriptions, so it rebuilds. */
    @Test fun `a coordinator's lost socket still rebuilds the session`() = runBlocking<Unit> {
        val (_, coordinator) = guestTvJoinsKitchen()
        fake.clearHistory()

        fake.dropConnection(coordinator)

        withTimeout(15_000) { household.state.first { !it.connected } }
        withTimeout(15_000) { household.state.first { it.connected } }
        fake.awaitCommand(timeoutMillis = 5_000) {
            it.get("command")?.asString == "subscribe" && it.get("namespace")?.asString == "playback:1"
        }
    }

    /**
     * A socket being opened to a speaker that is slow to answer must not hold up a command to
     * one that is already open. The pool's lock used to be held across the handshake.
     */
    @Test fun `a slow handshake to one speaker does not block another`() = runBlocking<Unit> {
        val (member, coordinator) = guestTvJoinsKitchen()
        fake.dropConnection(member)
        withTimeout(5_000) { household.playerVolumes.first { member !in it } }
        fake.stallHandshakesTo(member, 4_000)

        val stalled = launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { household.setPlayerVolume(member, 10) } }
        // Long enough for the stalled open to be under way, well short of its stall.
        delay(300)
        val joined = household.state.value.groups.first { it.coordinatorId == coordinator }.id
        val started = System.nanoTime()
        withTimeout(2_000) { household.play(joined) }
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("play waited ${tookMillis}ms behind another speaker's handshake", tookMillis < 1_500)
        stalled.cancel()
    }

    /**
     * With Authentication on in the Sonos app, every Control API command is refused with
     * ERROR_NO_PERMISSION, getGroups first (x2rock, verified 2026-09-26). The player is
     * reachable and right, so its memory is kept, the error names the switch, and turning the
     * switch off is all a retry needs.
     *
     * From the remembered seed, not a passed one: that is the path that clears a seed whose
     * session failed, and the one a permission refusal must not take.
     */
    @Test fun `a household with Authentication on says so, and keeps the player it remembers`() = runBlocking<Unit> {
        fake.refuse("getGroups", "ERROR_NO_PERMISSION")
        val failure = runCatching { household.connect() }.exceptionOrNull()
        assertTrue("expected the refusal, got $failure", failure is SonosCommandException)
        val refused = household.state.value
        assertTrue(refused.authenticationRequired)
        assertEquals(AUTHENTICATION_REQUIRED, refused.error)
        assertNotNull("a refusing player is still the right one to remember", seeds.peek())

        fake.allow("getGroups")
        household.connect()
        val recovered = withTimeout(5_000) { household.state.first { it.connected } }
        assertFalse(recovered.authenticationRequired)
        assertNull(recovered.error)
    }

    // ------------------------------------------------------------- the UPnP switch
    //
    // Captured off the office One SL with UPnP on (getSettingsGroup and settingsChanged, both
    // at security version 9). Off is the capture with its one attribute flipped and the
    // version moved, which is what the switch in the Sonos app does to them.

    @Test fun `a household with UPnP on reads as on`() {
        connected()
        runBlocking { fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "getSettingsGroup" } }
        assertFalse(household.state.value.upnpOff)
    }

    @Test fun `the UPnP switch is followed in both directions`() = runBlocking<Unit> {
        connected()
        fake.setUpnpAllowed(false)
        withTimeout(5_000) { household.state.first { it.upnpOff } }
        fake.setUpnpAllowed(true)
        withTimeout(5_000) { household.state.first { !it.upnpOff } }
    }

    /** Every settings group's version rides in every event; only a moved `security` is news. */
    @Test fun `an event at the version already read asks nothing`() = runBlocking<Unit> {
        connected()
        fake.awaitCommand(timeoutMillis = 5_000) { it.get("command")?.asString == "getSettingsGroup" }
        delay(200)
        val reads = fake.commandsNamed("getSettingsGroup")
        // From the seed, or it is ignored for the wrong reason and this could not fail.
        fake.push("effectiveSettings:1", "settingsChanged",
            FakePlayer.fixture("event.settingsChanged.json").toString(), groupId = null, playerId = fake.id)
        delay(500)
        assertEquals(reads, fake.commandsNamed("getSettingsGroup"))
    }

    @Test fun `only the seed's settings are followed`() = runBlocking<Unit> {
        connected()
        val other = household.state.value.players.first { it.id != fake.id }.id
        fake.setUpnpAllowed(false, playerId = other)
        delay(1_000)
        assertFalse(household.state.value.upnpOff)
    }

    // ------------------------------------------------------------- playlists

    /** The office One SL's list, one saved queue: the id is bare, and there is a track count. */
    @Test fun `playlists are listed with their bare ids`() = runBlocking<Unit> {
        connected()
        val playlist = household.playlists().playlists.single()
        assertEquals("6", playlist.id)
        assertEquals(17, playlist.trackCount)
    }

    /** Not the default: loadPlaylist appends unless told, so REPLACE must be said. */
    @Test fun `a playlist replaces the queue and plays`() = runBlocking<Unit> {
        connected()
        val group = household.state.value.groups.first().id
        household.loadPlaylist(group, "6")
        val body = fake.lastCommandBody("loadPlaylist")!!
        assertEquals("REPLACE", body.get("action").asString)
        assertEquals("6", body.get("playlistId").asString)
        assertTrue(body.get("playOnCompletion").asBoolean)
    }

    /** Adding to the queue is APPEND, said explicitly, and does not start the playlist. */
    @Test fun `a playlist added to the queue appends and does not play`() = runBlocking<Unit> {
        connected()
        household.appendPlaylist(household.state.value.groups.first().id, "6")
        val body = fake.lastCommandBody("loadPlaylist")!!
        assertEquals("APPEND", body.get("action").asString)
        assertFalse(body.get("playOnCompletion").asBoolean)
    }

    /** The captured status carries a queueVersion. */
    @Test fun `a playback status carries the queue version`() = runBlocking<Unit> {
        connected()
        val groupId = household.state.value.groups.first { it.coordinatorId == fake.id }.id
        fake.pushFixture("playbackStatus", groupId)
        withTimeout(5_000) { household.groupStates.first { it[groupId]?.queueVersion == "8" } }
    }

    /**
     * Sonos lists an unplugged speaker for minutes. One group whose coordinator cannot be
     * reached must cost that group, not the session: on the Shield it cost every room.
     */
    @Test fun `a listed coordinator that cannot be reached does not sink the session`() = runBlocking<Unit> {
        val groups = FakePlayer.reachableTopology().getAsJsonArray("groups").map { it.asJsonObject }
        val kitchen = groups.first { it.get("name").asString == "Kitchen" }
        fake.makeUnreachable(kitchen.get("coordinatorId").asString)

        connected()
        val state = household.state.value
        assertTrue(state.connected)
        assertNull(state.error)
        val dining = state.groups.first { it.name == "Dining Room" }.id
        fake.push("groupVolume:1", "groupVolume", VOLUME_42, dining)
        withTimeout(5_000) { household.groupStates.first { it[dining]?.volume?.volume == 42 } }
    }

    /**
     * Plugged back in, with no topology change to announce it — Kitchen on the Shield was
     * listed as its own group throughout — the group must still be subscribed, by retrying.
     */
    @Test fun `a coordinator that comes back is subscribed without a topology change`() = runBlocking<Unit> {
        val groups = FakePlayer.reachableTopology().getAsJsonArray("groups").map { it.asJsonObject }
        val kitchen = groups.first { it.get("name").asString == "Kitchen" }
        val kitchenId = kitchen.get("id").asString
        fake.makeUnreachable(kitchen.get("coordinatorId").asString)
        connected()
        fake.clearHistory()

        fake.makeReachable(kitchen.get("coordinatorId").asString)
        fake.awaitCommand(timeoutMillis = 10_000) {
            it.get("command")?.asString == "subscribe" && it.get("groupId")?.asString == kitchenId
        }
    }

    /**
     * The real capture: Bedroom's left surround unplugged, every other member connected. The
     * other two zones have members too, so a parser counting members rather than disconnected
     * ones would put Living Room's four in the map and fail.
     */
    @Test fun `a bonded speaker that dropped off is counted against its room`() = runBlocking<Unit> {
        connected()
        fake.pushFixture("activeZonesChange")
        val offline = withTimeout(5_000) { household.state.first { it.offlineSpeakers.isNotEmpty() } }.offlineSpeakers
        val bedroom = household.state.value.groups.first { it.name == "Bedroom" }.coordinatorId
        assertEquals(mapOf(bedroom to 1), offline)
    }

    // ------------------------------------------------------------- HDMI

    private fun pushHdmi(playerId: String, fixture: String) =
        fake.push("hdmi:1", "hdmiStatus", FakePlayer.fixture(fixture).toString(), groupId = null, playerId = playerId)

    /** Both captures: a Beam with a TV is a TV room, a Beam with an empty port is not. */
    @Test fun `a soundbar with nothing in its HDMI port has no TV input`() = runBlocking<Unit> {
        connected()
        val state = household.state.value
        val guest = state.groups.first { it.name == "Guest TV" }
        val bedroom = state.groups.first { it.name == "Bedroom" }
        assertTrue("a Beam not yet heard from counts", state.hasTvInput(guest))

        pushHdmi(guest.coordinatorId, "event.hdmiStatus.noConnection.json")
        pushHdmi(bedroom.coordinatorId, "event.hdmiStatus.json")
        val after = withTimeout(5_000) { household.state.first { it.hdmiConnection.size == 2 } }
        assertFalse(after.hasTvInput(guest))
        assertTrue(after.hasTvInput(bedroom))
    }

    /**
     * Two rooms on a TV input is an ambiguity detection refuses to guess at — unless one of
     * them has nothing plugged in, which settles it.
     */
    @Test fun `an empty HDMI port takes a room out of TV detection`() = runBlocking<Unit> {
        connected()
        val state = household.state.value
        val guest = state.groups.first { it.name == "Guest TV" }
        val bedroom = state.groups.first { it.name == "Bedroom" }
        fake.pushFixture("tvMetadataStatus", guest.id)
        fake.pushFixture("tvMetadataStatus", bedroom.id)
        withTimeout(5_000) {
            household.groupStates.first { it[guest.id]?.onTvInput == true && it[bedroom.id]?.onTvInput == true }
        }
        assertNull("two TV rooms must not be guessed between", TvSoundbar.detect(household.state.value, household.groupStates.value))

        pushHdmi(guest.coordinatorId, "event.hdmiStatus.noConnection.json")
        withTimeout(5_000) { household.state.first { it.hdmiConnection.isNotEmpty() } }
        assertEquals(bedroom.coordinatorId, TvSoundbar.detect(household.state.value, household.groupStates.value))
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
