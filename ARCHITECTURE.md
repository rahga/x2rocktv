# x2rock — Architecture & Codebase Overview

A Sonos controller for Google TV / Android TV, written in Kotlin with Jetpack Compose.

---

## What it does

x2rock lets you control Sonos speakers from a TV remote. You authenticate with your Sonos account via OAuth, then see all your rooms in a sidebar. Selecting a room shows the now-playing track, playback controls, queue, and favorites. Rooms can be grouped/ungrouped, volume adjusted per-player, and a sleep timer set. There are five dark color themes.

---

## Modules

Three Gradle modules. `:core` is a plain Kotlin/JVM library with no Android dependency — it is
everything that talks to Sonos. `:app` is the Android TV application. `:cli` is the Linux
command line; it depends on `:core` only.

`:core` carries `javax.inject` annotations (`@Inject`, `@Singleton`, `@Qualifier`) so Hilt in
`:app` can construct its classes directly; a non-Hilt frontend just calls the constructors.

## File Map

```
core/src/main/kotlin/com/rahga/x2rock/          (pure JVM)
├── model/
│   ├── SonosModels.kt             All data classes (API requests/responses, UI state)
│   └── AppColorTheme.kt           Enum: DEFAULT, OCEAN, EMBER, FOREST, ORCHID
│
├── network/
│   ├── SonosApiService.kt         Retrofit interface — 20 suspend endpoints
│   ├── AuthInterceptor.kt         OkHttp interceptor: injects Bearer token
│   └── RateLimitedException.kt    Surfaces 429 + Retry-After to the pollers
│
├── repository/
│   ├── SonosRepository.kt         Wraps all Sonos Control API calls; handles 401 retry
│   └── SonosAuthRepository.kt     OAuth 2.0 flow; token exchange, refresh, storage
│
├── auth/
│   ├── TokenStore.kt              Interface: where tokens persist (platform supplies the impl)
│   └── SonosClientConfig.kt       OAuth client id + secret (platform supplies the values)
│
├── viewmodel/
│   └── Polling.kt                 pollLoop() with exponential backoff and Retry-After
│
└── di/
    └── TokenClient.kt             Qualifier for the token-exchange OkHttp client

cli/src/main/kotlin/com/rahga/x2rock/cli/       (Linux, Clikt + Mordant)
├── Main.kt                        Commands: login, rooms, now, play/pause/toggle, vol, queue, favorites…
├── Daemon.kt                      `x2rock daemon`: polls one room, publishes it over MPRIS
├── mpris/
│   ├── PlayerSnapshot.kt          Pure Sonos→MPRIS mapping: status, metadata, volume, position, diffs
│   ├── MprisInterfaces.kt         org.mpris.MediaPlayer2 + .Player as dbus-java interfaces
│   ├── MprisPlayer.kt             The exported D-Bus object; Properties Get/Set/GetAll, PropertiesChanged
│   └── PlayerControls.kt          What MPRIS clients can ask for; the daemon binds it to SonosRepository
├── Sonos.kt                       Hand-built object graph (what Hilt does in :app) + room-name matching
├── FileTokenStore.kt              TokenStore impl: 0600 JSON in $XDG_CONFIG_HOME/x2rock
├── CliConfig.kt                   Client credentials + default room; env vars override the file
├── OAuthCallback.kt               Browser → `x2rock oauth-callback URL` → file in $XDG_RUNTIME_DIR → `x2rock login`
├── Xdg.kt                         XDG base dirs, private-file writes
└── Format.kt                      Clock, one-line track, "+5"/"40" volume parsing

app/src/main/java/com/rahga/x2rock/             (Android TV)
├── X2RockApp.kt                   Hilt application entry point
├── MainActivity.kt                Single activity; sets Compose content + theme
│
├── auth/
│   ├── EncryptedTokenStore.kt     TokenStore impl: EncryptedSharedPreferences + keystore
│   ├── ThemeStore.kt              StateFlow-backed theme preference
│   └── RoomPreferencesStore.kt    StateFlow-backed favorites + primary room
│
├── di/
│   ├── AppModule.kt               Hilt @Provides: OkHttp, Retrofit, SonosClientConfig from BuildConfig
│   └── StoreModule.kt             Hilt @Binds: TokenStore → EncryptedTokenStore
│
├── ui/
│   ├── NavGraph.kt                Navigation graph — 5 routes
│   ├── theme/Theme.kt             5 Material 3 dark color schemes; AppButton component
│   └── screens/
│       ├── LoginScreen.kt         OAuth entry point (Idle → Loading → Authenticated)
│       ├── SonosAuthWebViewScreen.kt  WebView for Sonos OAuth; intercepts callback
│       ├── HomeScreen.kt          Main UI: room sidebar + player detail pane
│       ├── PlayerScreen.kt        Playback controls (embedded in HomeScreen)
│       ├── QueueScreen.kt         Track queue — tap to jump, long-press to delete
│       └── FavoritesScreen.kt     Saved favorites — tap to load
│
└── viewmodel/
    ├── LoginViewModel.kt
    ├── HomeViewModel.kt
    ├── PlayerViewModel.kt
    ├── QueueViewModel.kt
    └── FavoritesViewModel.kt
```

---

## Screens & Navigation

```
login  ──────────────────────────────────────────────── start (if no tokens)
  └─→ auth-webview/{authUrl}
        └─→ (deep link: x2rock://callback?code=…&state=…)
              └─→ login (exchanges code) → home

home  ───────────────────────────────────────────────── start (if authenticated)
  ├─→ queue?groupId={id}
  └─→ favorites?groupId={id}
```

**HomeScreen** is a split-pane layout:
- Left: 320dp `RoomSidebar` — room list, party mode button, settings slide-in (theme, sign out)
- Right: `PlayerScreen` — album art, track info, progress bar, playback buttons, volume

**PlayerScreen controls** are three rows:
1. Prev / −30s / Play-Pause / +30s / Next
2. Shuffle / Repeat / Crossfade / Queue / Favorites / Sleep Timer
3. Volume− / Volume display / Volume+ / Mute; per-player rows when rooms are grouped

---

## Data Flow

```
Compose UI
    │  collectAsState()
    ▼
ViewModel  (StateFlow<UiState>)
    │  viewModelScope.launch { withContext(Dispatchers.IO) }
    ▼
Repository  (singleton, Result<T>)
    │  Retrofit suspend call
    ▼
SonosApiService  →  https://api.ws.sonos.com/control/api/v1/
```

State in each ViewModel is a sealed interface: `Loading | Success(...) | Error`.

Polling is simple `while(true) { fetch(); delay(5_000) }` loops scoped to `viewModelScope`. When the user navigates away or selects a different group the old job is cancelled.

---

## ViewModels

### LoginViewModel
Manages the OAuth dance. `buildAuthUrl()` generates the Sonos URL; `handleCallback(code, state)` calls the auth repository to exchange the code for tokens.

### HomeViewModel
Polls the groups list every 5 seconds. Tracks `selectedGroupId`, `sidebarVisible`, `primaryRoomId`, and `favoriteRoomIds`. Handles party mode, room grouping/ungrouping, and theme selection. Broadcasts the selected group ID to PlayerViewModel via a shared `MutableStateFlow` in the repository.

### PlayerViewModel
Polls playback state, metadata, volume, and play mode every 5 seconds for the current group. Manages:
- Optimistic seek: updates the local position immediately, then calls the API
- Volume debouncing: 300ms debounce on group and per-player volume adjustments
- Sleep timer: a local countdown coroutine that calls `togglePlayPause` when it expires

### QueueViewModel
Loads once on entry. `playItem(trackNumber)` seeks to that position. `removeItem(id)` deletes and reloads.

### FavoritesViewModel
Loads once on entry. Determines the active favorite by matching the current container name against the favorites list.

---

## Repository Layer

### SonosRepository
Singleton. Caches `householdId`, `players`, and `groups` so the household lookup only happens once per session.

All methods return `Result<T>`. Internally, every call goes through `fetchWithRefresh()`:
1. Calls `authRepository.ensureValidToken()` (refreshes if expiring in <60s)
2. Makes the HTTP request
3. If 401 → calls `refreshAccessToken()` and retries once

### SonosAuthRepository
OAuth 2.0 with Basic auth (client credentials). Tokens go through the `TokenStore` interface — on Android that is `EncryptedTokenStore` (AES256-GCM). A `Mutex` prevents concurrent refresh calls. Client credentials arrive via `SonosClientConfig` rather than `BuildConfig`, so the repository has no build-system coupling.

**OAuth redirect URI:** `https://rahga.github.io/x2rock/callback.html`  
The hosted page redirects to the deep link `x2rock://callback`. On Android the WebView intercepts
it. On Linux, `x2rock install-handler` registers an `x-scheme-handler/x2rock` desktop entry, so the
browser launches `x2rock oauth-callback <url>`, which drops the URL in `$XDG_RUNTIME_DIR/x2rock/`
for the waiting `x2rock login` process. Same registered redirect URI for both platforms — nothing
to add in the Sonos integration manager.

---

## Key Sonos API Endpoints Used

| Method | Path | What it does |
|--------|------|--------------|
| GET | `/households` | List households |
| GET | `/households/{id}/groups` | List groups + players |
| GET | `/groups/{id}/playback` | Current play state + position |
| GET | `/groups/{id}/playbackMetadata` | Track name, artist, album art |
| POST | `/groups/{id}/playback/togglePlayPause` | Play / pause |
| POST | `/groups/{id}/playback/skipToNextTrack` | Next |
| POST | `/groups/{id}/playback/skipToPreviousTrack` | Previous |
| POST | `/groups/{id}/playback/seek` | Seek to position or track |
| GET/POST | `/groups/{id}/groupVolume` | Get / set group volume |
| POST | `/groups/{id}/groupVolume/mute` | Mute group |
| GET/POST | `/groups/{id}/playMode` | Get / set shuffle, repeat, crossfade |
| GET | `/groups/{id}/queue` | Track queue (up to 500) |
| POST | `/groups/{id}/queue/delete` | Delete queue items |
| GET | `/households/{id}/favorites` | List favorites |
| POST | `/groups/{id}/favorites/loadFavorite` | Load a favorite |
| GET/POST | `/players/{id}/playerVolume` | Per-player volume |
| POST | `/households/{id}/groups/{id}/modifyGroupMembers` | Group/ungroup players |

---

## MPRIS daemon (Linux)

`x2rock daemon` owns a poll loop for one room and mirrors it onto the session bus as
`org.mpris.MediaPlayer2.x2rock`. `PlayerSnapshot` is the pure mapping layer — Sonos state to MPRIS
`PlaybackStatus`/`Metadata`/`Volume`/`LoopStatus`, plus a diff so only changed properties are
signalled. `Position` is not signalled (per spec) but extrapolated from the last poll while playing,
so progress bars move between polls. An MPRIS command runs the Sonos call and then nudges the loop
to re-poll immediately, so the bus reflects the result within a round trip. Polling failures back off
with the same `nextBackoffMillis` curve as the TV app; a failed poll also re-resolves the room by
name, because group ids change whenever rooms are grouped or ungrouped.

`MprisBusTest` exports a real `MprisPlayer` onto the session bus and drives it with `busctl`; it
skips when no bus is available.

## Persistence

| Store | Mechanism | What's stored |
|-------|-----------|---------------|
| `EncryptedTokenStore` | EncryptedSharedPreferences (AES256-GCM) | Access token, refresh token, expiry, pending OAuth state |
| `ThemeStore` | Plain SharedPreferences + StateFlow | Selected theme enum |
| `RoomPreferencesStore` | Plain SharedPreferences + StateFlow | Primary room ID, Set of favorite room IDs |

No local database — all content state is remote.

---

## Dependency Injection

Dagger Hilt in `:app` only. `AppModule` provides:
- `SonosClientConfig` from `BuildConfig` (which reads `local.properties`)
- Two `OkHttpClient` instances: one plain (for token exchange), one with `AuthInterceptor` + logging
- `Retrofit` instance backed by the authenticated client, using `GsonConverterFactory`

`StoreModule` binds `TokenStore` to `EncryptedTokenStore`. The `:core` repositories and
`AuthInterceptor` have `@Inject` constructors and are picked up without explicit bindings.

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
- Min SDK: 21 / Target SDK: 34
- Build system: Gradle with Kotlin DSL (`build.gradle.kts`); modules `:core` (Kotlin/JVM), `:cli` (Kotlin/JVM) and `:app` (Android)
- Unit tests: `./gradlew :core:test :cli:test :app:testDebugUnitTest`
- CLI binary: `./gradlew :cli:installDist` → `cli/build/install/x2rock/bin/x2rock`
- Key dependencies: Jetpack Compose TV, Dagger Hilt, Retrofit 2, OkHttp, Gson, Jetpack Navigation, AndroidX Security Crypto
