# x2rocktv against the Sonos app

What each service looks like in both apps, what has been tested where, and what the Sonos app
does that this one does not — split into what is attainable and what is not. Read it before
proposing parity work: several gaps are walls, and the wall is recorded here with its evidence.

The record of *how* each service behaves over SMAPI is `../x2rock/docs/architecture.md`, "Music
services: what has actually been tested" and "Every household token, imported" (2026-10-11). This
file is the comparison with the app; that one is the protocol.

## Pass of 2026-10-11 (home household)

- **Sonos app** 90.00.43 (built 2026-10-06) on a moto g 5G 2024, Android 15, over USB adb.
- **x2rocktv** release build of `e2722d5` on the Shield, driven with key events.
- **x2rock CLI** for service-level answers (`browse`, `search`, `link --from-household --dry-run`).
- Every service's top level was opened in both apps and screenshotted, and "miles davis" was
  searched in both. **Nothing was played**: every room was in use (Bedroom and Dining Room playing,
  Living Room on its TV, Kitchen paused mid-track). Playback evidence below is from earlier passes.
- Screenshots and page texts are in `~/x2rocktv-sonos-compare-2026-10-11/` on the dev machine,
  not in the repo (they carry account names). `pair-<service>.png` is the Shield beside the phone.

The previous pass, 2026-10-08 against the office household, is in `punch-list.md`, "Against the
Sonos app, 2026-10-08 — Browse"; everything it found was built.

## Service by service

"Top level" is what opening the service shows. "Played" is the last time anything from that
service played on hardware, and through which — x2rocktv where it has a record, otherwise
the x2rock CLI, whose playback goes through the same player paths.

| service | top level, x2rocktv vs app | search ("miles davis") | played | notes |
|---|---|---|---|---|
| Amazon Music | **differs**: Library, User Playlists vs the app's Home — Library, Explore Podcasts, User Playlists, then 11 personal shelves (Followed Playlists, Songs for You, Your Frequent Plays, Podcasts You May Like, …) | both answer; x2rocktv gets tracks and albums, the app's section held only two playlists | x2rock, Kitchen, 2026-10-11 | Home is `api/home`, which refuses a bearer token (x2rock). Library's albums end in a sentinel row, id `unauthorized`, "Content Unavailable" — x2rocktv lists it as a container |
| Apple Music | **differs, by necessity**: search only (iTunes) vs the app's Library (Artists, Albums, Songs, Playlists, Recently Added) | both answer | x2rocktv, Dining Room, 2026-10-06 (catalogue ids) | The household's credential has an empty `privateKey`; SMAPI refuses every call. Not attainable |
| Audible | same: the marketplaces (`.com`, `.co.uk`, `.de`, …) | neither offers it | x2rock, 2026-10-06 (resume measured); x2rocktv's chapters only against a captured shape | x2rock guessed the app opens straight into the library; it does not |
| Bandcamp | same, empty: x2rocktv falls to its search, the app says "No content items available" | x2rocktv: "1 service didn't answer" (`Client.LoginInvalid`); the app leaves it out | — | The household's own token is refused, by the app too. Relink in the Sonos app; nothing to fix here |
| Deezer | **differs**: SMAPI root (Flow, Popular, Explore, Picked for you, My Music, …) vs the app's page — Favorites (Tracks, Playlists, Albums, Artists), Recently played, Flow moods, Made for you, Recommended playlists, Music by genre, Recommended releases, Music by mood, Charts | both answer | x2rocktv queued a track, home, 2026-10-07 | Page from the manifest `browse` endpoint; answers a bearer token (x2rock) |
| iHeartRadio | same: For You, My Stations, Nearby Stations, Live Radio, Artist Radio, Podcasts | both answer | x2rocktv, office, 2026-10-07 (custom stations) | Two household accounts: two rows here, an account switcher in the app's service header |
| Mixcloud | **x2rocktv only** | x2rocktv answers (mixes, and user names) | **✗ x2rocktv's play restarts the player** (below); through the queue ✓, Dining Room, 2026-10-11 | The household still stores a Mixcloud account (`sid 181`); the app lists it nowhere |
| Pandora | same: My Stations | both answer, every hit a station | x2rock, 2026-10-11 | A station is `canPlay`/not `canEnumerate`; x2rocktv already plays a `program` as a leaf |
| Qobuz | same: Playlists, Purchases, Favorites, Discover, Qobuz Playlists | both answer | x2rock, office, 2026-09-23 | |
| Radio Paradise | same: 128k, 320k, FLAC | no search categories in either | x2rock, 2026-09-30 (office) | |
| Saavn | same: Recently Played, New Releases, Charts & Playlists, Top Shows, Radio, Browse & Discover | both answer | x2rock, office, 2026-09-21 | |
| SiriusXM | same entries; the app draws them as shelves, x2rocktv as rows | both answer | x2rock, 2026-10-11 | The `browse` endpoint lists the same 9 ids SMAPI root does |
| Sonos Radio | same: the shelves (Trending Now, Discover Sonos Radio, Sonos Presents, …) | both answer | x2rocktv, office, 2026-10-08 (Hit List) | Built from the anonymous `browse` endpoint; Browse Radio since T-09 |
| SoundCloud | same: Likes, Playlists, Feed, New & Hot, Following, own uploads | both answer | x2rock, 2026-10-11 | |
| Spotify | **differs most**: 5 SMAPI shelves (Popular Playlists, Charts, New Releases, Genres and Moods, Your Music) vs the app's personalised page — More like <artist>, Recommended for today, Albums featuring songs you like, Recommended Stations, Album picks, Your top mixes, Based on your recent listening, … (22 views) | both answer | x2rock, 2026-10-11 | `browse` endpoint answers a bearer token; each view's id opens with SMAPI `getMetadata` (x2rock) |
| TIDAL | same: My Collection, My Mix, Suggested new albums, Recommended new tracks, Featured Playlists, Genres, … | both answer | x2rock, home, 2026-10-11 | |
| TuneIn (New) | **differs**: SMAPI root (iHeartRadio, Holiday Stations, TuneIn Recommends, Music, News & Talk, …) vs 13 shelves — Recents, Exclusive Music Stations, Most Popular, Top Calm Radio, Featured Collections, … | both answer | x2rock, 2026-10-11 | `browse` endpoint answered 403 (Cloudflare 1010) to a Python client; untried from OkHttp |
| YouTube Music | **app only** (Home, Library, My Supermix) | the app answers; x2rocktv leaves it out | x2rocktv, favourites only | Google's project gate: HTTP 403 `SERVICE_DISABLED` on every SMAPI call, the household's own token included. Not attainable. Saved favourites still play |
| More radio (Hype Machine, myTuner, NRK, Radio France, …) | x2rocktv only | x2rocktv only | — | Anonymous services the app does not show as "yours" |

## Plays, Dining Room, 2026-10-11 (afternoon)

One item per service, and an album where the service has them, started through the same
`SonosHousehold` calls the app's buttons use (`startServiceItem`, `playAppleMusic`, `loadFavorite`),
from a scratch JVM test at volume 1; the room was put back afterwards. Times are from the call to
the room reporting PLAYING with the new content.

| service | what | result |
|---|---|---|
| Amazon Music | station "Miles Davis"; album Kind Of Blue | ✓ 8.3s; ✓ 3.9s |
| Apple Music | song and album, from iTunes | ✓ 4.2s each |
| Audible | "The Body" | ✓ 5.1s — **the resume path's first run on hardware**: it started chapter 14, where the account's place was. Whether it also sought into the chapter was not checked |
| Bandcamp | — | nothing to play: the household's token is refused |
| Deezer | track; album | ✓ 3.3s; ✓ 2.3s. Titles carry a literal `&amp;` (P-22) |
| iHeartRadio, both accounts | live station | ✓ 1.4s, 1.5s. Its first hit for "miles davis" is a live station, not the artist station |
| **Mixcloud** | any mix | **✗ the player hangs and restarts** (P-21). `loadContent` leaves it BUFFERING, it stops answering within 5s and is back ~2.5 min later. Reproduced three times, twice with nothing else sent. The same mix through the queue plays |
| Pandora | search station | ✓ 2.4s; playing a search hit adds it to My Stations, as the app does |
| Qobuz | track; album | ✓ 2.9s; ✓ 2.2s |
| Radio Paradise | The Main Mix | ✓ 3.9s |
| Saavn | track | ✓ 4.5s (it stalled once, in a run that ended in a restart; alone, it played and the player stayed up) |
| SiriusXM | artist station | ✓ 2.9s |
| Sonos Radio | station | ✓ 1.5s |
| SoundCloud | track; album | ✓ 4.8s; **✗ the album "loaded but did not start"** — the room kept playing the track before it (P-23) |
| Spotify | track; album | ✓ 4.2s; ✓ 2.4s |
| TIDAL | track; album | ✓ 5.1s; ✓ 2.3s |
| TuneIn (New) | stream | ✓ 2.2s |
| YouTube Music | favourites | album ✓ (through the CLI); both *song* favourites `ERROR_PLAYBACK_NO_CONTENT`, from x2rocktv and the CLI alike — the favourites, not the app |

## Search

- Both search every service at once. The app puts the **Preferred service first** (Sonos Radio
  here), then an order of its own (TIDAL, YouTube Music, Qobuz, Pandora, Spotify, …); x2rocktv is
  alphabetical under its own Preferred services tier, which is empty on the Shield.
- The app has a **filter chip per service** above the results; x2rocktv has "More from
  <service>" at the end of each block, which opens that service's full search. Equivalent reach.
- The app keeps a **search history** on its Search tab. x2rocktv keeps none, and typing is the
  expensive part on a remote. Attainable: a short list of this device's recent terms.
- x2rocktv's Amazon results were richer: tracks and albums where the app showed two playlists.

## Beyond services

Seen in the app's System tab and Settings (viewed, nothing changed):

- **Room order.** The System tab has a **Sort** menu — *Actively playing* (the default),
  *Frequently used*, *Alphabetical* — and **Pin**. CLAUDE.md's rule that the room list is
  alphabetical rests on "Sonos has nothing positional". That premise is no longer true of the app.
  Whether any of it is the household's or only the app's is not known. Owner's call: PUNCH P-20.
- **Room settings, home theatre.** The app offers Surround audio (on/off, levels), Sub audio
  (on/off, level), TV autoplay, Ungroup on autoplay, TV dialog sync and Group audio delay.
  x2rocktv has night sound, speech enhancement, tone, loudness, status light, touch controls, IR
  repeater and remote feedback light. Sub and surround levels are UPnP EQ types x2rock measured
  (`SubGain`, `SurroundLevel`, `MusicSurroundLevel`; they answer only where the hardware is), so
  they are attainable through the `setEq` this app already has. Autoplay and lip sync are probably
  `DeviceProperties`/EQ too, unverified.
- **Groups** (Settings): a named selection of rooms. x2rocktv's presets are the same idea, kept on
  the device and carrying levels and a favourite. None were saved in this household.
- **Music library** (local shares): none configured here, so not comparable. x2rocktv has no
  library browse; it would be UPnP `ContentDirectory` (`A:` containers), if a household needs it.
- **Alarms**: the app edits and removes; x2rocktv creates and arms only (`AlarmsScreen`). Editing is
  `UpdateAlarm`, removing `DestroyAlarm`, both UPnP and both done by the x2rock CLI.

## Found with the remote

- **The search field holds the D-pad once the keyboard is closed.** With the keyboard dismissed
  (Back), Right and Down stay in the field, so the Search button beside it and the results under it
  cannot be reached; Back leaves the screen. Seen on the Shield with key events from both the
  keyboard and D-pad sources; the keyboard's own SEARCH key works. PUNCH P-19, fixed the same day:
  Up and Down always leave the field, Right and Left from the end of the text (`SearchFieldFocusTest`).

## Attainable, in order of what a listener would notice

1. **Personalised front pages** for Spotify and Deezer: read the manifest `browse` endpoint with the
   account's token as `Authorization: Bearer`, falling back to SMAPI root on any non-200 — today
   `SmapiClient.shelves` is anonymous and only consulted when root is empty. Amazon (401) and TuneIn
   (403) want the header each expects worked out first.
2. ~~The search field's D-pad trap~~ (above; fixed).
3. **Recent searches** on the Search screen.
4. **Sub and surround levels** in the room panel's Sound section, for rooms that have them.
5. **Amazon's "Content Unavailable" row** dropped from listings.
6. **Editing and removing alarms.**

Not attainable: YouTube Music browse and search (Google's gate), Apple Music library (empty
credential), Bandcamp until the household relinks it.
