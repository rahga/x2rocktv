package com.rahga.x2rock.viewmodel

import com.rahga.x2rock.store.AppleMusicSetting
import androidx.lifecycle.SavedStateHandle
import com.rahga.x2rock.store.PrimaryAccounts
import com.rahga.x2rock.store.PreferredServices
import com.rahga.x2rock.lan.FakePlayer
import com.rahga.x2rock.lan.PlayerAddressBook
import com.rahga.x2rock.lan.SonosHousehold
import com.rahga.x2rock.lan.connectTo
import com.rahga.x2rock.lan.fakeHousehold
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress

/**
 * The browse/search *navigation* of [ServiceBrowseViewModel] — the part that is the view model's
 * own rather than the household's: opening a service, searching it, descending into a container,
 * and Back climbing back out a level at a time to the service list. The household end is covered
 * by `ServiceSearchTest`; this drives the same fakes through the view model.
 */
class ServiceBrowseViewModelTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    private lateinit var fake: FakePlayer
    private lateinit var upnp: MockWebServer
    private lateinit var service: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var household: SonosHousehold
    private lateinit var viewModel: ServiceBrowseViewModel
    /** Whether the service answers its root with nothing at all, as Sonos Radio does over SMAPI. */
    @Volatile private var emptyRoot = false
    /** Whether the service's manifest names a browse endpoint, as Sonos Radio's does. */
    @Volatile private var shelvesOn = false
    /** The page the browse endpoint answers with; Sonos Radio's unless a test says otherwise. */
    @Volatile private var browsePage = "sonosradio.browse.json"
    /** A captured SMAPI root to answer with, in place of the made-up children. */
    @Volatile private var rootFixture: String? = null
    /** The Authorization header the browse endpoint was last sent. */
    @Volatile private var browseAuth: String? = null

    /** Every SMAPI request body, in order. */
    private val smapiCalls = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        fake = FakePlayer().also { it.start() }
        service = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/manifest" -> MockResponse().setBody(
                        if (shelvesOn) """{"presentationMap":{"uri":"${url("/map")}"},"endpoints":[{"type":"browse","uri":"${url("/browse")}"}]}"""
                        else """{"presentationMap":{"uri":"${url("/map")}"}}"""
                    )
                    "/browse" -> {
                        browseAuth = request.getHeader("Authorization")
                        MockResponse().setBody(FakePlayer.fixtureText(browsePage))
                    }
                    "/map" -> MockResponse().setBody(MAP)
                    "/smapi" -> {
                        val body = request.body.readUtf8()
                        smapiCalls += body
                        MockResponse().setBody(when {
                            "<addToContainer" in body -> FakePlayer.fixtureText("smapi.deezer.addToContainer.xml")
                            "<id>$PLAYLIST</id>" in body -> FakePlayer.fixtureText("smapi.deezer.playlist.oneTrack.xml")
                            "<id>$ALBUM</id>" in body -> FakePlayer.fixtureText("getMetadata.plex.albumTracks.xml")
                            "<id>$EMPTY_ALBUM</id>" in body -> FakePlayer.fixtureText("getMetadata.soundcloud.emptyAlbum.xml")
                            "<getMetadata" !in body -> SEARCH_ALBUM
                            "<id>root</id>" in body && emptyRoot -> EMPTY
                            "<id>root</id>" in body && rootFixture != null -> FakePlayer.fixtureText(rootFixture!!)
                            // A page naming one item twice, as radio directories do: t1 at 1 and 2.
                            "<id>dups</id><index>0</index>" in body -> pageOf(listOf(0, 1, 1), total = 4)
                            "<id>dups</id><index>2</index>" in body -> pageOf(listOf(1), total = 4)
                            "<id>dups</id><index>3</index>" in body -> pageOf(listOf(3), total = 4)
                            "<id>long</id><index>0</index>" in body -> page(0, 2, total = 3)
                            "<id>long</id><index>2</index>" in body -> page(2, 1, total = 3)
                            "<id>$BOOK</id>" in body -> CHAPTERS
                            else -> CHILDREN
                        })
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        upnp = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (request.path == "/MusicServices/Control") MockResponse().setBody(services())
                    else MockResponse().setBody("<s:Envelope><s:Body><u:Resp/></s:Body></s:Envelope>")
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        scope = CoroutineScope(SupervisorJob())
        val book = PlayerAddressBook()
        val envelope = seal(
            """<ThirdPartyMediaServers><MediaServer UDN="SA_RINCON7943_X" SerialNum0="14"
               Token0="t" Key0="k" Nickname0="Qb1" Username0="X_#Svc7943-abc-Token"/></ThirdPartyMediaServers>""",
            fake.householdId.substringBefore('.'),
        )
        household = fakeHousehold(
            fake,
            scope,
            addressBook = book,
            upnpPort = upnp.port,
            accountCapture = { envelope },
        )
        runBlocking { household.connectTo(fake) }
        viewModel = ServiceBrowseViewModel(household, SavedStateHandle(mapOf("groupId" to fake.groupId(household))), PrimaryAccounts(FakePreferences()), PreferredServices(FakePreferences()), AppleMusicSetting(FakePreferences(), household))
    }

    @After fun tearDown() {
        household.disconnect(); scope.cancel(); fake.shutdown(); upnp.shutdown(); service.shutdown()
    }

    private suspend fun ready(): List<com.rahga.x2rock.smapi.LinkedService> =
        (withTimeout(5_000) { viewModel.services.first { it is ServiceBrowseViewModel.Services.Ready } }
            as ServiceBrowseViewModel.Services.Ready).services

    private suspend fun found(): List<com.rahga.x2rock.smapi.Item> =
        (withTimeout(5_000) { viewModel.results.first { it is ServiceBrowseViewModel.Results.Found } }
            as ServiceBrowseViewModel.Results.Found).items

    /**
     * The captured history holds an Apple Music album, but the speakers here store no Apple Music
     * sign-in: play history alone does not list it (owner's rule, 2026-10-18; see AppleMusicSettingTest).
     */
    @Test fun `Apple Music is not offered from play history alone`() = runBlocking<Unit> {
        val ready = withTimeout(5_000) { viewModel.services.first { it is ServiceBrowseViewModel.Services.Ready } }
            as ServiceBrowseViewModel.Services.Ready
        org.junit.Assert.assertFalse(ready.appleMusic)
    }

    /**
     * A track goes in at the playlist's end: its count, read first, is the index. Deezer's own
     * replies, captured with a scratch playlist; one track already in it, so the end is 1.
     */
    @Test fun `a track is added at the end of the playlist chosen`() = runBlocking<Unit> {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        val track = found().first { !it.container }
        val playlist = com.rahga.x2rock.smapi.Item(PLAYLIST, "Favorite tracks", "playlist", null, null, container = true, userContent = false, readOnly = false)
        smapiCalls.clear()
        viewModel.menu.addToPlaylist(ServiceItemMenu.Target.Service(qobuz, track), playlist)
        assertEquals("Added \"${track.title}\" to Favorite tracks", withTimeout(5_000) { viewModel.notice.first { it != null } })
        val add = smapiCalls.single { "<addToContainer" in it }
        assertTrue(add, "<parentId>$PLAYLIST</parentId>" in add)
        assertTrue(add, "<index>1</index>" in add)
        assertTrue(add, "<id>${track.id}</id>" in add)
    }

    /** A book's chapters open on the one its service has the listener in, marked with the place. */
    @Test fun `a book's chapters open on the chapter it resumes in`() = runBlocking<Unit> {
        viewModel.open(ready().first { it.service.id == "31" })
        found()
        viewModel.openChapters(com.rahga.x2rock.smapi.Item(BOOK, "Dune", "audiobook", null, null, container = true))
        val chapters = withTimeout(5_000) {
            viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.any { i -> i.id == RESUME_CHAPTER } == true }
        } as ServiceBrowseViewModel.Results.Found
        assertEquals(2, chapters.items.size)
        assertEquals(RESUME_CHAPTER, viewModel.resume.value?.id)
        assertEquals(689440L, viewModel.resume.value?.offsetMillis)
        assertEquals("focus starts on it", RESUME_CHAPTER, viewModel.returnTo.value)
    }

    /** That chapter carries on from the place; it does not play the chapter from its start. */
    @Test fun `pressing the resumed chapter carries on from its place`() = runBlocking<Unit> {
        viewModel.open(ready().first { it.service.id == "31" })
        found()
        viewModel.openChapters(com.rahga.x2rock.smapi.Item(BOOK, "Dune", "audiobook", null, null, container = true))
        val chapter = withTimeout(5_000) {
            viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.any { i -> i.id == RESUME_CHAPTER } == true }
        }.let { (it as ServiceBrowseViewModel.Results.Found).items.first { i -> i.id == RESUME_CHAPTER } }
        withTimeout(5_000) { viewModel.resume.first { it != null } }
        viewModel.select(chapter) { }
        // The chapter the place is in is loaded; the room then plays, as a player would say.
        fake.awaitCommand(10_000) { it.get("command")?.asString == "loadContent" }
        assertEquals(RESUME_CHAPTER, fake.lastCommandBody("loadContent")!!.getAsJsonObject("id").get("objectId").asString)
        fake.pushPlaybackStatus(fake.groupId(household), "PLAYBACK_STATE_PLAYING")
        // Then the seek to the place, which a plain play from the chapter's start lacks.
        val seek = fake.awaitCommand(10_000) { it.get("command")?.asString == "seek" }
        assertEquals("seek", seek.get("command").asString)
        assertEquals(689440L, fake.lastCommandBody("seek")!!.get("positionMillis").asLong)
    }

    @Test fun `opening a service exposes its search categories`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        withTimeout(5_000) { viewModel.categories.first { it.isNotEmpty() } }
        assertEquals("tracks", viewModel.categories.value.first().id)
    }

    /**
     * A service that can be searched still opens on its library: it used to open on a search field,
     * which left every library behind one — Deezer's Flow, Qobuz's playlists — out of reach.
     */
    @Test fun `a searchable service opens on its library, not on the search`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        assertEquals("SICKO MODE", found().first().title)
        withTimeout(5_000) { viewModel.categories.first { it.isNotEmpty() } }
        assertFalse("the search waits to be asked for", viewModel.searching.value)
    }

    /** Sonos Radio answers its SMAPI root with nothing, so its search is all there is to open on. */
    @Test fun `a searchable service with an empty root opens on the search`() = runBlocking {
        emptyRoot = true
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        withTimeout(5_000) { viewModel.searching.first { it } }
        assertTrue(viewModel.back())
        assertEquals("Back from that search leaves the service", null, viewModel.active.value)
    }

    /** Sonos Radio: an empty SMAPI root, and a page of shelves from its own browse endpoint instead. */
    @Test fun `an empty root with a browse endpoint opens on its shelves`() = runBlocking {
        emptyRoot = true
        shelvesOn = true
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        val shelves = withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.isNotEmpty() == true } }
            as ServiceBrowseViewModel.Results.Found
        assertEquals("Trending Now", shelves.items.first().title)
        assertFalse(viewModel.searching.value)
        viewModel.select(shelves.items.first()) {}
        val stations = viewModel.results.value as ServiceBrowseViewModel.Results.Found
        assertTrue(stations.items.any { it.id == "sonos:2997" })
        assertTrue(viewModel.back())
        assertEquals("Trending Now", (viewModel.results.value as ServiceBrowseViewModel.Results.Found).items.first().title)
    }

    /**
     * Spotify: a root of its own and a personal page, read with the account's token. The page's
     * shelves come first and the root's rows after them, so nothing the root reached is lost.
     */
    @Test fun `a page read with the account's token leads, and the root follows it`() = runBlocking {
        shelvesOn = true
        browsePage = "spotify.browse.json"
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        val page = withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.isNotEmpty() == true } }
            as ServiceBrowseViewModel.Results.Found
        assertEquals("Bearer t", browseAuth)
        assertEquals("More like Tyler, The Creator", page.items.first().title)
        assertTrue("the root's own rows follow the shelves", page.items.last().id !in page.items.take(4).map { it.id } && page.items.size > 4)
        viewModel.select(page.items.first { it.title == "Recommended for today" }) {}
        val albums = viewModel.results.value as ServiceBrowseViewModel.Results.Found
        assertEquals("spotify:album:3MJGcgbroIjiO90yi7RMhy", albums.items.first().id)
        assertTrue("an album on a shelf opens", albums.items.first().container)
    }

    /**
     * TuneIn, both captured off the home household on 2026-10-12: its page has Music, News & Talk and
     * Sports as shelves, and its SMAPI root has them again under other ids. Each is listed once, from
     * the page, and the root's own entries follow the shelves.
     */
    @Test fun `a root entry a shelf already names is left out`() = runBlocking {
        shelvesOn = true
        browsePage = "tunein.browse.home.json"
        rootFixture = "getMetadata.tunein.root.xml"
        viewModel.open(ready().first { it.service.id == "31" })
        val page = withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.isNotEmpty() == true } }
            as ServiceBrowseViewModel.Results.Found
        val titles = page.items.map { it.title }
        assertEquals(listOf("Music", "News & Talk", "Sports"), titles.filter { it in setOf("Music", "News & Talk", "Sports") })
        assertEquals("TuneIn's Exclusive Music Stations", titles.first())
        assertEquals(listOf("iHeartRadio", "Holiday Stations", "TuneIn Recommends", "Talk", "Trending", "Location"), titles.takeLast(6))
    }

    /** An album's tracks are numbered, as the Sonos app draws Plex's; a playlist's are not. */
    @Test fun `an album's tracks are numbered and a playlist's are not`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        found()
        viewModel.select(com.rahga.x2rock.smapi.Item(ALBUM, "00s Hits", "album", null, null, container = true)) {}
        val tracks = withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.any { t -> t.trackNumber == 1 } == true } }
            as ServiceBrowseViewModel.Results.Found
        // The capture is the first three of a hundred.
        assertEquals(listOf(1, 2, 3), tracks.items.take(3).map { it.trackNumber })
        assertTrue("an album is numbered", viewModel.numbered.value)

        viewModel.back()
        viewModel.select(com.rahga.x2rock.smapi.Item(PLAYLIST, "Favorite tracks", "playlist", null, null, container = true)) {}
        val listed = withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.singleOrNull()?.id?.let { id -> id != "tr:1" } == true } }
            as ServiceBrowseViewModel.Results.Found
        assertEquals("the playlist's one track", 1, listed.items.size)
        assertFalse("but a playlist is not numbered", viewModel.numbered.value)
    }

    /** SoundCloud's albums answer with nothing in them; the Sonos app's page for one has no Play. */
    @Test fun `an album that holds nothing offers no Play`() = runBlocking {
        viewModel.open(ready().first { it.service.id == "31" })
        found()
        viewModel.select(com.rahga.x2rock.smapi.Item("playlist->soundcloud:playlists:867830924", "400 Miles From L.A. 1955-56", "album", null, null, container = true)) {}
        assertTrue("an album offers Play while it loads", viewModel.playsWhole.value)
        withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.isEmpty() == true } }
        assertFalse("and none once it holds nothing", viewModel.playsWhole.value)
    }

    @Test fun `descending into a container, then Back, climbs out a level at a time`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        assertEquals("SICKO MODE", found().first().title)
        withTimeout(5_000) { viewModel.category.first { it != null } }

        viewModel.openSearch()
        assertTrue(viewModel.searching.value)
        viewModel.setQuery("astroworld")
        viewModel.search()
        withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.firstOrNull()?.container == true } }
        val hits = found()
        assertEquals("the search returns an album to open", "ASTROWORLD", hits.first().title)

        // Open the album: results become its children.
        viewModel.select(hits.first()) {}
        withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.firstOrNull()?.container == false } }
        assertEquals("SICKO MODE", found().first().title)
        assertFalse(viewModel.searching.value)

        // Back out of the container — still inside the service, and back on the search's hits
        // rather than an empty list under the query.
        assertTrue(viewModel.back())
        assertTrue(viewModel.searching.value)
        assertEquals("ASTROWORLD", (viewModel.results.value as ServiceBrowseViewModel.Results.Found).items.first().title)
        // Back out of the search — onto the library it was entered from, as it was left.
        assertTrue(viewModel.back())
        assertFalse(viewModel.searching.value)
        assertTrue(viewModel.active.value != null)
        assertEquals("SICKO MODE", (viewModel.results.value as ServiceBrowseViewModel.Results.Found).items.first().title)
        // Back again — out to the service list.
        assertTrue(viewModel.back())
        assertEquals(null, viewModel.active.value)
        // Back once more — nothing left to climb; the screen itself should close.
        assertFalse(viewModel.back())
    }

    /**
     * Opened from a Browse favourite, on its album: the album's tracks, with Play and Shuffle. Built
     * in the constructor, which is where this crashed on the TV — a flow the entry path set was
     * declared below the `init` that reached it, so it was still null.
     */
    @Test fun `a favourite's album opens on its tracks, playable whole`() = runBlocking {
        val groupId = fake.groupId(household)
        // Already read, as it is by the time Browse opens one: with nothing to wait for, the entry
        // runs inside the constructor — the order the TV crashed in.
        ready()
        val fromFavorite = ServiceBrowseViewModel(household, SavedStateHandle(mapOf(
            "groupId" to groupId, "service" to "31:sn_14", "container" to "album:9", "title" to "ASTROWORLD", "favorite" to "84",
        )), PrimaryAccounts(FakePreferences()), PreferredServices(FakePreferences()), AppleMusicSetting(FakePreferences(), household))
        val tracks = withTimeout(5_000) { fromFavorite.results.first { it is ServiceBrowseViewModel.Results.Found } }
        assertEquals("SICKO MODE", (tracks as ServiceBrowseViewModel.Results.Found).items.first().title)
        assertTrue(fromFavorite.playsWhole.value)
        assertFalse("its top is where Back leaves, for Browse", fromFavorite.back())
    }

    /**
     * Opened from search on an album, which names its kind: Play and Shuffle at the head, as on a
     * favourite's album. On an artist, which holds albums rather than tracks, neither. Search used
     * to name no kind, and an album found there offered no way to play it whole (2026-10-09).
     */
    @Test fun `an album opened from search is playable whole, an artist is not`() = runBlocking {
        val groupId = fake.groupId(household)
        ready()
        fun opened(kind: String) = ServiceBrowseViewModel(household, SavedStateHandle(mapOf(
            "groupId" to groupId, "service" to "31:sn_14", "container" to "album:9", "title" to "ASTROWORLD", "kind" to kind,
        )), PrimaryAccounts(FakePreferences()), PreferredServices(FakePreferences()), AppleMusicSetting(FakePreferences(), household))
        val album = opened("album")
        withTimeout(5_000) { album.results.first { it is ServiceBrowseViewModel.Results.Found } }
        assertTrue(album.playsWhole.value)
        val artist = opened("artist")
        withTimeout(5_000) { artist.results.first { it is ServiceBrowseViewModel.Results.Found } }
        assertFalse(artist.playsWhole.value)
    }

    /**
     * The next page starts where the service's count says, not where the list of distinct rows
     * does: a page that named one item twice used to make the next ask one too early, and here
     * that ask comes back as nothing but the repeat — the list then stalled short of its end.
     */
    @Test fun `a page with a repeat is read on from the service's own count`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        found()
        viewModel.browse("dups", "Dups")
        val first = withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.map { i -> i.id } == listOf("t0", "t1") } }
            as ServiceBrowseViewModel.Results.Found
        assertTrue(first.hasMore)

        viewModel.loadMore()
        val all = withTimeout(5_000) {
            viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.size == 3 }
        } as ServiceBrowseViewModel.Results.Found
        assertEquals(listOf("t0", "t1", "t3"), all.items.map { it.id })
        assertFalse(all.hasMore)
    }

    /** A container longer than one answer is read on as the list nears its end, not cut short. */
    @Test fun `a long container is read a page at a time`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        found()
        viewModel.browse("long", "Long")
        withTimeout(5_000) { viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.total == 3 } }
        val first = viewModel.results.value as ServiceBrowseViewModel.Results.Found
        assertEquals(2, first.items.size)
        assertTrue(first.hasMore)

        viewModel.loadMore()
        val all = withTimeout(5_000) {
            viewModel.results.first { (it as? ServiceBrowseViewModel.Results.Found)?.items?.size == 3 }
        } as ServiceBrowseViewModel.Results.Found
        assertEquals(listOf("t0", "t1", "t2"), all.items.map { it.id })
        assertFalse(all.hasMore)
    }

    /**
     * A browse-only service opens on its root, and that root is its top: Back from it leaves the
     * service. It used to re-read the root, which pushed it onto the trail again, so Back never
     * left — every anonymous radio service was a trap (Piraten.FM, on the Streamer, 2026-10-07).
     */
    @Test fun `Back from a browse-only service's root leaves the service`() = runBlocking {
        val radio = ready().first { it.service.id == "894" }
        viewModel.open(radio)
        found()
        assertTrue(viewModel.categories.value.isEmpty())
        assertTrue(viewModel.back())
        assertEquals("Back left the service", null, viewModel.active.value)
        assertFalse(viewModel.back())
    }

    @Test fun `queuing a container that holds no tracks is turned away`() = runBlocking {
        val qobuz = ready().first { it.service.id == "31" }
        viewModel.open(qobuz)
        withTimeout(5_000) { viewModel.category.first { it != null } }
        val artist = com.rahga.x2rock.smapi.Item("artist:1", "x", "artist", null, null, container = true)
        viewModel.menu.queue(ServiceItemMenu.Target.Service(qobuz, artist))
        assertTrue(withTimeout(5_000) { viewModel.notice.first { it != null } }!!.contains("isn't something"))
    }

    private fun services(): String {
        val smapi = service.url("/smapi"); val manifest = service.url("/manifest")
        val descriptors = """<Services SchemaVersion="1"><Service Id="31" Name="Qobuz" Uri="$smapi" SecureUri="$smapi">""" +
            """<Policy Auth="AppLink"/><Manifest Uri="$manifest"/></Service>""" +
            // An anonymous radio service with no presentation map: nothing to search, browse only.
            """<Service Id="894" Name="Pirate Radio" Uri="$smapi" SecureUri="$smapi"><Policy Auth="Anonymous"/></Service>""" +
            """</Services>"""
        val escaped = descriptors.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>""" +
            """<u:ListAvailableServicesResponse xmlns:u="urn:schemas-upnp-org:service:MusicServices:1">""" +
            """<AvailableServiceDescriptorList>$escaped</AvailableServiceDescriptorList>""" +
            """<AvailableServiceTypeList>7943</AvailableServiceTypeList></u:ListAvailableServicesResponse></s:Body></s:Envelope>"""
    }

    private companion object {
        /**
         * Audible's Dune as captured (2026-10-06): the book's id, two of its chapter ids and its
         * place. The second chapter's title was not recorded, so it is named by the span its id
         * gives in book time, 1:59–29:55, rather than invented.
         */
        /** Deezer's one playlist as its listing names it (number redacted), and the track added. */
        const val PLAYLIST = "playlist_spotify:playlist-10000000001"
        const val ALBUM = "plex-album"
        const val EMPTY_ALBUM = "playlist-&gt;soundcloud:playlists:867830924"

        const val BOOK = "reftitle:B002V1OF70_com"
        const val RESUME_CHAPTER = "refchapter:B002V1OF70_119953071_com_119000_1795000"
        const val CHAPTERS = """<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>
            <getMetadataResponse><getMetadataResult><index>0</index><count>2</count><total>2</total>
              <positionInformation><id>$RESUME_CHAPTER</id><index>0</index><offsetMillis>689440</offsetMillis></positionInformation>
              <mediaMetadata><id>refchapter:B002V1OF70_119953071_com_0_61000</id><itemType>track</itemType><title>Opening Credits</title></mediaMetadata>
              <mediaMetadata><id>$RESUME_CHAPTER</id><itemType>track</itemType><title>1:59–29:55</title></mediaMetadata>
            </getMetadataResult></getMetadataResponse></soap:Body></soap:Envelope>"""

        const val MAP = """<Presentation><PresentationMap type="Search"><Match>
            <SearchCategories><Category id="tracks" mappedId="track"/></SearchCategories></Match></PresentationMap></Presentation>"""
        const val SEARCH_ALBUM =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><searchResponse><searchResult>""" +
                """<total>1</total><mediaCollection><id>album:9</id><itemType>album</itemType><title>ASTROWORLD</title>""" +
                """</mediaCollection></searchResult></searchResponse></s:Body></s:Envelope>"""
        const val CHILDREN =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><getMetadataResponse><getMetadataResult>""" +
                """<total>1</total><mediaMetadata><id>tr:1</id><itemType>track</itemType><title>SICKO MODE</title></mediaMetadata>""" +
                """</getMetadataResult></getMetadataResponse></s:Body></s:Envelope>"""

        const val EMPTY =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><getMetadataResponse><getMetadataResult>""" +
                """<total>0</total></getMetadataResult></getMetadataResponse></s:Body></s:Envelope>"""

        /** Tracks `t<n>` for each of [ids], as one page of a container of [total]. */
        fun pageOf(ids: List<Int>, total: Int) =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><getMetadataResponse><getMetadataResult>""" +
                "<count>${ids.size}</count><total>$total</total>" +
                ids.joinToString("") { "<mediaMetadata><id>t$it</id><itemType>track</itemType><title>Track $it</title></mediaMetadata>" } +
                """</getMetadataResult></getMetadataResponse></s:Body></s:Envelope>"""

        /** [count] tracks from [index] of a container of [total]. */
        fun page(index: Int, count: Int, total: Int) =
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><getMetadataResponse><getMetadataResult>""" +
                "<index>$index</index><count>$count</count><total>$total</total>" +
                (index until index + count).joinToString("") {
                    "<mediaMetadata><id>t$it</id><itemType>track</itemType><title>Track $it</title></mediaMetadata>"
                } +
                """</getMetadataResult></getMetadataResponse></s:Body></s:Envelope>"""

        fun seal(xml: String, householdId: String) = com.rahga.x2rock.smapi.TestEnvelope.seal(xml, householdId)
    }
}
