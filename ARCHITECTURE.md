# x2rock — Architecture & Codebase Overview

A Sonos controller for Google TV / Android TV, written in Kotlin with Jetpack Compose.

> **The app talks to the speakers over the LAN, with no Sonos account.** The cloud Control
> API and its OAuth flow were removed in 2026-09: the same API is served by the players
> themselves, it pushes instead of polling, and Sonos's consent page cannot be completed
> with a TV remote anyway. The protocol, and the evidence behind every claim about it, is
> in [`docs/lan-transport.md`](docs/lan-transport.md) — read that before touching `:core`.

---

## What it does

x2rock lets you control Sonos speakers from a TV remote. There is no sign-in: the speakers are found on the local network and answer without an account. Rooms appear in a sidebar. Selecting a room shows the now-playing track, playback controls, queue, and favorites. Rooms can be grouped/ungrouped, volume adjusted per-player, and a sleep timer set. There are five dark color themes.

---

## Modules

Two Gradle modules. `:core` is a plain Kotlin/JVM library with no Android dependency — it is
everything that talks to Sonos. `:app` is the Android TV application and depends on `:core`.

A third module `:cli` (a Linux command line plus an MPRIS daemon) was removed in 2026-09;
the Rust project `rahga/x2rock` covers that ground on the desktop.

`:core` carries `javax.inject` annotations (`@Inject`, `@Singleton`, `@Qualifier`) so Hilt in
`:app` can construct its classes directly; a non-Hilt frontend just calls the constructors.

## File Map

```
core/src/main/kotlin/com/rahga/x2rock/          (pure JVM — no Android, so it is
│                                                testable against real speakers)
├── model/
│   ├── SonosModels.kt             Control API shapes, shared by every layer
│   └── AppColorTheme.kt           Enum: DEFAULT, OCEAN, EMBER, FOREST, ORCHID
│
└── lan/
    ├── Frame.kt                   [header, body] wire format; reply vs event
    ├── PlayerNames.kt             RINCON id → sonos-<MAC>.local
    ├── LanHttp.kt                 the player-only OkHttp client + address book
    ├── SonosSocket.kt             one socket to one player
    ├── Discovery.kt               SSDP: id, address and household in one reply
    ├── SonosHousehold.kt          state flows, commands, reconnection
    ├── Upnp.kt                    the queue, over cleartext 1400
    └── PlayModes.kt               repeat flags ↔ the app's enum

app/src/main/java/com/rahga/x2rock/             (Android TV)
├── X2RockApp.kt                   Hilt application entry point
├── MainActivity.kt                Single activity; sets Compose content + theme
│
├── auth/
│   ├── ThemeStore.kt              StateFlow-backed theme preference
│   └── RoomPreferencesStore.kt    StateFlow-backed favorites + primary room
│
├── net/
│   └── NetworkMonitor.kt          ConnectivityManager → household.onNetworkChanged()
│
├── di/
│   └── AppModule.kt               Hilt @Provides: app scope, address book, LAN client, household
│
├── ui/
│   ├── NavGraph.kt                Navigation graph — 5 routes
│   ├── theme/Theme.kt             5 Material 3 dark color schemes; AppButton component
│   └── screens/
│       ├── HomeScreen.kt          Main UI: room sidebar + player detail pane
│       ├── PlayerScreen.kt        Playback controls (embedded in HomeScreen)
│       ├── QueueScreen.kt         Track queue — tap to jump, long-press to delete
│       └── FavoritesScreen.kt     Saved favorites — tap to load
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

There is no login route. `x2rock://room/{groupId}` still deep-links from the TV
home-screen channel tiles.

**HomeScreen** is a split-pane layout:
- Left: 320dp `RoomSidebar` — room list, party mode button, settings slide-in (theme)
- Right: `PlayerScreen` — album art, track info, progress bar, playback buttons, volume

**PlayerScreen controls** are three rows:
1. Prev / −30s / Play-Pause / +30s / Next
2. Shuffle / Repeat / Crossfade / Queue / Favorites / Sleep Timer
3. Volume− / Volume display / Volume+ / Mute; per-player rows when rooms are grouped

---

## Data Flow

**Nothing polls.** The speakers push, and every link above them is already reactive:

```
speakers  ──push──▶  SonosSocket        wss://sonos-<MAC>.local:1443
                         │  events
                         ▼
                     SonosHousehold     StateFlow<HouseholdState>
                         │              StateFlow<Map<String, GroupState>>
                         ▼
                     ViewModel          combine(...) → StateFlow<UiState>
                         │  collectAsState()
                         ▼
                     Compose            recomposes only on real change
```

State in each ViewModel is derived rather than assembled by hand, so it recomputes when
something actually changes and at no other time. A command's effect arrives as an event
like any other, which is why nothing re-fetches after acting.

The **queue is the exception**: the Control API has no queue at all, so it is read over
UPnP on port 1400 and is stale until re-read. See `Upnp`.

---

## ViewModels

### HomeViewModel
Derives the room list from the household's flows, and connects on first display. Tracks `selectedGroupId`, `sidebarVisible`, `primaryRoomId`, and `favoriteRoomIds`. Handles party mode, room grouping/ungrouping, and theme selection. Broadcasts the selected group ID to PlayerViewModel via a shared `MutableStateFlow` in the repository.

### PlayerViewModel
Derives everything visible for the selected group from pushed state, and owns the
`MediaSession` so media keys and the TV's own transport controls work. Manages:
- Volume debouncing: 300ms, so a held D-pad key sends one command rather than one per repeat
- Sleep timer: a local countdown coroutine that pauses the group when it expires

### QueueViewModel
Loads once on entry. `playItem(trackNumber)` seeks to that position. `removeItem(id)` deletes and reloads.

### FavoritesViewModel
Loads once on entry. Determines the active favorite by matching the current container name against the favorites list.

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
| `Discovery.kt` | SSDP. The reply carries the player id *and* household id, which is why the first connection can be made to a verified name |
| `SonosHousehold.kt` | the live view: connections, subscriptions, state flows, commands, reconnection |
| `Upnp.kt` | the queue, over cleartext port 1400 — the one thing the Control API lacks |
| `PlayModes.kt` | Sonos's two repeat booleans ↔ the app's three-way enum |

### SonosHousehold

Holds `StateFlow<HouseholdState>` (groups, players) and `StateFlow<Map<String, GroupState>>`
(playback, metadata, volume, play mode per group), plus per-player volumes. One socket per
group coordinator, because group-scoped namespaces are answered by coordinators only;
player-scoped calls open that player's own socket. Both are pooled.

Reconnection is capped exponential backoff, 1s doubling to 60s. It rebuilds from scratch
rather than repairing in place: subscriptions do not survive a reconnect and there is no
replay buffer, so the fresh snapshot is truth. `onNetworkChanged()` skips the wait entirely,
because a resumed socket can accept writes and never report failure.

### Authentication

There is none, and that is the point. No account, no OAuth, no tokens, no stored secrets.

---

## Namespaces Used

Commands and events are `[header, body]` frames over the WebSocket, not REST paths.

| Namespace | Scope | Used for |
|---|---|---|
| `groups:1` | household | topology, `getGroups`, `modifyGroupMembers` |
| `playback:1` | group (coordinator) | transport, seek, play modes |
| `playbackMetadata:1` | group (coordinator) | track, album art, container |
| `groupVolume:1` | group (coordinator) | group volume and mute |
| `playerVolume:1` | **that player's own socket** | per-speaker volume |
| `favorites:1` | household / group | listing and loading favourites |

`queue:1`, `playbackQueue:1` and `cloudQueue:1` all answer `ERROR_UNSUPPORTED_NAMESPACE`;
the queue lives in `Upnp` instead.

---

## Dependency Injection

Dagger Hilt in `:app` only. `AppModule` provides:
- an application-scoped `CoroutineScope` — the sockets outlive any one screen
- a `PlayerAddressBook`, and the single `OkHttpClient` allowed to reach players
- `SonosHousehold`, as a singleton

**The image loader must use that same client.** Album art is served by the players at
cleartext `.local` URLs, so a loader with its own client has neither the address book to
resolve the name nor permission to fetch it.

All ViewModels are `@HiltViewModel` and injected automatically.

---

## TV / Leanback Considerations

- Landscape-only (`screenOrientation="landscape"`)
- `FocusRequester` used throughout for D-pad navigation
- `androidx.tv.material3` components (TV-aware Compose)
- Large touch targets, high-contrast dark themes
- All screens handle `BackHandler` for remote back button

---

## Build

- Language: Kotlin only
- Min SDK: 23 / Compile & Target SDK: 35
- Build system: Gradle with Kotlin DSL (`build.gradle.kts`); modules `:core` (Kotlin/JVM) and `:app` (Android)
- Unit tests: `./gradlew :core:test :app:testDebugUnitTest`
- Key dependencies: Jetpack Compose TV, Dagger Hilt, OkHttp, Gson, Jetpack Navigation
