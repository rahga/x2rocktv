# Punch list: catching up with x2rock

Written 2026-10-01. x2rocktv's last commit was 2026-09-09 and its last working session 2026-09-12;
the Rust sibling `../x2rock` landed 388 commits since 2026-09-05 and its `docs/architecture.md`
grew to 9,400 lines, audited whole on 2026-09-13 and reviewed 2026-09-18/19. This is what that
work found that this app does not yet know or do. Each item is roughly one commit; tick it here
when it lands, and add the fact it rests on to `docs/lan-transport.md` if it is a protocol fact.

Where an item cites "x2rock", the evidence is in `../x2rock/docs/architecture.md` under the
section named. That file is the record; this one is the to-do.

Decisions already taken (2026-10-01):
- Music services are a **radio-only tier**: no account, little typing. Search, browse and
  account linking stay out — they need a keyboard and a browser.
- The ratings work in progress is **finished and committed first**, not parked.

Status: `[ ]` open · `[x]` done · `[-]` decided out

---

## Tier 0 — the open work and the stale record

- [x] **0.1 Finish the ratings WIP and commit it.** *Done 2026-10-01: buttons drawn from
  `SonosHousehold.ratingState`, which answers null wherever `rate` would refuse; filled thumb
  for the current state; `RatingsTest` and `RatingsCatalogueTest`, each mutation-checked.* Wired end to end (`Smapi.kt`,
  `RatingsCatalogue`, `SonosHousehold.rate`, thumbs in `PlayerScreen`), tests passed 2026-09-12.
  To close: gate `canRate` on the service *publishing* ratings, not just on a real `track.id`
  (today a Plex track shows the buttons and pressing one answers "publishes no ratings"; the
  catalogue already knows, expose it on `GroupState`). Show the current rating state from
  `getExtendedMetadata`. Tests: `RatingsCatalogue` hit/miss/"asked, none published",
  `Upnp.listAvailableServices` against a canned envelope, one successful `rate()` through a fake
  SMAPI endpoint — mutation-check each. No live test: this household's iHeart listening is Live
  stations, which have no track id to rate. Icons instead of emoji labels. Commit
  `docs/porting-from-x2rock.md` (untracked since 2026-09-23) in the same go.
- [x] **0.2 Stale docs.** *Done 2026-10-01: README rewritten for the LAN app;
  ARCHITECTURE.md rebuilt from the code; fixture path fixed; the transport doc's migration
  section and answered questions marked as such.* `README.md` still describes the cloud API and `SONOS_CLIENT_ID`/`SECRET`.
  `CLAUDE.md` puts fixtures in `core/src/test/resources/fixtures/`; they are in
  `core/src/testFixtures/resources/fixtures/`. `ARCHITECTURE.md` predates RoomPanel, TvSoundbar,
  NowPlayingPublisher, ChannelSync, MulticastGate, SeedStore, the smapi package and `settings:1`,
  and still lists five routes and a favourites/primary-room store. `docs/lan-transport.md`'s open
  questions still list network-change handling and Streamer testing, both done.
- [x] **0.3 Dead declaration.** *Done 2026-10-01: deleted, not implemented. OkHttp's
  ping already fails a socket whose pong is overdue, so a silent peer is dead within 60s;
  `SilentPeerTest` freezes a live connection and fails without the ping.* `LanHttp.SILENCE_LIMIT_MILLIS = 90_000` is never read. Implement
  it with 1.2 (x2rock: ping every 30s, 90s of total silence is a dead socket) or delete it.

---

## Tier 1 — robustness: protocol facts the app gets wrong or ignores

- [x] **1.1 `playback:1` carries errors, and the app reads them as statuses.** *Done
  2026-10-01: dispatched on `_objectType`; kept as `GroupState.lastError` until the room plays
  again, shown on the pane. Fixture captured off the office One SL. `globalError` was never
  seen in either capture, so it is not handled: add it when one is captured.* `apply()` switches
  on namespace only; `Frame.type` exists and is never consulted. A `playbackError`
  (`{errorCode: ERROR_PLAYBACK_FAILED, reason: ERROR_CANT_REACH_SERVER, trackName, itemId}`)
  deserialises into an all-null status and vanishes — benign today only because every field is
  optional-means-unchanged. Dispatch on `header.type` (`playbackStatus`, `playbackError`, and
  `globalError` events) and surface the error on `GroupState` so the pane can say what failed.
  Capture a real `playbackError` fixture by playing a dead stream URL; do not invent it. x2rock:
  "`playback:1` carries errors too, and they parsed as statuses".
- [x] **1.2 One lost socket tears down the household.** *Done 2026-10-01: only the seed's or a
  coordinator's loss rebuilds; a member's is evicted and its level withdrawn; sockets open
  outside the lock. Not exercised on hardware — the office household has no group members.
  Re-reading state after a failed write is left to 1.4, where command failures are surfaced.* `handleLoss` rebuilds everything on any
  socket failure, members included. x2rock's rule: only an *unreachable* coordinator (or the
  seed) drops the session; a member socket is best-effort and its loss is swallowed; a player's
  *refusal* never drops anything. From its `session::Pool`: never hold the lock across an open,
  evict a socket the keepalive gave up on and reopen before handing it out, re-read state after
  a failed write so the UI matches the speaker. Test with `FakePlayer.dropConnection` on a
  member versus a coordinator.
- [x] **1.3 A group change the coordinator did not answer.** *Done 2026-10-01: on a reply
  timeout, never a refusal, `modifyGroupMembers` waits up to 20s for the pushed topology, then
  checks a fresh `getGroups`; `useTvInput` waits for its `htInputFormat` event and fails if it
  never comes. Not exercised on hardware: the stall needs a Beam on its TV input at home.
  The failures still reach no one until 1.4.* Grouping onto a Beam on its TV input
  stalled past the 5s reply timeout for 14–20s and then applied (x2rock "The Beam stall",
  "Confirm a group change the coordinator did not answer"). `runCatching` swallows the timeout
  and the panel shows nothing. On timeout only — never on refusal — read `getGroups` fresh and
  find the group *by coordinator*; if every added player is in and every removed one is out,
  it is done. Likewise `useTvInput` on a member soundbar, whose reply is lost by design: the
  `htInputFormat` event confirms it, but report a failure if nothing arrives within ~20s.
- [x] **1.4 Commands fail silently.** *Done 2026-10-01: every view-model command reports
  through `TransientNotice` — the pane, a banner over the room panel, and the queue and
  favourites screens; a failed favourite no longer navigates back. `ERROR_NO_PERMISSION`
  names Connection Security; the UPnP 403 text now gives the path x2rock confirmed. Not yet
  seen on a television.* Every view-model command is `runCatching { }` with the
  error discarded. Minimum: a transient message on the pane or panel for a refused or timed-out
  command, reusing the 4s message the ratings WIP added. `ERROR_NO_PERMISSION` and UPnP 403 get
  the wording from 1.6 and 1.7.
- [x] **1.5 Skip to a queue item fails with 701 after radio.** *Done 2026-10-01: 701
  confirmed on the office One SL on Radio Paradise; `skipToQueueItem` now makes the queue the
  source when `GetMediaInfo` says it is not, seeks, and plays. Live test passes there and
  restores the source. The queue screen marks the playing row only while the queue is the
  source, and says when it is not.* `skipToQueueItem` is `Seek
  TRACK_NR` alone; when the queue is not the transport's source (after a station or TV input)
  Seek answers 701. Set `SetAVTransportURI x-rincon-queue:<coordinator>#0` first. Read
  `GetMediaInfo` for `x-rincon-queue:` to know whether the queue is in use, which is what the
  queue screen's now-playing marker should key on.
- [x] **1.6 Authentication-on households.** *Done 2026-10-01 against the fake: a
  permission refusal at connect sets `authenticationRequired`, names the switch, and keeps the
  remembered player; Retry recovers once it is off. The REST pre-check was not needed —
  `getGroups` answers at once. Needs the switch flipped at home to confirm.* With the Sonos app's Connection Security →
  Authentication switch on, every Control API command answers `ERROR_NO_PERMISSION`, starting
  with `getGroups`; UPnP keeps answering. The app would show a generic error and retry forever.
  Detect it at `getGroups` and say so, naming the Sonos app path (Account → Privacy and
  Security → Connection Security). No sign-in on TV: the consent page cannot be completed with
  a remote, and the bearer token would ride in every command header, not the handshake. Cheap
  pre-check: REST `GET https://<ip>:1443/api/v1/players/local/info` stays open and
  `credentialTypeAllowed` flips `API_KEY` → `GUEST_TOKEN`.
- [x] **1.7 UPnP-off households.** *Done 2026-10-01: read off the seed and followed by
  `settingsChanged`; `HouseholdState.upnpOff`; Queue and the panel's Source withdrawn, the
  TV pane's toggles kept (they anchor focus) with a note naming the switch. Fixtures and a
  read-only drift test from the office One SL. The CA fallback for a solo soundbar's TV input
  was not built. Flip the switch at home to see the UI follow.* With the UPnP switch off every SOAP call is 403: queue, TV
  input, night/dialog writes. The app explains 403 only on the queue screen; TV input and EQ
  failures are swallowed. Read the switch via `effectiveSettings:1 getSettingsGroup
  {"groupName":"security"}` (player-scoped, no other parameters; household scope refuses) →
  `allowInsecureUPnP`, `allowUnauthenticatedControl`, `allowGuestAccess`; follow it live via a
  player-scoped parameterless `subscribe` whose `settingsChanged` event carries per-group
  timestamps, re-reading only when `security` moves. Put `upnpOff` on `HouseholdState`;
  withdraw queue, TV Input and the night/dialog toggles (reads still work over `settings:1`).
  Control API fallback for TV input: `homeTheater:1 loadHomeTheaterPlayback` (player-scoped, no
  params) does not preserve the group, so offer it only for a soundbar that is alone. Capture
  `getSettingsGroup` and `settingsChanged` fixtures.
- [x] **1.8 Household id fallback.** *Done 2026-10-01: `/status/zp`'s `HouseholdControlID`
  replaces the malformed-frame probe, which is removed; a live test connects a household-less
  seed on the office One SL. The second half needed nothing: a seed is reached by its `.local`
  name, so an address now held by another household's player fails hostname verification,
  and a failed remembered seed is already cleared.* `SonosSocket.householdId()` (a malformed frame) is still the
  fallback and a One SL ignored it. Replace with the long id from `http://<ip>:1400/status/zp`,
  which the live suite's named-address form already reads. A remembered seed whose household
  differs from what the player now reports is rejected, not used.
- [x] **1.9 Volume buttons disabled while muted.** *Done 2026-10-01: Vol −/+ and the
  per-speaker steps are never disabled and send `setRelativeVolume`, so they need no known
  level and unmute a muted room; a muted level is shown as "30 (muted)". The panel's bar keeps
  `setVolume`. Live test on the office One SL; its volume restores now wait for the player's
  own event, after one run left the room at 4.* CLAUDE.md's own rule: a disabled tv-material3
  button takes focus and draws no highlight, yet Vol −/+ are disabled while muted and until the
  volume is known. x2rock: a step on a muted room unmutes it (both setters unmute anyway) and the
  bar stays at its level, dimmed. Use `setRelativeVolume` for the buttons (a stateless control,
  per Sonos's rules), keep `setVolume` for the panel's left/right bar. Never answer a volume
  *event* with a volume *command*.
- [x] **1.10 The media session advertises what the source forbids.** *Done 2026-10-01: the
  session's actions follow `availablePlaybackActions`, seek also needing a duration, and are
  re-published when they change. Check at home with `dumpsys media_session` on a station:
  no SKIP_TO_NEXT or SEEK_TO, and still `volumeType=1`.* `NowPlayingPublisher` always
  advertises skip and seek. Gate the session's actions on `availablePlaybackActions` as the pane
  does, so the launcher card and a voice "next" do not offer a skip to a live station.
- [x] **1.11 Network change: arrival, not loss.** *Done 2026-10-01: `onLost` no longer
  rebuilds; a lost carrying network fails the sockets by itself and the next arrival reconnects.
  Android framework code, so untested here: on a box with both, unplug the unused network and
  the rooms must not blank.* `NetworkMonitor` reconnects on both
  `onAvailable` and `onLost`. x2rock: only arrival on a network is a reason to reconnect; acting
  on loss causes retry storms. Check what `onLost` does and drop the reconnect if that is it.

---

## Tier 2 — the room list and panel: what the widget shows that we do not

- [ ] **2.1 Fixed volume.** `GroupVolume.fixed` is parsed and never read. A Port or Amp with a
  fixed line-out reads "fixed" where its level is and loses its volume controls (x2rock
  `fixedVolume`, `memberFixedVolume`; `settings:1 volumeMode FIXED`). Capture a fixture only if
  a real one exists; otherwise a unit test on a derived `groupVolume` event.
- [ ] **2.2 Mute, shown.** Group and member mute are commands today, but neither the row nor the
  panel shows them. Row: a mute glyph in place of the percentage, the bar dimmed at its level.
  Panel: member rows show muted. Mute stays a group-only control outside the speaker rows.
- [ ] **2.3 Normalize.** In RoomPanel's "Playing together", an action that sets every member to
  the group's level (`playerVolume:1 setVolume` per member), shown only while levels differ.
- [ ] **2.4 Bonded speakers that have dropped off.** `zones:1 subscribe` (there is no `get`)
  answers two events, per-member `state.disconnected` and `settings.gainTrimDB`; x2rock merges
  them for ~2s and unsubscribes. A Sub or surround off the network is otherwise invisible. Mark
  the room with a warning glyph. Subscribe at connect and on topology change, not continuously.
  Low priority.
- [ ] **2.5 `hdmi:1` for the TV badge and detection.** Player-scoped `hdmi:1 subscribe` returns
  `tvPowerStatus` and `connection` (`NO_CONNECTION` is an empty port; Guest TV's Beam has nothing
  plugged in). `TvSoundbar.detect` and the badge key on `HT_PLAYBACK`, which is why three rooms
  qualify. Excluding `NO_CONNECTION` narrows the heuristic and drops the badge from a room that
  will never be on TV. Verify the event shape on hardware and capture it.
- [ ] **2.6 Favourites the household can no longer play.** x2rock hides them. Check what
  `getFavorites` returns for one and whether anything distinguishes it; if nothing, leave as is.

---

## Tier 3 — features x2rock has that fit a remote

- [ ] **3.1 Native sleep timer.** The current timer is a view-model coroutine: invisible to every
  other controller, lost with the process. Sonos's lives on UPnP `AVTransport:1`, group-scoped:
  `ConfigureSleepTimer(InstanceID, NewSleepTimerDuration)` in `HH:MM:SS` (bare seconds → 402, an
  empty string cancels) and `GetRemainingSleepTimerDuration → RemainingSleepTimerDuration,
  CurrentSleepTimerGeneration` ("no timer" is an empty element at generation 0; `00:00:00` is a
  real "expired, about to stop" state that lingers ~7s). Not pushed: read it when the pane opens
  and after setting, then count down locally. Withdrawn under `upnpOff`.
- [ ] **3.2 Playlists.** `playlists:1 getPlaylists` / `loadPlaylist`, the same shape as
  Favorites. Ids are bare (`"0"`), and `action` defaults to APPEND where `loadFavorite` replaces,
  so pass `REPLACE`, or offer append as "add to queue".
- [ ] **3.3 Recently played.** `history:1 getHistory` (40 items) plus `playback:1 loadContent
  {id, type}`. `loadContent` loads but does not start and ignores `playOnCompletion` — x2rock
  pauses, loads, then presses play until `PLAYING` (up to 12s); it is replace-only; anonymous
  services answer `ERROR_ACCOUNT_INVALID_ID`; with Personalization off, `getHistory` answers
  `ERROR_DISALLOWED_BY_POLICY` ("History is disabled") — show that, do not retry.
- [ ] **3.4 Queue edits beyond remove.** Move (`ReorderTracksInQueue`), clear
  (`RemoveAllTracksFromQueue`, confirmed first), save (`SaveQueue`), add a favourite or playlist
  (`AddURIToQueue`, then seek to `FirstTrackNumberEnqueued`, not `NewQueueLength`). Every edit
  quotes a fresh `UpdateID`; a stale one gets 1028 and re-reads rather than retries. The
  1000-item cap needs paging (`StartingIndex`).
- [ ] **3.5 Queue freshness.** The `UpdateID` of a `Q:0` browse with count 1 is the change signal
  (x2rock found no `queueVersion`; see the correction below). While the queue screen is open, re-browse on
  each `playback:1` event whose `UpdateID` moved, off the event path with its own timeout
  (x2rock: one wedged coordinator stalled every room). Request/response, bounded to the open
  screen — the one documented exception to "nothing polls".
  **Correction, 2026-10-01:** `queueVersion` *is* sent. The office One SL (build 97180312)
  sends `"queueVersion": "QV:00019"` in `playbackStatus`, and this repo's own captured
  `event.playbackStatus.json` carries `"queueVersion": "8"`. x2rock's "does not exist" holds
  for the firmware it was measured on and not beyond. Check whether it moves on a queue edit
  before choosing it or `UpdateID` as the signal.

- [ ] **3.6 Room settings: tone.** Bass and treble (`RenderingControl Get/SetBass|Treble`,
  −10..10), loudness (`Get/SetLoudness` needs `Channel=Master`, wire `1`/`0`), TruePlay on/off
  (`Get/SetRoomCalibrationStatus`). Read via `settings:1 getPlayerSettings` `eq{bass, treble,
  loudness}`, already fetched for night/dialog; write over UPnP; setters answer an empty body so
  re-read after. Per player, not group; bonded members follow the visible player. Under "Room
  settings" in RoomPanel for every room, not only soundbars.
- [ ] **3.7 Snooze or dismiss a ringing alarm.** `GetRunningAlarmProperties` (800 means none is
  ringing), `SnoozeAlarm`, and `pause` to stop it (`DestroyAlarm` does not). A remote press is
  the right shape for this; alarm *management* is not (see Tier 5).
- [ ] **3.8 Radio directory.** Radio Browser API, no key: browse by tag or country, sort by votes
  with `hidebroken=true`, use `url_resolved` never `url`, send a `User-Agent`. Play via
  `playbackSession:1 createSession{appId, appContext}` (group-scoped) → `loadStreamUrl{streamUrl,
  stationMetadata}`; `TRANSITIONING` can last 4s or more; wait up to ~10s for `PLAYING` and
  report a failure otherwise, because `loadStreamUrl` accepts unplayable URLs and sits IDLE.
  Direct HTTP to a third party, off the household's sockets, with its own short timeout — the
  ratings WIP's `@InternetHttp` client is the one to use. Optional: a "Play URL" row with the
  on-screen keyboard (`x-rincon-mp3radio://` for bare streams; `http(s)://` gets 714).
- [ ] **3.9 A station's ICY text.** Already read as `streamInfo`. x2rock's layout rule: the
  stream's headline on top, the host below, and a stream with no headline named once by its host;
  never parse the text into artist and title. Check the row and pane against that.

---

## Tier 4 — discovery and the network

- [ ] **4.1 mDNS fallback.** `NsdManager` for `_sonos._tcp`; the TXT record carries `uuid`,
  `location` and `mhhid` (the long id), a complete `DiscoveredPlayer`. Lives in `:app` behind an
  interface like `MulticastGate`; tried after SSDP's 3s window draws nothing. x2rock's port-1443
  connect-scan is the last resort, and `docs/porting-from-x2rock.md` says to re-derive rather
  than inherit it: measure on the Streamer (Wi-Fi) and the Shield (Ethernet) first.
- [ ] **4.2 Two households on one network.** `Discovery.stopAfterFirst` takes whichever player
  answers first. x2rock: never guess between two households; several on one LAN turned up in
  practice. Collect replies for the window, group by `HOUSEHOLD.SMARTSPEAKER.AUDIO`, and if there
  are two, ask once and remember in `SeedStore`.
- [ ] **4.3 Network identity.** x2rock keys remembered players by the default gateway's MAC and
  never scans an unknown network; the app remembers one seed regardless of network. Key the seed
  per network via `ConnectivityManager`/`LinkProperties` so a device carried elsewhere does not
  probe a home address for 3s first. Low priority on a TV bolted to one wall.
- [ ] **4.4 Cover art bounds.** Coil uses the LAN client with no size cap. x2rock: fetch only
  `https://`, or `http://` from a private IPv4 on 1400; at most 2 MB and 8s; refuse
  `Content-Encoding`; check the magic bytes. A Coil interceptor on the shared loader.
- [ ] **4.5 TV channel tiles.** Program posters are cleartext `.local` URLs the launcher cannot
  fetch, and `x2rock://room/<groupId>` links go stale on every regroup. Resolve posters to an IP
  or drop them; link by player id and resolve to its current group on open (HomeViewModel already
  follows speakers rather than ids).

---

## Tier 5 — decided out, or waiting on a decision

- [-] **Music-service search, browse, linking, `match`, household-token import.** Out
  (2026-10-01): needs a keyboard and a browser. The Kotlin SMAPI client from the ratings work is
  the seam if this ever returns.
- [-] **Alarm management** (create, edit, recurrence, timezone). The Sonos app's job; only 3.7.
- [ ] **Scenes** (saved groupings, levels, soundtrack). Plausible as "Party" presets in RoomPanel;
  revisit after Tier 3. Rules if built: never remove a coordinator from its own group; set mute
  after levels, because setting a level unmutes.
- [-] **Chime / notify** (`audioClip:1 loadAudioClip`). No TV use case.
- [-] **LED, button lock, rename, IR repeater, battery, firmware check.** The Sonos app's job.
- [-] **A foreground service.** Still no; `connectedDevice` if ever (CLAUDE.md).
- [-] **`loadCloudQueue`.** A permanent no, per Sonos's own scope.

---

## Protocol facts to copy into `docs/lan-transport.md` (no code change)

Recorded by x2rock since 2026-09-04 and relevant here:

- ~~`playback:1` sends `playbackStatus` and `playbackError`~~ — copied in with 1.1. An event
  that omits a field is not setting it false.
- `queueVersion` was not sent by the firmware x2rock measured, but is sent by the office One SL
  and appears in this repo's captures (see 3.5); UPnP `UpdateID` is the signal known to work.
  `itemId` is the 1-based queue position while the queue drives and an opaque hash otherwise, and
  it renumbers on every edit.
- Namespaces resolve by prefix (`home:1` → `homeTheater:1`); read the echoed header. 41
  namespaces enumerated on API 1.54.1. `info:1 getInfo` gives `restUrl`, the API version and the
  capabilities.
- `homeTheater:1 subscribe` succeeds and never fires: night/dialog travel only in GENA
  `LastChange`. `getPlayerSettings` reflects a `SetEQ` write immediately, so re-read after write
  is right. `homeTheater:1 loadHomeTheaterPlayback` switches a *solo* soundbar to TV over the
  Control API.
- `DialogLevel` is 0/1 on a Beam; 1–4 only on hardware this household lacks.
- The security switches are the `effectiveSettings:1` security group: Authentication, UPnP,
  Guest Access. Authentication on → `ERROR_NO_PERMISSION` on every Control API command. UPnP off
  → 403 on every SOAP call, while the Control API and `getaa` art keep working.
- Both volume setters unmute. A second volume command within ~260ms is deferred and `getVolume`
  reads stale inside that window: never report a read-after-write. Group volume is the members'
  average.
- A live stream is identified only by `container.type == "station"`. TuneIn: `canPause: false`,
  pause → IDLE. Sonos Radio: `canPause: true`, and resume restarts the track.
- Player TLS cert: leaf-only, `CN=<MAC>`, SAN `sonos-<MAC>.local` plus `urn:sonos:udn:RINCON_…`
  plus `sonos-<MAC>.smartspeaker.audio`, about six months' validity, issued by "Sonos Device
  Authentication Root CA".
- Open ports: 1400, 1410 (answers 404 to everything), 1443, 7000 (AirPlay).

---

## Verification, for every item

- `./gradlew :core:test :app:testDebugUnitTest` green; every behavioural test mutation-checked.
- New wire shapes (`playbackError`, `getSettingsGroup`, `settingsChanged`, `hdmi:1`, `zones:1`,
  `GetRemainingSleepTimerDuration`) captured from hardware with `-Dx2rock.live=discover`,
  redacted, added under `core/src/testFixtures/resources/fixtures/`, and covered by the
  fixture-drift check in `LiveHouseholdTest`.
- Tier 1 on both devices: pull a member's power (1.2), group onto the TV Beam (1.3), flip
  Authentication and UPnP in the Sonos app and back (1.6, 1.7).
- `dumpsys media_session` still reads `volumeType=1` after 1.10.
