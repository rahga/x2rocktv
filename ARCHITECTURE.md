# x2rock — Architecture & Codebase Overview

A Sonos controller for Google TV / Android TV, written in Kotlin with Jetpack Compose for TV.

> **The app talks to the speakers over the LAN, with no Sonos account.** The cloud Control
> API and its OAuth flow were removed in 2026-09: the same API is served by the players
> themselves, it pushes instead of polling, and Sonos's consent page cannot be completed
> with a TV remote anyway. The protocol, and the evidence behind every claim about it, is
> in [`docs/lan-transport.md`](docs/lan-transport.md) — read that before touching `:core`.
> What is still open is at the head of [`docs/punch-list.md`](docs/punch-list.md); the rest of it is the record.

---

## What it does

x2rock controls Sonos speakers from a TV remote. There is no sign-in: a player is found on
the local network by SSDP, or by mDNS where SSDP draws nothing (`NsdMdnsDiscovery`), or is
remembered from last time, and answers without an account.

Rooms are listed in a sidebar, alphabetically until the viewer moves one (a hold picks a room
up; the order is this device's). Selecting a room shows
what it is playing with the controls its source permits; a room on its soundbar's TV input
gets its own pane with Night Sound and Speech Enhancement instead. The room panel has two halves,
each opened by its own pane button: **Group** — play everywhere, the rooms playing together, the
rooms that could join, presets — and **Configure** — source (TV input, line-in), sleep timer,
sound, and room settings with "This is my TV". The queue and the
household's favourites each have a screen. A track on a service that publishes ratings can be
rated up or down. There are six dark colour themes.

The app opens on the television's own room: the soundbar the device is plugged into, found by
`TvSoundbar` or named by the viewer. Each room is also a tile on the TV home screen's channel,
and the selected room is the system's media session.

---

## Modules

Two Gradle modules. `:core` is a plain Kotlin/JVM library with no Android dependency — it is
everything that talks to Sonos. `:app` is the Android TV application and depends on `:core`.

A third module `:cli` (a Linux command line plus an MPRIS daemon) was removed in 2026-09;
the Rust project `rahga/x2rock` covers that ground on the desktop.

`:core` carries no DI annotations: `:app`'s Hilt modules construct its classes, and any other
frontend just calls the constructors.
`:core` also publishes `FakePlayer` and the captured fixtures as **test fixtures**, so `:app`'s
view models are tested against the same fake household over a real socket.

## File Map

```
core/src/main/kotlin/com/rahga/x2rock/          (pure JVM — no Android, so it is
│                                                testable against real speakers)
├── model/
│   ├── SonosModels.kt             Control API shapes, shared by every layer
│   └── AppColorTheme.kt           Enum: DEFAULT, OCEAN, EMBER, FOREST, ORCHID, ARTWORK (accent from the cover)
│
├── lan/
│   ├── Frame.kt                   [header, body] wire format; reply vs event
│   ├── PlayerNames.kt             RINCON id → sonos-<MAC>.local
│   ├── LanHttp.kt                 the player-only OkHttp client + address book
│   ├── SonosSocket.kt             one socket to one player
│   ├── CallAwait.kt               an OkHttp call awaited, and cancelled with its caller, body included
│   ├── BoundedSocket.kt           a blocking socket exchange bounded as a whole, and closed on cancellation
│   ├── Discovery.kt               SSDP: id, address and household in one reply
│   ├── MdnsDiscovery.kt           the seam for `_sonos._tcp` over mDNS when SSDP finds nothing; the platform supplies it
│   ├── MulticastGate.kt           whatever the platform must hold for multicast
│   ├── SeedStore.kt               the last reachable player (per network), for a warm start
│   ├── SonosHousehold.kt          state flows, commands, subscriptions, reconnection
│   ├── TrackFavorites.kt          current-track favorite metadata, identity and duplicate matching
│   ├── Upnp.kt                    cleartext 1400: queue, favorites, TV input, EQ, service list, alarms
│   ├── Alarms.kt                  an alarm, the household clock, favourites as alarm programs
│   ├── Xml.kt                     the one parser for UPnP and SMAPI replies, DOCTYPE refused by hand
│   ├── TvSoundbar.kt              which soundbar this television is plugged into
│   └── PlayModes.kt               repeat flags ↔ the app's enum
│
├── account/                       T-27: a developer's own integration; inert unless a build carries one
│   ├── SonosAccount.kt            the integration, OAuth code redemption and refresh, the token store seam, signInState
│   └── SignInCallback.kt          the TV listening once on the LAN for the phone's forwarded `/callback?code=…`
│
├── radio/RadioDirectory.kt        Radio Browser: stations by votes, genre or country
├── apple/ITunesSearch.kt         Apple Music's catalogue through Apple's public iTunes search
├── apple/AppleMusic.kt           a result as the player plays it: loadContent id, queue URI/DIDL
│
└── smapi/                         a service's own server: ratings, and search/browse
    ├── Smapi.kt                   envelope, parseServices; search/getMetadata/getMediaURI; ratings
    ├── StoredAccounts.kt          decrypt the household's ThirdPartyMediaServersX tokens
    ├── AccountCapture.kt          one-shot GENA capture of that encrypted token blob off a player
    ├── LinkedServices.kt          which services a stored token (or anonymity) actually unlocks
    ├── ServiceContent.kt          what a service item is (opens, enqueues, streams), and its URI/DIDL
    ├── ServicePages.kt            T-28 seam: a service's pages from an untracked source, via ServiceLoader; none ships
    ├── RatingsCatalogue.kt        a service's rating rules, fetched on a miss
    └── RatingsStore.kt            where those rules are remembered (seam)

core/src/testFixtures/kotlin/com/rahga/x2rock/  (shared by :core's and :app's tests)
├── lan/FakePlayer.kt              an in-process TLS player replaying captured fixtures
├── lan/FakeUpnp.kt                SOAP helpers for MockWebServer fakes: the action, the arguments, an empty reply
├── lan/TestHousehold.kt           fakeHousehold: no multicast, SSDP, mDNS or account capture; FakeSeedStore
└── smapi/TestEnvelope.kt          seals account XML as a player does, with the tests' own salt

app/src/main/java/com/rahga/x2rock/             (Android TV)
├── X2RockApp.kt                   Hilt entry point; image loader on the LAN client
├── MainActivity.kt                Single activity; deep links; Compose content + theme
├── DebugSwitches.kt               debug-build switches set from the launch intent (mDNS only, …)
│
├── store/Preferences.kt           the key-value seam every store sits on
├── store/ThemeStore.kt            StateFlow-backed theme preference
├── store/RoomPreferencesStore.kt  the television's soundbar, as a player id
├── store/PendingRoomDeepLink.kt   a room asked for by a channel tile, until it exists
├── store/AfterPlaying.kt          whether starting something stays in the browser or returns to the room
├── store/AppleMusicSetting.kt     whether Apple Music is listed: a stored sign-in, or the Advanced switch
├── store/BetaFeatures.kt          Settings → Beta features, and the last refusal one met, for the report
├── store/DeveloperAccount.kt      the T-27 account where the build carries an integration; null otherwise
├── store/RecentSearches.kt        this device's last eight search terms, newest first
├── net/
│   ├── ArtHttp.kt                 cover art: bounded, and players and CDNs on separate clients
│   ├── NetworkMonitor.kt          ConnectivityManager → household.onNetworkChanged()
│   ├── PrefsSeedStore.kt          SeedStore over preferences, one player per network
│   ├── NetworkIdentity.kt         which network this is: default gateways + DHCP domain
│   ├── NsdMdnsDiscovery.kt        _sonos._tcp over NsdManager, when SSDP finds nothing
│   └── WifiMulticastGate.kt       a MulticastLock held across SSDP
├── smapi/PrefsRatingsStore.kt     RatingsStore over Preferences
├── store/PresetStore.kt           presets: rooms, levels, a favourite — this device's, as JSON
├── store/PrimaryAccounts.kt       a service's primary account on this device: first, and what Search asks
├── store/PreferredServices.kt     the services this device leads with, in order: Search and Music Services
├── media/NowPlayingPublisher.kt   the MediaSession, behind an interface
├── channel/
│   ├── RoomsChannelSync.kt        rooms as tiles on the TV home screen channel
│   ├── PosterArt.kt               a tile's poster as the launcher can fetch it: the player's address, not its name
│   └── ChannelSyncReceiver.kt     INITIALIZE_PROGRAMS: connect, sync, within budget
│
├── di/
│   ├── AppModule.kt               app scope, address book, LAN and internet clients, household
│   └── StoreModule.kt             binds the seams: Preferences, ChannelSync, publisher, ratings
│
├── ui/
│   ├── NavGraph.kt                the routes, the room name each screen is opened on, and PlayerFollowsHome
│   ├── ServiceLogos.kt            each service's logo, generated by tools/service-logos.py
│   ├── ServiceLogo.kt             a player's service name to its logo
│   ├── theme/
│   │   ├── Theme.kt               dark colour schemes, the ten-foot type scale; AppButton, IconAppButton,
│   │   │                          AppCard and the one focus border every card wears
│   │   └── ArtColors.kt           an accent and a shade from cover art (Palette), for the pane's tint
│   │                              and the Artwork theme
│   ├── components/
│   │   ├── DpadMenuKey.kt         the Menu key as a row's second action; a hold is the Card's own onLongClick
│   │   ├── MediaRow.kt            every list's row (fixed art slot, marquee on focus) and ScreenHeader
│   │   │                          (Back, title, "in <room>")
│   │   ├── SearchField.kt         the one text field, shared by every search
│   │   ├── ItemMenu.kt            an item's menu (hold or Menu): Sonos's actions, then the service's
│   │   ├── ServiceItemMenuOverlay.kt  ServiceItemMenu drawn: play now/next, queue, radio, artist,
│   │   │                          album, the account's favourites
│   │   ├── NoticeBanner.kt        one line for a few seconds; never focusable
│   │   ├── StepMark.kt            − and + beside a level, while it has focus or by touch
│   │   ├── Touch.kt               a tap as a press, for the emulator and tablets; inert on a remote
│   │   ├── DotEqualizer.kt        a playing room's mark, and DotScanner for loading
│   │   ├── SignInsNote.kt         why services needing an account are missing, when stored sign-ins could not be read
│   │   └── Overlay.kt             pins focus inside a modal
│   └── screens/
│       ├── HomeScreen.kt          room sidebar + player pane; settings slide-in
│       ├── PlayerScreen.kt        the player pane (`PlayerPane`), in its music and TV forms
│       ├── SeekBar.kt             the pane's progress bar, its knob and seek bursts, the time labels
│       ├── NowPlayingScreen.kt    the room's music full screen: art, track, room, clock; any key closes
│       ├── HeaderVolume.kt        the pane's header: room, volume wedge, the set-level overlay
│       ├── SleepTimer.kt          the sleep timer's button, icon, countdown and picker
│       ├── PaneButtons.kt         Named places, Group, the mode and rating buttons, the overhang
│       │                          (Configure, in Sleep timer's old place, is in SleepTimer.kt)
│       ├── RoomPanel.kt           everything one room can be told to do, in two halves: Group, Configure
│       ├── QueueScreen.kt         the queue — click to jump, long-press to remove
│       ├── FavoritesScreen.kt     Browse: presets, favourites by kind, playlists, recently played;
│       │                          Radio and Music Services one press up
│       ├── SearchScreen.kt        one search across every service and Apple Music, by section
│       ├── PreferredServicesScreen.kt  Settings' sorter: mark services preferred, carry them into order
│       ├── AlarmsScreen.kt        Settings' alarms: every room's, armed on Select; a new one step by step
│       ├── AboutSystemScreen.kt   Settings' About this system: every speaker, its software, any update waiting
│       ├── AccountScreen.kt       Settings → Sonos account (T-27): a QR code to sign in from a phone, or sign out
│       ├── BetaReportScreen.kt    Settings → Beta features → Send a report: the text, and a QR code to a draft issue
│       ├── StayPin.kt             the pin at the right of every Browse header: hold in the browser or release
│       ├── RadioScreen.kt         the radio directory, by category — click to play
│       ├── AppleMusicScreen.kt    Apple Music search — click to play, hold or Menu to queue
│       ├── ServiceBrowseScreen.kt the household's own services — each opens on its library (or its
│       │                          page of shelves), search one press up; Play/Shuffle on a container
│       └── NowPlayingBar.kt       what the room plays, stream or track, on the queue and Browse screens
│
└── viewmodel/
    ├── HomeViewModel.kt
    ├── PlayerViewModel.kt
    ├── NowPlayingViewModel.kt      the full-screen view's room, its own so it follows a regroup
    ├── QueueViewModel.kt
    ├── FavoritesViewModel.kt
    ├── RadioViewModel.kt
    ├── AppleMusicViewModel.kt
    ├── ServiceBrowseViewModel.kt   browse/search the household's own services, a level at a time
    ├── SearchViewModel.kt          fan one query out to every searchable service and Apple Music
    ├── AboutSystemViewModel.kt     every speaker's description and update state, read once
    ├── AccountViewModel.kt         the T-27 sign-in: show the consent page's QR, listen once, redeem the code
    ├── AfterPlayingViewModel.kt    AfterPlaying, for the nav graph and Browse's header
    ├── BetaReportViewModel.kt      the beta report as a prefilled GitHub issue, read once on opening
    ├── AlarmsViewModel.kt          the household's alarms, the clock they are on, and the new-alarm steps
    ├── PreferredServicesViewModel.kt  the household's services, for the preferred-services sorter
    ├── ServiceItemMenu.kt          an item's menu, shared by both: play now/next, queue, its service's say
    ├── StartGuard.kt               one thing starting at a time, marked for its row; every screen's start
    ├── Notices.kt                  TransientNotice: one line of "that didn't work", and its wording;
    │                               SharedNotice, the player's line every screen draws
    ├── RoomTarget.kt               the room a screen was opened for, followed across regroups; SavedRoom,
    │                               the room chosen, the one anchor every surface follows, kept through
    │                               process death; RoomRoute, a room as one screen hands it to the next
    ├── MonotonicClock.kt           milliseconds that only go up (sleep timer ends), a test seam
    ├── VolumeBurst.kt              how far one volume press moves: 2, 3, 5 within a burst, finer when quiet
    ├── FavoriteGroups.kt           favourites grouped and labelled, and which open on a page
    └── RoomActivity.kt             Playing / Paused / Stopped / Nothing playing — one rule for every screen

app/src/androidTest/java/com/rahga/x2rock/      (Compose UI tests on the emulator, over FakePlayer)
├── FocusChoreographyTest.kt       the home screen's focus paths
├── AlarmPaneTest.kt               a ringing alarm's Snooze and Stop take the places row's slot (P-25)
├── BrowseFocusReturnTest.kt       Back to Browse lands on the opening row, even one scrolled out of view (P-02)
├── SearchFieldFocusTest.kt        the D-pad leaves the search field once the keyboard is closed (P-19)
├── DpadMenuKeyTest.kt             Menu acts once per press, repeats consumed (P-69); a hold's release clicks nothing it moved to
└── TestDoubles.kt                 in-memory Preferences, no channel sync, no media session
```

---

## Screens & Navigation

```
home  ───────────────────────────────────────────────── start, always
  ├─→ queue?groupId={id}
  ├─→ nowPlaying?groupId={id}          Full screen: the art, the track, the clock
  ├─→ search?groupId={id}              every service at once
  │     └─→ services?…&service=&container=   an album or artist, opened in its service's browser
  ├─→ favorites?groupId={id}           "Browse"
  │     ├─→ radio?groupId={id}
  │     └─→ services?groupId={id}      pick a service, search or browse, play in the room
  │           └─→ applemusic?groupId={id}
  └─→ Settings, at the foot of the room list
        ├─→ preferredServices
        ├─→ alarms                     every room's, made a step at a time
        ├─→ account                    Sonos account (T-27): only in a build carrying an integration, Beta on
        ├─→ report                     Send a report: only with Beta features on
        └─→ about                      About this system: each speaker and its update state
```

There is no login route. `x2rock://room/{playerId}` deep-links from the TV home-screen channel
tiles, and `x2rock://home` opens the app. A tile names its room's coordinator, a *player*, because
a regroup mints new group ids; `HomeViewModel` waits for the topology and opens whichever group
holds that player now. A group id, from a tile published before 2026-10-02, still opens while it
exists. Posters are the speaker's art by address (`PosterArt`), since the launcher cannot resolve
`.local`.

**HomeScreen** is a split pane:
- Left: `RoomSidebar` — the rooms, then Settings at the foot. Each row carries a 56dp art slot
  (cover, station logo, or a TV or radio glyph), the room name, two lines of what is playing
  (three for a soundbar on its input), a dotted level meter on a room that is playing (nothing
  on one that is not), and a dim TV badge on a room that has an HDMI input but is not on it. A room not playing is
  drawn quieter; the selected room keeps a tint while focus is in the pane. Selection follows
  focus. Select enters the player pane; a hold or the Menu key picks the room up to move it in
  the list (Left then offers to play everywhere from it). The pane's Group and Configure buttons
  open `RoomPanel`.
- Right: `PlayerScreen` for the selected room.

**PlayerScreen** — the player pane — draws one of two panes under a shared header: the room's
name and what it is doing, and opposite it the group's volume (speaker, wedge, level; select
mutes, left and right step it while focused). Behind it, a tint from the cover.
- **Music:** art, track and artist (or a stream's own text and its station), a progress bar
  where there is a duration, then two rows — transport, flanked by shuffle and repeat as icons
  the way the Sonos app draws them (each only where `availablePlaybackActions` permits it, plus
  the rating thumbs where a press can succeed), all icons, and places (Queue, Browse, Search,
  Group, Configure, full screen) — with a volume pill per speaker in the header when the room
  is grouped. A room with nothing loaded says so and offers Browse.
- **TV:** a television glyph with the input format, Night Sound and Speech Enhancement, and a
  way back into music through Browse and Search.

**RoomPanel** — see "The room panel" in `CLAUDE.md` for its shape and the reasons for it.

Focus between the sidebar and the pane is wired as explicit key handlers, not
`focusProperties`; `CLAUDE.md` says why.

---

## Data Flow

**Nothing polls.** The speakers push, and every link above them is already reactive:

```
speakers  ──push──▶  SonosSocket        wss://sonos-<MAC>.local:1443
                         │  events
                         ▼
                     SonosHousehold     StateFlow<HouseholdState>
                         │              StateFlow<Map<String, GroupState>>
                         │              StateFlow<Map<String, GroupVolume>>  (per player)
                         ▼
                     ViewModel          combine(...) → StateFlow<UiState>
                         │  collectAsState()
                         ▼
                     Compose            recomposes only on real change
```

State in each ViewModel is derived rather than assembled by hand, so it recomputes when
something actually changes and at no other time. A command's effect arrives as an event
like any other, which is why nothing re-fetches after acting.

Five things are asked for rather than pushed, each because nothing pushes it:
- **The queue**, over UPnP — the Control API has none. Stale until re-read.
- **Night Sound and Speech Enhancement**, from `settings:1 getPlayerSettings`: `homeTheater:1`
  accepts a subscription and never fires. Read once per room and after each write. Tone and
  the volume limit come from the same read.
- **A track's rating**, from the service. Read once per room and track and after each press.
- **Favourites**, read when their screen opens.
- **Alarms**, read when their screen opens and after each change made there.

---

## ViewModels

### HomeViewModel
Derives the room list from the household's flows — placed rooms in this device's order, the
rest alphabetically after them (`sortGroups`, `movedRanks`) — and
connects on first display. Tracks the selected group, sidebar visibility and the television's
soundbar. Follows the *speakers* rather than the group id when a regroup mints new ids. Moves
the selection to the television's room once it is known, but only while the selection is still
the app's own default choice. Handles moving a room, party mode (optionally evening the levels
first), joining, leaving, TV input, "This is my TV", the panel's sound and room settings and the
theme.

### PlayerViewModel
Derives everything visible for the selected group from pushed state, and feeds
`NowPlayingPublisher` so media keys and the TV's transport controls work. It never takes the
volume keys. Manages:
- Volume and seek debouncing: 300ms, accumulating, so a held key sends one command by the
  total rather than one per repeat. A volume press is sized by `VolumeBurst` (larger within a
  burst, finer when quiet) and sent as `setRelativeVolume` (Sonos's rule for stateless
  controls); the header's set-level overlay sends `setVolume`
- The home-theatre reading and its optimistic toggles
- The rating state
- A notice line: a rating's result, or why a command failed
- The room's sleep timer: Sonos's own, so every controller sees it and the room pauses
  itself. Pushed by `sleepTimer:1` on every set and cancel, whoever made it, and counted down
  locally between reports

The selected room reaches it from the home screen's view model, wherever the viewer is:
`PlayerFollowsHome`, in the nav graph, calls `selectGroup` whenever the selection or its group
changes, so a preset started from Browse with the Stay pin held moves the bar and the media keys
too (P-100).

### QueueViewModel
Read on entry, and again whenever the room's pushed `queueVersion` moves while it is open — which is
not every outside edit while a station plays (P-29). `playItem` reads the queue first and plays the
track where it now is (P-57); edits are serialised and guarded by the `UpdateID` read with the list,
so one made under another controller's edit is refused rather than landing on the wrong row (P-41).

### FavoritesViewModel
Loads once on entry. Marks the active favourite by matching the current container name.

---

## Transport Layer (`:core`, package `lan`)

Everything that talks to a speaker. Pure Kotlin/JVM with no Android dependency, which is
what lets it be exercised against real hardware from a plain JVM test — that is how it was
developed, and it is worth keeping that way.

| File | What it is |
|---|---|
| `Frame.kt` | the `[header, body]` wire format. `success` is the only thing separating a reply from an unsolicited event |
| `PlayerNames.kt` | derives `sonos-<MAC>.local` from a RINCON player id |
| `LanHttp.kt` | the player-only OkHttp client, and the address book standing in for the mDNS Android has no resolver for |
| `SonosSocket.kt` | one socket to one player: handshake, `cmdId` correlation, event flow, keepalive, failure reporting |
| `Discovery.kt` | SSDP. The reply carries the player id *and* the long household id, which is why the first connection can be made to a verified name |
| `MulticastGate.kt` | the seam for Android's `MulticastLock`; nothing to hold on Ethernet or a desktop JVM |
| `SeedStore.kt` | one remembered player (per network, where the store is keyed), probed first so a warm start skips discovery |
| `SonosHousehold.kt` | the live view: connections, subscriptions, state flows, commands, reconnection, ratings |
| `Upnp.kt` | cleartext port 1400 — the queue, the TV input switch, `SetEQ`, the music-service list, and `AlarmClock` |
| `TvSoundbar.kt` | among the rooms with an HDMI input, the one currently on it |
| `PlayModes.kt` | Sonos's two repeat booleans ↔ the app's three-way enum |

### SonosHousehold

Holds `StateFlow<HouseholdState>` (groups, players, connection), `StateFlow<Map<String,
GroupState>>` (playback, metadata, volume, play modes and permitted actions per group), and
per-player volumes. One socket per group coordinator, because group-scoped namespaces are
answered by coordinators only; player-scoped calls open that player's own socket. Both are
pooled by hostname.

A `groups:1` event brings subscriptions back in line: a regroup mints new group ids, so groups
new since the last event are subscribed and vanished ones dropped, serialised under a lock and
reading the topology itself. `CLAUDE.md` records the two bugs that shape this.

Only the seed's socket and coordinators' sockets carry the session: the seed holds
`groups:1`, a coordinator its group's subscriptions. Losing one of those rebuilds. A member's
socket carries only that speaker's own volume, so losing it evicts it from the pool and
withdraws that level, and the next `groups:1` catch-up brings it back. Sockets are opened
outside the pool's lock, so one slow or unreachable speaker never holds up the rest.

Reconnection is capped exponential backoff, 1s doubling to 60s. It rebuilds from scratch
rather than repairing in place: subscriptions do not survive a reconnect and there is no
replay buffer, so the fresh snapshot is truth. `onNetworkChanged()` skips the wait entirely,
because a resumed socket can accept writes and never report failure.

### Leaving the LAN (`:core`, packages `smapi`, `radio`, `apple`, `account`)

Control never leaves the household, but some traffic does, all of it over the `@InternetHttp`
client — never the LAN one, whose trust manager accepts the players' leaf-only chains:
- a service's own SMAPI endpoint, for search, browse, favourites, playlists and ratings, and its
  manifest's `browse` endpoint for a service's page of shelves
- Sonos's CDN, for a service's manifest and presentation map
- Radio Browser (`RadioDirectory`) and Apple's iTunes search (`ITunesSearch`)
- with T-27 only, `api.sonos.com` to redeem and refresh the account token; with T-28 only, the
  untracked page source

**Ratings.** A rating starts on the LAN with `ListAvailableServices`, then fetches the service's
manifest and presentation map from the CDN (cached per service by `RatingsCatalogue`, including
"publishes none"), then calls `getExtendedMetadata` and `rateItem` on the service itself. A
service is reached with the household's own credential: none for an anonymous one, the token
read off a player (`StoredAccounts`, the one search uses) for the rest, picking the account the
track plays from. No account linking happens on the TV. `SonosHousehold.ratingState` answers
null wherever `rate` would refuse, so the buttons are drawn exactly when a press can succeed.

### Authentication

By default none: the LAN takes commands with no account, OAuth or token. Two exceptions:
- **The household's stored service sign-ins** (`StoredAccounts`): the music-service tokens the
  speakers already hold, read off a player and decrypted with a salt the build carries, so
  services needing an account can be searched, browsed and rated. Nothing is stored by the app.
- **A developer's own integration** (T-27, `account/`), only in a build made with
  `-Px2rock.sonosIntegration`: the TV is signed in from a phone, the token is kept in the app's
  private preferences, and it rides in each command's header as `authorization: Bearer …`.

---

## Namespaces Used

Commands and events are `[header, body]` frames over the WebSocket, not REST paths.

| Namespace | Scope | Used for |
|---|---|---|
| `groups:1` | household | topology, `getGroups`, `modifyGroupMembers` |
| `playback:1` | group (coordinator) | transport, seek, play modes, permitted actions |
| `playbackMetadata:1` | group (coordinator) | track, art, container, `streamInfo`, `htInputFormat` |
| `groupVolume:1` | group (coordinator) | group volume and mute |
| `playerVolume:1` | **that player's own socket** | per-speaker volume and mute |
| `favorites:1` | household / group | listing and loading favourites |
| `playlists:1` | household / group | listing Sonos playlists, and loading one with `action: REPLACE` |
| `history:1` | household | recently played, `getHistory` |
| `playbackSession:1` | group (coordinator) | `createSession`, then `loadStreamUrl` in it: a radio-directory station or Play a URL |
| `sleepTimer:1` | group (coordinator) | the room's sleep timer, subscribed and pushed (set over UPnP) |
| `playback:1 loadContent` | group (coordinator) | playing a recently played item again: load, then press play until pushed as playing |
| `settings:1` | player | `getPlayerSettings`: Night Sound and Speech Enhancement, tone, the volume limit; read only (writes are refused, so go over UPnP) |
| `zones:1` | household (the seed) | bonded speakers that have dropped off, `activeZonesChange` |
| `hdmi:1` | **each soundbar's own socket** | whether anything is plugged into the HDMI port |
| `effectiveSettings:1` | player (the seed) | the UPnP switch: `getSettingsGroup {"groupName":"security"}`, followed by `settingsChanged`'s per-group timestamps |

`queue:1`, `playbackQueue:1` and `cloudQueue:1` all answer `ERROR_UNSUPPORTED_NAMESPACE`.

## UPnP actions used (port 1400)

| Service | Action | Used for |
|---|---|---|
| ContentDirectory | `Browse Q:0` | reading the queue, and its `UpdateID` before an edit |
| ContentDirectory | `Browse FV:2`, `CreateObject FV:2`, `DestroyObject` | Sonos favourites: the shortcuts and alarm programs the Control API omits, saving the current track, removing one |
| AVTransport | `RemoveTrackFromQueue`, `Seek TRACK_NR` | removing from and jumping in the queue |
| AVTransport | `ReorderTracksInQueue`, `RemoveAllTracksFromQueue`, `SaveQueue` | moving a track, clearing, saving as a playlist |
| AVTransport | `AddURIToQueue`, `GetPositionInfo` | adding to the queue (at the end, or next after the slot playing); the slot for Previous and for saving a favourite |
| AVTransport | `GetMediaInfo`, `SetAVTransportURI x-rincon-queue:<coordinator>#0` | whether the queue is the source, and making it so before a jump — `Seek` answers 701 otherwise |
| AVTransport | `SetAVTransportURI x-sonos-htastream:<soundbar>:spdif` | the TV input, sent to the coordinator |
| AVTransport | `SetAVTransportURI` (a service's radio URI) | a service's station, before falling back to `getMediaURI` |
| AVTransport | `ConfigureSleepTimer` (`GetRemainingSleepTimerDuration` for the live suite) | setting the room's own sleep timer; its state arrives on `sleepTimer:1` |
| AVTransport | `GetRunningAlarmProperties`, `SnoozeAlarm` | a ringing alarm's Snooze |
| RenderingControl | `SetEQ NightMode`, `SetEQ DialogLevel` | the two home-theatre toggles, sent to the soundbar |
| RenderingControl | `GetEQ`, `SetEQ` (Sub and surround types) | Sub and surrounds: on/off and level |
| RenderingControl | `SetBass`, `SetTreble`, `SetLoudness` (`Channel=Master`), `Get/SetRoomCalibrationStatus` | tone and TruePlay, sent to the speaker itself |
| DeviceProperties | `Get/SetLEDState`, `Get/SetButtonLockState` | Status light, Touch controls |
| HTControl | `Get/SetLEDFeedbackState`, `Get/SetIRRepeaterState` | a soundbar's Remote feedback light and IR repeater |
| AlarmClock | `ListAlarms`, `CreateAlarm`, `UpdateAlarm`, `DestroyAlarm`, `GetTimeNow`, `GetTimeZone` | the household's alarms and the clock they run on |
| ZoneGroupTopology | `CheckForUpdate` (`CachedOnly=1`) | About this system: whether an update waits; never applied |
| MusicServices | `ListAvailableServices` | the household's services: the list search, browse and Music Services start from, which service a track's id names (ratings, saving a favourite) |

Plain GETs on the same port: `/status/zp` (the long household id), `/xml/device_description.xml`
(a speaker's model and software), `/status/batterystatus` (a portable's battery).

Every UPnP call is addressed by `.local` name, because cleartext is permitted for those names
only. A 403 means the household has UPnP switched off in the Sonos app.

---

## Dependency Injection

Dagger Hilt in `:app` only. `AppModule` provides:
- an application-scoped `CoroutineScope` — the sockets outlive any one screen
- a `PlayerAddressBook`, and the single `OkHttpClient` allowed to reach players
- an `@InternetHttp` `OkHttpClient` for everything that is not a player: Sonos's CDN, music
  services, and `RadioDirectory` and `ITunesSearch`, which it also provides
- the `SeedStore` (`PrefsSeedStore`, keyed by `NetworkIdentity`) and the `MonotonicClock`
- `SonosHousehold`, as a singleton, with the T-27 bearer and T-28 page source where the build has them

`StoreModule` binds the seams that keep the view models constructible without the framework:
`Preferences`, `ChannelSync`, `MulticastGate` (`WifiMulticastGate`), `MdnsDiscovery`
(`NsdMdnsDiscovery`), `NowPlayingPublisher` and `RatingsStore`.

**The image loader must use the LAN client.** Album art is served by the players at cleartext
`.local` URLs, so a loader with its own client has neither the address book to resolve the
name nor permission to fetch it.

All ViewModels are `@HiltViewModel` and injected automatically.

**Commands fail out loud.** They are fire-and-forget, because their effect returns as an event,
but a failure is reported through `TransientNotice`: one line for a few seconds, worded by
`failureNotice`. The home screen draws its line above the room panel, which stays open while
grouping. An answer that lands once the viewer has left the screen that asked — Home's, a
preset's — goes to `SharedNotice`, the player's line, which the pane and every other screen draw
when they have nothing of their own to say. A cancellation is never reported: a newer press
cancels a debounced volume send, and `runCatching` catches that too.

---

## Tests

Two suites; `CLAUDE.md` explains why both exist and how to run the live one.

- **`:core`** against `FakePlayer`, an in-process TLS WebSocket server replaying captured
  payloads from `core/src/testFixtures/resources/fixtures/`. Ratings add two plain
  `MockWebServer`s for UPnP and the service. `LiveHouseholdTest` runs against real speakers
  when `-Dx2rock.live` is set and is skipped otherwise.
- **`:app`** view models against the same `FakePlayer`, through the seams above.
- Both build their household with `fakeHousehold` (`TestHousehold.kt` in `:core`'s test
  fixtures): no multicast, no real SSDP, mDNS or account capture. Name only what a test changes.
- **On the device**, `app/src/androidTest`: `FocusChoreographyTest` drives the home screen's focus
  paths with Compose UI tests over the same in-process `FakePlayer`, view models built by hand;
  `AlarmPaneTest`, `BrowseFocusReturnTest`, `SearchFieldFocusTest` and `DpadMenuKeyTest` pin four
  punch-list fixes.
  `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest`, on the emulator only:
  the task installs and uninstalls on every attached device, so `adb disconnect` the TVs first.

---

## TV / Leanback Considerations

- Landscape-only (`screenOrientation="landscape"`)
- `FocusRequester` used throughout for D-pad navigation; modals pin focus with `Overlay`
- `androidx.tv.material3` components (TV-aware Compose)
- Large touch targets, high-contrast dark themes
- All screens handle `BackHandler` for the remote's back button
- A disabled `tv-material3` button still takes focus and draws no highlight, so rows are
  hidden rather than disabled where a source does not permit them. The volume buttons are never
  disabled: a step on a muted room unmutes it

---

## Build

- Language: Kotlin only
- Min SDK 26, compile SDK 37, target SDK 35 (`app/build.gradle.kts` is the source of truth)
- Build system: Gradle with Kotlin DSL; modules `:core` (Kotlin/JVM) and `:app` (Android)
- Unit tests: `./gradlew :core:test :app:testDebugUnitTest`
- Build inputs that are not in the repository, each opt-in, none needed for a complete build:
  - `-Px2rock.upload` signs with the release key named in `keystore.properties` (the testers'
    APK and Play's bundle); without it, release signs with the local debug key.
  - `~/x2rock-sonos.speaker.salt` (or `-Px2rock.speakerSaltFile`): the salt that reads the
    household's stored sign-ins. Any build on a machine with the file carries it; without it
    services needing an account drop out of Music Services and Search, and say so.
  - `-Px2rock.sonosIntegration`: the owner's own Sonos integration (T-27), from
    `~/x2rocktv-sonos.api.*`. `-Px2rock.cloudContent`: the untracked `core/src/cloud` page
    source (T-28). Both are for the owner's TVs only, and a bundle refuses either.
- Key dependencies: Compose for TV, Dagger Hilt, OkHttp, Gson, Coil, Jetpack Navigation
