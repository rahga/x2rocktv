# x2rock — Architecture & Codebase Overview

A Sonos controller for Google TV / Android TV, written in Kotlin with Jetpack Compose for TV.

> **The app talks to the speakers over the LAN, with no Sonos account.** The cloud Control
> API and its OAuth flow were removed in 2026-09: the same API is served by the players
> themselves, it pushes instead of polling, and Sonos's consent page cannot be completed
> with a TV remote anyway. The protocol, and the evidence behind every claim about it, is
> in [`docs/lan-transport.md`](docs/lan-transport.md) — read that before touching `:core`.
> What is still to do is in [`docs/punch-list.md`](docs/punch-list.md).

---

## What it does

x2rock controls Sonos speakers from a TV remote. There is no sign-in: a player is found on
the local network by SSDP (or remembered from last time) and answers without an account.

Rooms are listed in a sidebar, alphabetically, as Sonos lists them. Selecting a room shows
what it is playing with the controls its source permits; a room on its soundbar's TV input
gets its own pane with Night Sound and Speech Enhancement instead. Clicking a room opens its
room panel: party mode, the rooms playing together and their levels, the rooms that could
join, and — for a room with a soundbar — the TV input and "This is my TV". The queue and the
household's favourites each have a screen. A track on a service that publishes ratings can be
rated up or down. There are five dark colour themes.

The app opens on the television's own room: the soundbar the device is plugged into, found by
`TvSoundbar` or named by the viewer. Each room is also a tile on the TV home screen's channel,
and the selected room is the system's media session.

---

## Modules

Two Gradle modules. `:core` is a plain Kotlin/JVM library with no Android dependency — it is
everything that talks to Sonos. `:app` is the Android TV application and depends on `:core`.

A third module `:cli` (a Linux command line plus an MPRIS daemon) was removed in 2026-09;
the Rust project `rahga/x2rock` covers that ground on the desktop.

`:core` carries `javax.inject` annotations (`@Inject`, `@Singleton`, `@Qualifier`) so Hilt in
`:app` can construct its classes directly; a non-Hilt frontend just calls the constructors.
`:core` also publishes `FakePlayer` and the captured fixtures as **test fixtures**, so `:app`'s
view models are tested against the same fake household over a real socket.

## File Map

```
core/src/main/kotlin/com/rahga/x2rock/          (pure JVM — no Android, so it is
│                                                testable against real speakers)
├── model/
│   ├── SonosModels.kt             Control API shapes, shared by every layer
│   └── AppColorTheme.kt           Enum: DEFAULT, OCEAN, EMBER, FOREST, ORCHID
│
├── lan/
│   ├── Frame.kt                   [header, body] wire format; reply vs event
│   ├── PlayerNames.kt             RINCON id → sonos-<MAC>.local
│   ├── LanHttp.kt                 the player-only OkHttp client + address book
│   ├── SonosSocket.kt             one socket to one player
│   ├── Discovery.kt               SSDP: id, address and household in one reply
│   ├── MulticastGate.kt           whatever the platform must hold for multicast
│   ├── SeedStore.kt               the last reachable player, for a warm start
│   ├── SonosHousehold.kt          state flows, commands, subscriptions, reconnection
│   ├── Upnp.kt                    cleartext 1400: queue, TV input, EQ, service list
│   ├── TvSoundbar.kt              which soundbar this television is plugged into
│   └── PlayModes.kt               repeat flags ↔ the app's enum
│
└── smapi/                         the one path that leaves the LAN
    ├── Smapi.kt                   presentation map, getExtendedMetadata, rateItem
    ├── RatingsCatalogue.kt        a service's rating rules, fetched on a miss
    └── RatingsStore.kt            where those rules are remembered (seam)

app/src/main/java/com/rahga/x2rock/             (Android TV)
├── X2RockApp.kt                   Hilt entry point; image loader on the LAN client
├── MainActivity.kt                Single activity; deep links; Compose content + theme
│
├── auth/                          (named for history; nothing here authenticates)
│   ├── ThemeStore.kt              StateFlow-backed theme preference
│   ├── RoomPreferencesStore.kt    the television's soundbar, as a player id
│   └── PendingRoomDeepLink.kt     a room asked for by a channel tile, until it exists
│
├── store/Preferences.kt           the key-value seam every store sits on
├── net/
│   ├── NetworkMonitor.kt          ConnectivityManager → household.onNetworkChanged()
│   ├── PrefsSeedStore.kt          SeedStore over shared preferences
│   └── WifiMulticastGate.kt       a MulticastLock held across SSDP
├── smapi/PrefsRatingsStore.kt     RatingsStore over Preferences
├── media/NowPlayingPublisher.kt   the MediaSession, behind an interface
├── channel/
│   ├── RoomsChannelSync.kt        rooms as tiles on the TV home screen channel
│   └── ChannelSyncReceiver.kt     INITIALIZE_PROGRAMS: connect, sync, within budget
│
├── di/
│   ├── AppModule.kt               app scope, address book, LAN and internet clients, household
│   └── StoreModule.kt             binds the seams: Preferences, ChannelSync, publisher, ratings
│
├── ui/
│   ├── NavGraph.kt                three routes
│   ├── theme/Theme.kt             5 Material 3 dark colour schemes; AppButton
│   ├── components/
│   │   ├── DpadLongPress.kt       hold-select or Menu key, for a remote with no long press
│   │   └── Overlay.kt             pins focus inside a modal
│   └── screens/
│       ├── HomeScreen.kt          room sidebar + detail pane; settings slide-in
│       ├── PlayerScreen.kt        the music pane and the TV pane
│       ├── RoomPanel.kt           everything one room can be told to do
│       ├── QueueScreen.kt         the queue — click to jump, long-press to remove
│       ├── FavoritesScreen.kt     the household's favourites — click to load
│       └── NowPlayingBar.kt       a compact strip on the queue and favourites screens
│
└── viewmodel/
    ├── HomeViewModel.kt
    ├── PlayerViewModel.kt
    ├── QueueViewModel.kt
    └── FavoritesViewModel.kt
```

---

## Screens & Navigation

```
home  ───────────────────────────────────────────────── start, always
  ├─→ queue?groupId={id}
  └─→ favorites?groupId={id}
```

There is no login route. `x2rock://room/{groupId}` deep-links from the TV home-screen channel
tiles, and `x2rock://home` opens the app.

**HomeScreen** is a split pane:
- Left: `RoomSidebar` — the settings button, then the rooms. Each row carries a 48dp art slot
  (cover, station logo, or a TV or radio glyph), the room name, two lines of what is playing
  (three for a soundbar on its input), and a dim TV badge on a room that has an HDMI input but
  is not on it. Selection follows focus. Click, hold-select or the Menu key opens `RoomPanel`.
- Right: `PlayerScreen` for the selected room.

**PlayerScreen** draws one of two panes:
- **Music:** art, track and artist (or a stream's own text and its station), a progress bar
  where there is a duration, then three rows — transport (only what `availablePlaybackActions`
  permits, plus the rating thumbs where a press can succeed), modes (shuffle, repeat,
  crossfade, each gated the same way; queue, favourites, sleep timer), and volume, with a row
  per speaker when the room is grouped. A room with nothing loaded says so and offers
  Favorites.
- **TV:** a television glyph with the input format, Night Sound and Speech Enhancement, the
  volume row, and a way back into music through Favorites.

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

Four things are asked for rather than pushed, each because nothing pushes it:
- **The queue**, over UPnP — the Control API has none. Stale until re-read.
- **Night Sound and Speech Enhancement**, from `settings:1 getPlayerSettings`: `homeTheater:1`
  accepts a subscription and never fires. Read once per room and after each write.
- **A track's rating**, from the service. Read once per room and track and after each press.
- **Favourites**, read when their screen opens.

---

## ViewModels

### HomeViewModel
Derives the room list from the household's flows, sorted alphabetically and nothing else, and
connects on first display. Tracks the selected group, sidebar visibility and the television's
soundbar. Follows the *speakers* rather than the group id when a regroup mints new ids. Moves
the selection to the television's room once it is known, but only while the selection is still
the app's own default choice. Handles party mode, joining, leaving, TV input, "This is my TV",
the panel's level adjustments and the theme.

### PlayerViewModel
Derives everything visible for the selected group from pushed state, and feeds
`NowPlayingPublisher` so media keys and the TV's transport controls work. It never takes the
volume keys. Manages:
- Volume and seek debouncing: 300ms, accumulating, so a held key sends one command by the
  total rather than one per repeat
- The home-theatre reading and its optimistic toggles
- The rating state
- A notice line: a rating's result, or why a command failed
- A sleep timer, as a local countdown that pauses the group when it expires

The selected room reaches it from `HomeScreen`, which calls `selectGroup` as focus moves.

### QueueViewModel
Loads once on entry. `playItem(trackNumber)` seeks to that position. `removeItem` deletes and
reloads.

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
| `SeedStore.kt` | one remembered player, probed first so a warm start skips discovery |
| `SonosHousehold.kt` | the live view: connections, subscriptions, state flows, commands, reconnection, ratings |
| `Upnp.kt` | cleartext port 1400 — the queue, the TV input switch, `SetEQ`, and the music-service list |
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

### Leaving the LAN: ratings (`:core`, package `smapi`)

The only traffic that leaves the household, and it goes over its own internet client — never
the LAN one, whose trust manager accepts the players' leaf-only chains. A rating starts on the
LAN with `ListAvailableServices`, then fetches the service's manifest and presentation map from
Sonos's CDN (cached per service by `RatingsCatalogue`, including "publishes none"), then calls
`getExtendedMetadata` and `rateItem` on the service itself. Only anonymous services are rated:
there is no account linking on a TV. `SonosHousehold.ratingState` answers null wherever `rate`
would refuse, so the buttons are drawn exactly when a press can succeed.

### Authentication

None, and that is the point. No account, no OAuth, no tokens, no stored secrets.

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
| `settings:1` | player | `getPlayerSettings`: Night Sound and Speech Enhancement, read only |
| `effectiveSettings:1` | player (the seed) | the UPnP switch: `getSettingsGroup {"groupName":"security"}`, followed by `settingsChanged`'s per-group timestamps |

`queue:1`, `playbackQueue:1` and `cloudQueue:1` all answer `ERROR_UNSUPPORTED_NAMESPACE`.

## UPnP actions used (port 1400)

| Service | Action | Used for |
|---|---|---|
| ContentDirectory | `Browse Q:0` | reading the queue, and its `UpdateID` before an edit |
| AVTransport | `RemoveTrackFromQueue`, `Seek TRACK_NR` | removing from and jumping in the queue |
| AVTransport | `GetMediaInfo`, `SetAVTransportURI x-rincon-queue:<coordinator>#0` | whether the queue is the source, and making it so before a jump — `Seek` answers 701 otherwise |
| AVTransport | `SetAVTransportURI x-sonos-htastream:<soundbar>:spdif` | the TV input, sent to the coordinator |
| RenderingControl | `SetEQ NightMode`, `SetEQ DialogLevel` | the two home-theatre toggles, sent to the soundbar |
| MusicServices | `ListAvailableServices` | which service a track's id names, for ratings |

Every UPnP call is addressed by `.local` name, because cleartext is permitted for those names
only. A 403 means the household has UPnP switched off in the Sonos app.

---

## Dependency Injection

Dagger Hilt in `:app` only. `AppModule` provides:
- an application-scoped `CoroutineScope` — the sockets outlive any one screen
- a `PlayerAddressBook`, and the single `OkHttpClient` allowed to reach players
- an `@InternetHttp` `OkHttpClient` for the CDN and music services, and nothing else
- `SonosHousehold`, as a singleton

`StoreModule` binds the seams that keep the view models constructible without the framework:
`Preferences`, `ChannelSync`, `NowPlayingPublisher` and `RatingsStore`.

**The image loader must use the LAN client.** Album art is served by the players at cleartext
`.local` URLs, so a loader with its own client has neither the address book to resolve the
name nor permission to fetch it.

All ViewModels are `@HiltViewModel` and injected automatically.

**Commands fail out loud.** They are fire-and-forget, because their effect returns as an event,
but a failure is reported through `TransientNotice`: one line for a few seconds, worded by
`failureNotice`. The home screen draws its line above the room panel, which stays open while
grouping. A cancellation is never reported: a newer press cancels a debounced volume send,
and `runCatching` catches that too.

---

## Tests

Two suites; `CLAUDE.md` explains why both exist and how to run the live one.

- **`:core`** against `FakePlayer`, an in-process TLS WebSocket server replaying captured
  payloads from `core/src/testFixtures/resources/fixtures/`. Ratings add two plain
  `MockWebServer`s for UPnP and the service. `LiveHouseholdTest` runs against real speakers
  when `-Dx2rock.live` is set and is skipped otherwise.
- **`:app`** view models against the same `FakePlayer`, through the seams above.

---

## TV / Leanback Considerations

- Landscape-only (`screenOrientation="landscape"`)
- `FocusRequester` used throughout for D-pad navigation; modals pin focus with `Overlay`
- `androidx.tv.material3` components (TV-aware Compose)
- Large touch targets, high-contrast dark themes
- All screens handle `BackHandler` for the remote's back button
- A disabled `tv-material3` button still takes focus and draws no highlight, so rows are
  hidden rather than disabled where a source does not permit them. The volume buttons, still
  disabled while muted, are the exception and are on the punch list

---

## Build

- Language: Kotlin only
- Min SDK 23; compile and target SDK 35 (`app/build.gradle.kts` is the source of truth)
- Build system: Gradle with Kotlin DSL; modules `:core` (Kotlin/JVM) and `:app` (Android)
- Unit tests: `./gradlew :core:test :app:testDebugUnitTest`
- Key dependencies: Compose for TV, Dagger Hilt, OkHttp, Gson, Coil, Jetpack Navigation
