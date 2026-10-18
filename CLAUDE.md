# CLAUDE.md

## Project
**x2rock** — A Sonos controller for Google TV / Android TV. The Sonos layer is a plain
Kotlin/JVM library (`:core`) with no Android dependency. See `ARCHITECTURE.md` for the
full module/file map before making non-trivial changes.

A Linux CLI (`:cli`) and its MPRIS daemon lived here until 2026-09. They were removed
because the separate Rust project `rahga/x2rock` (cloned alongside as `../x2rock`) does
the same job better on the desktop: local-first, no account, event-driven. Do not
reintroduce a CLI here without deciding what it does that `x2rock` does not.

## The work queue is local: `.local-work/`

Since 2026-10-10 the work on this app is split between agents on this machine, and the queue lives
in `.local-work/` at the repo root — **untracked** (`.gitignore`), so it is not on GitHub. Read
`.local-work/README.md` before picking anything up: it has the protocol (claim an item, one agent
per item, both suites green and a release build before done, log the hand-off), the device and
room rules of the day, and the conventions. `TODO.md` is the backlog (`T-nn`), `PUNCH.md` the
defects, checks owed on hardware and decisions already taken (`P-nn`), `LOG.md` the hand-offs.
If the directory is missing on a fresh clone, there is no queue here; ask the owner.

## Language & Platform
- Kotlin only — no Java
- `:app` — Google TV / Android TV (Jetpack Compose for TV), min SDK 23, compile/target SDK 35
  (`app/build.gradle.kts` is the source of truth; this line has been wrong before)
- `:core` — plain Kotlin/JVM, no Android dependency; `:app` depends on it
- Build system: Gradle with Kotlin DSL (`build.gradle.kts`)

## Sonos Integration

**Read `docs/lan-transport.md` before touching anything network-related.** It records the
verified handshake, wire protocol, discovery, trust model and Android specifics, and every
claim in it was run against real hardware.

- The app speaks the Sonos **Control API over the LAN**, directly to the speakers:
  `wss://sonos-<MAC>.local:1443/websocket/api`. **No account, no OAuth, no tokens.** The
  cloud transport was removed in 2026-09 — it offered nothing the LAN does not, and its
  consent page cannot be completed with a TV remote, so it could never have shipped here.

  **One gated exception, T-27 (2026-10-13): a developer's own Sonos integration.** A household
  with Sonos's **Authentication** switch on refuses every command without an account token
  (`ERROR_NO_PERMISSION`), so a build can carry its owner's registered Control integration and
  sign the TV in from a phone: a QR code to Sonos's consent page, whose redirect page
  (`rahga.github.io/x2rocktv/callback.html`, x2rocktv's own since 2026-10-17) forwards the phone to the TV's LAN address, where the
  app listens once and redeems the code. The token then rides in each WebSocket command's header
  as `authorization: Bearer …`. **No key or secret is ever committed, and none goes in the Play
  bundle**: a build has one only with `-Px2rock.sonosIntegration` (it reads `~/x2rocktv-sonos.api.key`
  and `~/x2rocktv-sonos.api.secret`: x2rocktv's own integration, not the x2rock CLI's, per Sonos's
  Terms 1(c)), and the Settings row exists only then, with Beta features on.
  Without it nothing changes: no account, no token, no call to Sonos. **Run end to end on the
  Streamer at home, 2026-10-13**: the phone's browser followed the page's hop from HTTPS to the
  TV's plain-HTTP LAN address, the TV redeemed the code, and the household (Authentication off)
  went on answering every command carrying the token. Since 2026-10-17 a build carries **x2rocktv's own integration** (its key files and
  `rahga.github.io/x2rocktv/callback.html`), signed in end to end on the Streamer. The TV's listener
  reads the whole request and ends the connection properly before closing: closing with the headers
  unread reset the connection and threw away the queued "Signed in" page, so the phone sat on a
  blank tab over a sign-in that had worked (logged on the TV; loopback tests never show it). Not yet seen: a household with it on. Anyone who registers with
  Sonos and signs its agreement can build with their own.

  **Untracked service pages (T-28).** `SonosHousehold` can route a service's root, containers
  and search through a `ServicePages` source — for what a service's SMAPI endpoint refuses a client
  like this one — and lists a service only that source reaches only while it answers. **No source
  ships in the repository.** The owner's lives in `core/src/cloud` and `core/src/cloudTest`, which
  git ignores, and joins a build only with `-Px2rock.cloudContent`, found through `ServiceLoader` so
  no tracked code names it. It uses an undocumented API outside the Sonos Platform Terms (3(a), 3(g)),
  so it is for the owner's own TVs and must never be committed, nor its captured replies; its notes
  are in its own folder. Tracked tests cover only the routing, against a stand-in.
- It is **push, not poll**. Subscribe and the speakers send changes; a command's effect
  arrives as an event, so nothing re-fetches after acting. If you find yourself adding a
  timer or a `delay()` before a re-read, something is wrong.
- **Scope matters.** Group-scoped namespaces go to that group's *coordinator's* socket,
  player-scoped ones to that *player's own* socket; anything else answers
  `ERROR_INVALID_OBJECT_ID`.
- **Switching a room to its TV input is UPnP too**, and for the same reason: the Control
  API has no notion of a source. It is `SetAVTransportURI` to
  `x-sonos-htastream:<soundbarId>:spdif`, sent to the group's **coordinator** while naming
  the *soundbar* — which need not be the same player. Addressing the soundbar directly means
  something else entirely: it leaves the group and takes the TV alone, rather than bringing
  the room with it. Verified on a Kitchen+Bedroom group: Kitchen came along.

  When the soundbar is a member, taking the TV hands coordination to it, so the player we
  asked stops coordinating before it answers and the reply is simply lost. That is normal,
  not a failure: silence is never treated as an answer, from either player. **Nothing polls
  to find out** — unlike the sibling project, which had to: the switch arrives as a
  `playbackMetadata:1` event carrying `htInputFormat`, the same thing that lights the row.
  Measured on hardware: TV audio at ~4-5s, the format settled by ~9s. With no reply,
  `useTvInput` waits up to 20s for that event and fails if it never comes; an error the
  player actually answered with (`UpnpRefusedException`) fails at once.
- **No reply is not "no".** A player can stall past the 5s reply timeout and then do what it
  was asked — a Beam on its TV input took 14-20s to apply a regroup. A timeout is its own
  type, `ReplyTimeoutException`, and `modifyGroupMembers` answers one by waiting for the
  topology to show the change, finding the group **by coordinator** because the id moves,
  then asking once more. A `SonosCommandException` is the player's refusal: final, never
  re-checked.
- The **queue is not in the Control API at all** (`ERROR_UNSUPPORTED_NAMESPACE`). It lives
  behind UPnP on cleartext port 1400 — see `Upnp` — and is asked for rather than pushed,
  re-read when the room's pushed `queueVersion` moves.
- **No copy of Sonos's own reference is kept in the repo** (removed 2026-10-17, T-30 item 7):
  three pages scraped from Sonos's developer site lived in `docs/`, and their provenance could
  not be vouched for. Everything the code relies on is in `docs/lan-transport.md` and the
  captured fixtures, all of it observed on hardware. Read Sonos's public pages where they are;
  do not save copies here, and nothing from behind the portal login at all.

## Architecture
- MVVM in `:app`; `:core`'s `lan` package is everything that talks to a speaker
- `SonosHousehold` is the single live view of the household — two `StateFlow`s fed by
  subscriptions, plus the commands. ViewModels derive their UI state from it and hold no
  timers of their own.
- `:core` is pure Kotlin/JVM on purpose: it can be exercised against real speakers from a
  plain JVM test, which is how the transport was developed and how it should stay.

## Code Style
- Idiomatic Kotlin
- No unnecessary abstractions
- Prefer simplicity over cleverness

## Building

If Java isn't present, install a JDK (17+ is safe, 21 is what's been tested) via the system
package manager or `mise`. Gradle itself does not need installing — the project pins 8.7 and
`./gradlew` fetches it.

`gradle-wrapper.jar` used to be missing from fresh clones, which broke `./gradlew` for anyone
who did not already have a Gradle to regenerate it with. The cause was the blanket `*.jar` in
`.gitignore`'s "Package Files" block; there is now an explicit negation for it. If it goes
missing again, look there first.

**Put release builds on the TVs, not debug ones.** `./gradlew :app:assembleRelease` signs with
the local debug key, so it installs over a debug install with `adb install -r`. A debug build
is several times slower on a TV box — 4.7s to the room list on the Streamer, against 0.6-0.8s
for release — so judge nothing about speed from one. The release build is shrunk by R8: Gson
reads this project's classes by field name, so `app/proguard-rules.pro` keeps them all, and the
first release ever run crashed on launch until it did.

**Google TV keeps an app's icon until its `versionCode` moves.** The logo replaced a blue
placeholder (2026-10-07), and the Streamer's "Your apps" row went on showing the blue circle after
a reinstall, a launcher restart and a disable/enable; bumping `versionCode` from 1 to 2 changed it
at once. Bump it with any icon change. The icon is the brushed "X2" cut from the wordmark, the
banner the full wordmark; both are generated from `~/x2rock.png` and meant to be refined later.

An Android SDK is needed for `:app` (`sdk.dir` in `local.properties`, or `ANDROID_HOME`).
**There are no credentials to supply** — no client id, no secret, nothing in
`local.properties` beyond the SDK path. If you find yourself looking for Sonos API keys,
you are working from a stale mental model of this project. The one exception is opt-in and
the owner's: `-Px2rock.sonosIntegration` compiles a developer's own integration into a build
for their own TVs (T-27, above); a build without it is complete, and a Play bundle refuses it.

### Two test suites, and why both

`FakePlayer` lives in `core/src/testFixtures/`, so `:app`'s view models test against the
same fake household over a real socket rather than a hand-stubbed repository. Three seams
exist to make that possible, and are worth keeping: `Preferences` (so the stores are plain
Kotlin instead of `Context` holders), `ChannelSync`, and `NowPlayingPublisher` (so
`PlayerViewModel` no longer builds a `MediaSession` in a field initializer — the single
reason it had no coverage at all).

**`./gradlew :core:test` — against `FakePlayer`, no hardware.** An in-process TLS WebSocket
server replaying captured payloads. It exists for one reason that carries on its own: CI has
no Sonos on its LAN, so without it there is *no* automated regression check and verification
happens only when someone remembers. It catches bugs we introduce — parsing, the state
machine, error handling.

**`-Dx2rock.live=<ip|discover>` — against real speakers.** Opt-in, skipped otherwise. It
catches what a fake cannot: a fake only knows what it was told, so it can never discover
that the protocol behaves differently than assumed. Everything this project learned the hard
way came from hardware.

```sh
./gradlew :core:test                                  # fake only; what CI runs
./gradlew :core:test -Dx2rock.live=discover           # + real speakers, read-only
./gradlew :core:test -Dx2rock.live=discover -Dx2rock.live.room=Kitchen
./gradlew :core:test -Dx2rock.live=192.168.1.20       # a named address, no SSDP at all
```

The named-address form does not touch SSDP: it reads the player id from
`/xml/device_description.xml` and the household from `/status/zp`, both cleartext on 1400.
That is the form to use on a network where discovery does not work — and it is how the suite
was first run against a second household, a single One SL on an office LAN that drops SSDP.
Everything passed there, fixture drift included, which is the first evidence that these
assertions really are about the protocol rather than about one house.

The live suite is **read-only unless a room is named**, because it may run against a
household someone is listening to; what does change is restored afterwards. Its assertions
are about the *protocol*, never about one house — they have to hold for a single Sonos One
SL on a desk as much as for five rooms with a soundbar, so nothing asserts a room name, a
group count, or a populated queue.

**The discipline that makes the fake trustworthy: capture fixtures, never invent them.**
Everything in `core/src/testFixtures/resources/fixtures/` was recorded verbatim off a real household
and redacted for identifiers only. The invented payloads these replaced had no `_objectType`
anywhere, no `queueVersion`, no `availablePlaybackActions`, and no stereo pair — a fake
built from what one *assumes* the protocol looks like tests those assumptions against
themselves and passes for the wrong reasons. **That includes the header a test pushes an event under**, which no
fixture file holds: a Beam's `hdmi:1` event names no player (`playerId: null`, unlike
`playerVolume:1`'s), a test helper supplied one, and the feature did nothing on any real device
until the Shield showed it. `FakePlayer.pushFromPlayer` sends an event the way such a player does. `x2rock`'s `docs/architecture.md` holds more
verbatim captures worth drawing on.

And the check that stops a green CI quietly becoming a lie: the live suite compares the
structure a real player sends *now* against the recorded fixtures, and fails when they
diverge. That is the signal to re-capture.

**Mutation-check any test about behaviour before trusting it.** Five tests in this repo have
been written that could not fail — an assertion allowing the null it always got, a
`coerceAtMost(0)` compared against 0, a fake that refused nothing, and twice a captured
topology with one player per group making anything about *members* unreachable. In every
case the assertion read correctly and the input made it moot. Break the code the test names
and confirm it fails; nothing else reliably catches this.

### Households
A household is discovered, not configured: SSDP's reply carries
`HOUSEHOLD.SMARTSPEAKER.AUDIO`, which is the full id the WebSocket needs. One network, one
household in practice; a room missing from a listing is more likely a player that did not
answer discovery than a second household.

**There are at least three names for the household id and only the long one works.** It has
two segments either side of a dot — `Sonos_xxxxx.yyyyy`. The short `Sonos_xxxxx` is a
different string, not a prefix to be completed, and the Control API answers it with
`ERROR_INVALID_OBJECT_ID`. Verified against a player that accepted the long form seconds
later in the same session.

| Source | Field | Form |
|---|---|---|
| SSDP | `HOUSEHOLD.SMARTSPEAKER.AUDIO` | long — use this |
| SSDP | `X-RINCON-HOUSEHOLD` | short — refused |
| mDNS `_sonos._tcp` TXT | `mhhid` | long — use this |
| mDNS `_sonos._tcp` TXT | `hhid` | short — refused |
| TLS cert SAN | `urn:sonos:hhid:` | short — refused |
| `http://<ip>:1400/status/zp` | in the body | long — use this |

That last row matters: the long id is available over cleartext 1400 from a bare address, so
a player found by any means at all can be connected to without SSDP.

**A seed with no household reads it from `/status/zp`** (`Upnp.householdId`, the
`HouseholdControlID` element). It used to send a deliberately malformed frame and read the
household out of the error reply, and a One SL on p20.96.1 simply ignored that — no reply at
all, so a connect that depended on it hung its timeout and failed. It went unnoticed because
SSDP always supplies the household. The probe is gone; do not bring it back as a fallback.
`FakePlayer.ignoreHouseholdProbe` reproduces that One SL.

## Anything on the network can change this household

Other controllers — the Sonos app, a voice assistant, another copy of this one — regroup,
retarget and retransport at will, and the app has to keep working through changes it did
not make rather than merely not crash on them.

Two things this costs, both of which were wrong once:

- **A regroup mints new group ids.** Subscribing only at connect left every group formed
  afterwards listed but permanently frozen — no playback, metadata or volume subscription
  at all. `SonosHousehold` tracks group id to the coordinator it subscribed on, and brings
  subscriptions back in line on every `groups:1` event; unchanged groups are left alone.

  Two things that catch-up must keep doing. It is **serialised under a lock and reads the
  topology itself** rather than taking the event's copy: it suspends for three round-trips
  per group, so two events close together used to interleave, and whichever run held the
  older snapshot would see the newer group ids as "gone" and drop them. And a failed
  subscribe is **retried, with the reconnect's backoff**, until the group is subscribed or
  leaves the topology, because leaving it unsubscribed recreates the frozen-room bug with
  nothing to retry it until the next topology change, which may never come. That case was
  seen, not imagined: Kitchen, unplugged and plugged back in, was listed as its own group
  throughout and sat frozen. Setup subscribes groups one by one for the same reason — Sonos
  lists an unplugged speaker for minutes, and one unreachable coordinator used to fail the
  whole session.
- **A group is spoken for by its coordinator alone.** Group-scoped events (`playback:1`,
  `playbackMetadata:1`, `groupVolume:1`, `sleepTimer:1`) are taken only from the socket of the
  group's coordinator. A member leaving a party answered a subscription to its *new* group with a
  snapshot whose header still named the group it was leaving, with no track; applied by header,
  it blanked the host's row and pane to "Playing" for good, because a host keeps its group id
  through a party and its split and so is never subscribed afresh (Shield, 2026-10-17;
  `MemberEventTest`). Ids seen through a cycle: members get a new id each time, the host none.
- **A selected group can simply cease to exist.** `HomeViewModel` follows the *speakers*
  rather than the id: whichever group now holds them is the same room to a listener, even
  though it is a different group. Only if that fails does it fall back to the sorted first.

When writing a test for this, a derived topology must change the host group's **id**, not
just its membership — `FakePlayer.groupedTopology` does. Keeping the id made the fixture
look right while testing nothing, because a household that never re-subscribes passes it.

## Known Gaps / Deferred Work

**The catch-up list, `docs/punch-list.md`, is closed** (2026-10-09): every item is done or decided
out, and the outside reviews are answered in it. Its head gathers what is still open — checks owed
on hardware, one open question, things not built — so start there before picking up new work; the
items below are the standing notes it does not repeat.
- **The media session is not advertised for a soundbar on its TV input.** The Google TV home
  screen carries a card for every active session, and for a room with no track metadata it
  rendered ours as "Unknown · x2rock · Unknown" — a launcher row saying nothing, for a source
  the television already controls. `setPresenting(false)` deactivates the session there.

  **Idle is not that case and keeps its session.** A play key, or a voice "play in the living
  room", has to land somewhere, and idle is exactly when one is worth acting on. Its title is
  the room's own name, because an empty `METADATA_KEY_TITLE` is what the launcher renders as
  "Unknown" in the first place.

  Note this session handles **transport only** — it never takes the volume keys. See "The
  remote's volume keys are not ours to take".
- **No foreground service, and probably no need of one.** The sockets are held by an
  application-scoped `CoroutineScope`, which survives Activity changes but not the process
  being reclaimed; the cost of reclaim is a reconnect on next launch, which works.

  If one is ever added, **do not declare `foregroundServiceType="mediaPlayback"`** — an
  earlier version of this note suggested it, wrongly. This app plays nothing: the speakers
  do. That type is for apps that are themselves playing, and claiming it would be claiming
  a capability x2rock does not have. `connectedDevice` describes what this actually is
  (interacting with devices on the network) and is the type to reach for; check its
  permission requirements against current docs before relying on it.

  Whether a service is needed at all depends on the mechanism. Anything the system starts
  on demand — deep links, `MEDIA_PLAY_FROM_SEARCH`, a broadcast receiver like
  `ChannelSyncReceiver` — needs none. Only hosting a listener the outside world calls into
  does, and that is a design decision, not a gap to be filled by default.
- **SSDP is not always available, so mDNS is the fallback.** On an office LAN a raw `M-SEARCH`
  drew **zero replies** while mDNS worked normally and TCP 1443 was reachable throughout, so
  a first run there found nothing. When SSDP's window draws nothing, `SonosHousehold` asks
  `MdnsDiscovery` — `NsdMdnsDiscovery` in `:app`, over `NsdManager` — for `_sonos._tcp`, whose
  TXT record carries `uuid`, `location` and `mhhid`: a complete `DiscoveredPlayer` with no
  scanning (`Discovery.fromSonosTxt`, tested against the office One SL's record). Never take
  `hhid` for `mhhid`; it is the short form the Control API refuses. The port-1443 connect-scan
  is still not built, and nothing has needed it. **Run on an Android device 2026-10-06**: a moto g
  (Android 15) at the office, launched with SSDP switched off — the office network answers SSDP now,
  so the fallback is otherwise never reached there — found Media Room through `NsdManager` and
  connected. A debug build does that on demand: `adb shell am start -S -n
  com.rahga.x2rock/.MainActivity --ez debugMdnsOnly true` (see `DebugSwitches`).
- **Tested on two devices**, and the stricter one raised nothing. An NVIDIA Shield
  (Android 11, Ethernet) and a Google TV Streamer (Android 14, API 34, **Wi-Fi**). The
  Streamer was expected to surface newer local-network policy first and did not: the
  WebSocket, SSDP over Wi-Fi and cleartext UPnP to `.local` all worked unchanged, with no
  crash and a 4.3s cold start. What it *did* surface were two UI faults and one wrong
  assumption, all recorded below. Its ADB is wireless-only and needs pairing — plain
  `adb connect <ip>:5555` is refused until Wireless debugging is switched on.
- **A custom `X509TrustManager` is still required**, because players present a leaf-only
  chain whose root is not in any store. Hostname verification is *not* relaxed — see
  `LanHttp` for why, and do not "simplify" it by adding a permissive verifier.
- The queue is not pushed: UPnP eventing needs the player to connect back to us, which is
  deliberately not used. While the queue screen is open it re-reads when the room's
  `queueVersion` moves. Edits moved it while idle or playing the queue (home, 2026-10-01),
  but that is not a guarantee while playing a station: x2rock measured an unchanged pushed
  version, and Kitchen's direct-stream capture on 2026-10-11 saw no playback/metadata event
  after an inactive-queue removal. External edits can remain stale until the screen is
  reopened; this screen's own edits have a fallback read. See `docs/lan-transport.md`.
- **Android's XML parser accepts no Xerces features, and the JVM's does.** `setFeature` for
  `disallow-doctype-decl` threw on the Shield, so no UPnP reply parsed there while the live
  suite, on the JVM, passed. `Upnp.parse` guards DOCTYPEs by hand now. The lesson is
  wider than the parser: a platform API the JVM also has can still behave differently on the
  device, and only the device shows it. Anything that reads a UPnP reply needs one look on a TV.
- **`Upnp` keeps its own read timeout, and must.** It is handed the WebSocket's client,
  where `readTimeout(0)` is right because a subscription is meant to sit idle — and fatal
  for request/response, where a player that goes quiet mid-answer would hang the caller
  forever. Not hypothetical: switching a soundbar's group to its HDMI stalls AVTransport
  across the handoff.

## Where the UI is going

The reference is the sibling project's Quickshell widget: a room list where each row carries
cover art, room name, state, what is playing, inline transport and a volume slider — and for
a soundbar, three lines rather than two (room and state, then the input format, then "TV
Audio").

The room list carries art, the soundbar's three lines, a TV badge and a placeholder for the
TV input.

**The art slot is always 56dp wide, occupied or not**, so every room name starts at the same
x — rows used to shift left without art, which at three metres reads as a different list
rather than a missing image. It holds the cover (or a station logo for radio); for a TV input
it holds a television glyph, which is the app's invention because the player really does send
`images: []` for `TV Audio` — an honest invention, since it names a source we do know. A
station with no logo of its own gets a radio glyph on the same principle.

**Idle is not one state, and the slot follows the source rather than the transport.** A room
that is idle because it was paused or stopped keeps whatever its source earns — the station
logo, the radio glyph, the television glyph — because that room still *has* a source and
saying so is useful. What leaves the slot transparent is a room with nothing loaded at all,
where a grey square would assert something that is not there. The rule was originally written
as "an idle room leaves it transparent", which was true when idle and sourceless were the same
thing and is too broad now that `isRadio` and `onTvInput` outlive playback.

**The TV badge appears only while the room is *not* on that input.** It answers "which rooms
have a television attached", which nothing else says; once the room is actually on the input,
the art tile carries the same glyph and the third line reads "TV Audio", so a badge would be
the third telling of one fact. Dim, because it is a capability rather than a state.

**This device's own TV room wears the badge in the accent colour, always** (T-33, 2026-10-17), on
its input or not: there it says something the art tile does not — *this* television's room — and
it moves when "This is my TV" is pressed, which is that press's only sign it took. The other rooms'
badge is drawn in a neutral dim colour so the two cannot be confused. A room whose soundbar reports
nothing on its HDMI gets no badge at all (the owner's Guest TV, rarely plugged in): keep it so.

**Line-In is offered beside TV Input, and is untested on hardware** (2026-10-10). A room whose
player reports `LINE_IN` gets a Line-In row in the panel's Source section, sending the Control
API's own `playback:1 loadLineIn` with that player as `deviceId` — x2rock saw a Beam refuse it as
"player does not have line-in", which is the evidence it is checked per player. No player in either
household has a line-in, so the capability string is Sonos's published one, never captured, and
what a room *on* its line-in reports is unknown: nothing recognises it, so there is no art glyph,
no third line, no "(current source)". **Any release notes must say Line-In is untested** until a
real Port, Five or Amp has taken it (PUNCH P-14).

**Since T-21 (2026-10-12) it is a beta feature**, shown only with **Settings → Beta features** on,
as are a portable's battery line and the item menu's Sonos Favorites actions (the player pane's Save
favorite left Beta on 2026-10-17, after saves on the Shield), and each says "Beta" where it appears (the menu's, which are rows among others, do not). With the switch on, every
room is also offered **another room's** line-in ("Line-In on Den"), as Sonos's own steps imply but
never state. Anything else that has never run on a device goes behind the same switch. **Send a
report**, under it, is a QR code to a prefilled public GitHub issue: models, versions, capabilities,
what a line-in room reports, the last refusal; never an id, address or serial.

SoCo (the Python library) corroborates the command, not the capability: a UPnP event it captured off
a player on line-in (`tests/data/data_structures_entry_integration/source_linein.xml`, read
2026-10-10) carries `AVTransportURI` `x-rincon-stream:<player>` with metadata titled **`loadLineIn`**,
class `object.item.audioItem.linein` — that input was set through this same Control API command,
presumably by the Sonos app. SoCo itself switches over UPnP instead, `SetAVTransportURI
x-rincon-stream:<player whose input>` to the playing speaker — the TV input's pattern exactly, and the
fallback if `loadLineIn` ever disappoints. It recognises line-in by `GetPositionInfo`'s `TrackURI`
prefix, and checks no capability at all, so `LINE_IN` stays unconfirmed.

### The remote's volume keys are not ours to take

**Never call `MediaSession.setPlaybackToRemote(VolumeProvider)`.** It is the one API that
would route the remote's volume keys to the selected room instead of the device's own
output, and it must not be used here. An earlier version of this file proposed it as the way
to do per-room volume; that was wrong.

The case that settles it: audio playing through the streaming device reaches the speakers
over HDMI/ARC, so the remote's volume keys are *already* controlling exactly what is audible.
Someone must be able to pick up the remote and mute or turn down what they are hearing,
immediately, without x2rock in the way. If the keys were routed to "the selected room" they
would land on whatever this app happens to have highlighted — possibly a different room
entirely, possibly the same room by a path that does not affect the ARC stream. Either way
the volume key stops doing the one thing everybody expects it to do. This is the same
principle as the household section above: other things drive this system and must not be
prevented from doing so.

`dumpsys media_session` is how to check the app is behaving. Ours should always read
`volumeType=1` (`PLAYBACK_TYPE_LOCAL`). A `volumeType=2` there means someone added a
`VolumeProvider` and the keys have been hijacked.

Per-room volume lives in the player pane instead — the header's pill for the room, one pill per
speaker for a grouped room — on left/right, where it is asked for explicitly rather than
intercepted.

**Taking them even in the panel is not possible on a CEC setup, as it turned out.** Fine
control on the remote's volume keys while a panel row had focus was tried on the Streamer
(2026-10-05) and backed out: the row never received a key. Google TV hands volume to HDMI-CEC
before any app sees it, and the soundbar on the other end — Bedroom's Beam — moved 2 per press,
which at first looked like the app applying each press twice. The keys already control what is
audible there, which is the point of this section; a device without CEC volume would deliver
them, but none in this house does that has been checked, and the Shield's showing up in
`getevent` says only that the kernel saw them.

### The room panel

**The room panel has two halves, each with its own button in the pane** (2026-10-17): **Group** opens
grouping — Everywhere, Rooms in this group, Rooms not in this group, Presets — and **Configure**, in
the place the Sleep timer button had, opens Source, Sleep timer, Sound and Room settings, in that
order. Configure's button shows a running sleep timer's end time, as the Sleep button did. Moving a
room between the lists hands focus to the row that takes its place, not back to the top.

`RoomPanel` is everything a room can be told to do, on one surface. It replaced a context menu
that opened two further dialogs — `RoomContextMenu` led to `GroupPickerDialog` and
`SeparateRoomDialog` — so three surfaces became one.

**Select on a room enters the player pane; it does not open the panel** (2026-10-07). On a TV,
Select on a list item means "go into this", and a dialog in front of the player was the opposite
of what that press asked for. The panel opens from the pane's **Group** button, which a viewer can
see (or a tap on the selected room, by touch). A long press and Menu on the room opened it too,
for those who knew them; the owner saw no need for them and they were dropped (2026-10-16). They
now pick the room up to move it in the list (2026-10-17, below). Closing
it returns focus to whichever opened it. Group is in the pane's row rather than above or below the
room list, for the reason in "Nothing that acts on the selected room" below.

**A Sub and surrounds are in the Sound section too** (T-16, 2026-10-11), only for a room whose
`zones:1` members name them (`SW`, `LR`/`RR`): Sub on/off and level, surrounds on/off and their TV
and music levels, −15..15, as the Sonos app's Sub audio and Surround audio. Never offered from an EQ
probe: the Sub types answer on a One SL with no Sub.

**Crossfade lives in the panel's Sound section**, not the pane: Group needed its place in the row,
and it is a room setting, which the Sonos app also keeps a level away. The row scrolls
horizontally anyway, since a running sleep timer's label can still push it past the pane.

The shape follows the sibling project's Quickshell group selector (`BarWidget.qml`, the
`groupingPanel`), which is worth reading before changing this:

- **Rooms in this group** — the room itself, plus whatever is grouped with it. Drawn even when
  it is alone. Select on a member takes it out (a `leave` mark), on every member except the
  coordinator: the coordinator *is* the group, so removing it would dissolve the group rather
  than free the room.
- **Rooms not in this group** — every other group; Select brings it in (a `join` mark).
  "Every room is in this group." when there is nothing left.

**The panel carries no volume** (2026-10-17, the owner's call): it is for which rooms play
together, and Select moving a room from one list to the other is its whole gesture. Rows had a
level on left and right, and "Even out the levels"; both are gone. A grouped room's speakers are
set in the player pane, each as the header's own pill (`VolumePill`). No button sits after a
pill: Right on it is louder, so a button there could not be reached by remote.

The panel opens focused on **the room's own row**, not on Party: the row most likely to be
wanted, and one press from either neighbour.

**A volume press climbs like a seek press** (2026-10-17, `VolumeBurst`): within a burst, 2, then 3,
then 5; a pause of 1.5s or a turn the other way starts again at 2. Quiet is finer whatever the
burst: at most 2 below 10 and 1 below 4, judged by where the level is going. No cap at the top,
where 98 and 100 sound the same. The pills send a direction; the view model sizes the step.

**The player pane scrolls only from the places row down** (2026-10-17): above it nothing moves,
and from there only as far as the focused control needs (`LowerZonesOnly`). The TV's own rule
pivots each focused control towards the upper screen, which pushed the art and title off the top
the moment the places row had focus. Back on the transport, the pane returns to the top.

**Two hoists have been removed from `sortGroups`, and nothing should reintroduce one.** The
list was strictly alphabetical, Sonos's own order — Sonos had playback, EQ and home-theatre
settings but nothing positional. "If it doesn't let us reorder in the Sonos app, I don't think we
should either."

**The Sonos app has since gained room sorting** (seen 2026-10-11, app 90.00): Actively playing (its
default), Frequently used, Alphabetical, and Pin, and lets rooms be reordered. So, by the rule just
quoted, **this list can be reordered too** (P-20, 2026-10-17), in the list itself, with no menu: a
**hold** (of Select or of Left, or Menu) on a room picks it up — its lines read "Moving · Up/Down, Select" and "Left: play everywhere" —
Up and Down carry it, and Select or Back puts it down. **Left on a picked-up room offers to play
everywhere from it** (2026-10-17, asked for by the owner): the row asks "Play everywhere? Select" over "Left: also even volume";
Select confirms, a second Left confirms *and* first sets every speaker to that room's level (before any
room joins, so nothing joins loud: `partyMode(evenVolume = true)`), Back backs out of all of it. With
one room in the list — a single speaker, or the house already one group — there is no hold at all. Preset sorts (Alphabetical / Playing first in a
menu) were built first and dropped by the owner the same day: two fixed orders are not what is wanted,
and a hold that must be *kept* held is not a gesture on a remote whose select key sits inside the
D-pad — a hold only starts things. **Placed rooms come first in their order; every room never placed
follows alphabetically** (`sortGroups`, `movedRanks`). A move places the moved room and everything
above it as shown, so nothing jumps. Ranks are **player** ids, as the TV room is; a group stands where
its best-placed speaker does. The order is this device's (Sonos's own cannot be read over the LAN) and
is used by the list, the panel's rooms to join and the home-screen channel; the first-launch default
stays alphabetical. **Settings → Reset room order** appears only once a room has been placed.

- **Favourites**, a second sort rank with a star in the list. The owner's verdict on the
  feature it mirrored: "probably the least used Sonos feature on the planet." If it returns
  it belongs as a star off to the side, not a row in this panel. Sonos *content* favourites
  (`favorites:1`, the Favorites screen) are a different thing entirely and remain.
- **A pinned "primary room"**, which had quietly become the second of two answers to "which
  room does this device belong to" — and the louder one, because `defaultSelection` is just
  the top of this list and [TvSoundbar] detection only moves a selection still equal to it.
  Setting a primary therefore *switched TV detection off* for good, silently. Naming the
  television's room says the same thing better and without disagreeing with Sonos, so
  "This is my TV" is the only such control left.

Two things sit outside the widget's version, in the order asked for:

- **Party is first** — labelled "Play everywhere" — because it is the one press that answers "put this everywhere". It
  hosts from *the room the panel was opened on* — `partyMode(hostGroupId)` — since party
  mode hinges on a source, which is the whole reason it moved off the sidebar.
- **Source, then Room settings.** Source is for a room with a soundbar (TV Input) or a line-in.
  TV Input is the one row that changes what the room is *playing* rather than which rooms are
  listening, and it sits above "This is my TV" because it is a thing to do where that is a thing
  to state once and never touch again. Room settings is every room's since T-08 (2026-10-10): the
  room's own speaker's **Status light** and **Touch controls** (the Sonos app's Button Control,
  so On is unlocked), read with its tone; a **volume limit** and a **battery** as lines where there
  is one (the limit cannot be set unsigned; battery is untested against a portable); for a room
  with a soundbar, the soundbar's **Remote feedback light** and **IR repeater**, written to the
  soundbar itself; then "This is my TV". **About this system** (Settings → Household) lists every
  speaker and whether an update waits, by `DownloadSize`; nothing here ever applies one.

Settings sits at the **foot** of the room list, past the last room. At the top it was the first
thing Compose found, and focus fell onto it three different ways. Moving it exposed what it had
been hiding: Android's automatic first focus now lands on the first *room*, and since the list
selects on focus, that overrode "This is my TV" (Shield, 2026-10-07). So a row selects its room
only after `HomeScreen` has placed focus itself (`focusPlaced`); before that, focus on a row is
the platform's, not the viewer's. There is no collapse control:
the list is the app's spine, and hiding it bought nothing on a television. Reordering rooms
would live in the list too, if Sonos had an order to reorder — it does not.

**A row selects its room only when a key put focus there** (2026-10-11): an Up or Down within the
last half-second, or the row already being the selected room. Focus can reach a row with no key
behind it — a focused control leaving the composition makes Compose drop focus and the platform put
it on the first focusable thing — and that focus is handed back to the selected room's row instead.
Without this the Shield opened on Bedroom every launch, over the stated TV room: the pane's first
focus sat in Living Room's music pane, which gave way to the TV pane as its metadata arrived (logcat).
`FocusChoreographyTest` covers it on the emulator.

**Nothing that acts on "the selected room" may sit above or below the room list.** The list
selects the room focus rests on, so walking up it to reach a button selects every room on the
way: Browse and Search were first put at the top of the list, and Browse opened "in Bedroom"
from a walk that began on Kitchen (Streamer, 2026-10-06). They live in the player pane's button
row instead, where the room is the one already chosen — right, then down, from any room.

**Do not disable a row to say it is already true.** A disabled `tv-material3` `Button` still
takes focus and draws no highlight, so pressing down onto the TV Input row while it was
already the source left the remote sitting on an invisible row with nothing but Back to
press. Seen on the Streamer. Re-selecting the current input is harmless anyway, and is the
obvious thing to press when the TV audio has dropped out.

### The television's own soundbar

`TvSoundbar.detect` finds it: among the rooms that *have* an HDMI input, the one currently
*on* it. In this household three rooms have a Beam and only one is fed by the Shield, so
"has a TV input" is not the answer by itself.

It is a heuristic and cannot be otherwise — Android will say an HDMI output exists but not
what is on the other end, and CEC is not available to an ordinary app. Ambiguity (two
televisions on) and absence (none on) both answer null rather than guessing, which is why
the answer is *remembered* once found: the device is bolted to one television.

It stores a **player** id, never a group id, because a regroup mints new group ids and the
soundbar is what actually stays put.

**It changes where the app opens, never the order of the list.** Sonos sorts rooms
alphabetically and offers no ordering of its own, so hoisting a room here would make this
list disagree with every other controller in the house to no purpose. The list matches
Sonos; the television's room is simply what is selected.

**And it has to be correctable, because the heuristic answered confidently and wrongly.**
On the Streamer it stored *Living Room* — the other end of the house — and then kept it,
because "never overwrite an answer" was meant to protect a good answer from a later
ambiguity. What produced it was an ordinary moment: Bedroom had been grouped with Kitchen
for a test, so it was playing music rather than sitting on its input, which left Living Room
the only soundbar on a TV input and therefore unambiguous. Any evening where one room plays
music manufactures the same false certainty, and the two-televisions guard only holds if
both happen to be on at the instant of observation. There is a startup version of this too:
the first check runs before every room's metadata has arrived, so arrival order alone can
decide it.

So `RoomPanel` offers **"This is my TV"** for any room with an HDMI socket, and a stated
answer wins — the detector only ever fills this in while it is empty. Detection supplies a
default; it does not get the last word. Like detection it stores a **player**, so pressing
it on a group names the soundbar in that group, not the coordinator, which on a grouped
Kitchen+Bedroom would otherwise name a One SL as the television's.

It is **king of the hill**: naming a room moves the crown off whichever room held it, so
there is no un-naming row and the label never changes. A wrong answer is corrected by naming
the right room, not by clearing the wrong one first.

The first-launch case needs care, because detection lands a moment *after* the first room
is auto-chosen. Moving the selection then is right, but only while it is still the app's
own choice — and intent cannot be read from `selectGroup`, because the sidebar selects on
*focus*, so focusing the current row programmatically is indistinguishable from a viewer
pressing towards it. What is decidable is whether the selection is still what
`defaultSelection` would have picked; if so it moves, and if the viewer has gone elsewhere
it does not.

### The ten-foot pass (2026-10-06)

A review against Sonos 27's app and Google's TV guidance, applied across every screen and
checked on the Streamer. What it settled, so it is not undone by accident:

- **One focus language.** A focused button is filled with the primary colour; a focused card
  wears a 3dp border in that same colour (`appCardBorder`), and does not grow. tv-material3's own
  card focus — a slight scale and a lighter grey — read as a second language and was hard to find
  across a room; the scale went too (2026-10-09), since it made a focused row overhang its list.
- **The pane's places are icons, each named under it while focused** (`Named`, 2026-10-09):
  Queue, Browse, Search, Group and Configure, as a TV's player controls name theirs. As
  labelled buttons the row overran the pane and scrolled, leaving "Queue" cut in half at its edge.
- **The art names its service while the volume or seek bar has focus** (2026-10-09): the service's
  logo over the cover's bottom-left fifth, from the `service` every metadata event carries. Only
  square logos are published (Sonos's list has one tile per service, no wordmark), so it sits on
  the art, and only on those two controls, so it never covers the art otherwise.
- **The pane keeps one shape down to and including the seek bar's slot** (2026-10-10): the tile's
  slot is held blank where there is no art, and the bar's slot is laid out blank where there is no
  duration — a station, a TV input, an empty room — so walking the room list moves nothing above
  the transport. Below it each source has its own rows. Asked for after Bedroom, Dining Room and
  Kitchen each put the controls at a different height.
- **Next and Prev name where they go, between the seek bar's times, only while focused**
  (2026-10-10). Up next is `metadataStatus.nextItem` where the player names one — a queue does, a
  station does not, and nothing is drawn then. **The player never names a previous track**:
  `playbackStatus.previousItemId` is history (what played before, an opaque id, set even on a
  queue's first track), so "Previous" is read from the queue over UPnP (`previousInQueue`: slot from
  `GetPositionInfo`, one browse), once per track and queue version, only where `canSkipToPrevious`
  — Prev on this firmware goes to the previous track even 11s in, not back to the start. First drawn
  as a line under the transport, Up next pushed the places row past the safe area on a two-line
  title (Shield); the time row's middle costs no height, and showing each only on its button keeps
  the pane quieter, as the service logo shows only on volume and seek.
- **Full screen is a place in the pane's row** (`NowPlayingScreen`, 2026-10-10): the art as tall as
  the screen, the track, the room, the clock, a progress line, nothing to press. Any key but Back
  closes it too, on the key's **release** — closing on the press left the release to click the
  pane's button underneath. It has its own view model on `RoomTarget`: the pane's `PlayerViewModel`
  is told its room by `HomeScreen`, which is not composed meanwhile, so it would not follow a
  regroup. Kept awake only while the room plays; the content drifts a few dp a minute.
- **A bar composed after the last report starts from `livePosition`**, the report plus the time
  since it. From the report alone, the pane on the way back from any screen sat behind by however
  long the player had been quiet — 0:26 against 1:30 on the Shield.
- **The seek bar seeks only where the player says `canSeek`**, which is per content, not per
  service: an iHeartRadio artist station's track carries a duration and refuses a seek with
  `ERROR_DISALLOWED_BY_POLICY`, where the same service's on-demand tracks seek fine. The player
  reports it with every status, so there it is a plain progress line and not a focus stop. What
  did not work floats over the foot of the pane as a `NoticeBanner`, as the home screen's does
  over the room panel, rather than as a line among the rows.
- **A control the pane does not draw is not acted on from anywhere** (2026-10-10). The remote's
  media keys and the media session's controls come through the same `PlayerViewModel` functions
  as the pane's buttons, and each checks the player's `availablePlaybackActions` first: Next on a
  station used to send the skip and answer "Couldn't skip: ERROR_DISALLOWED_BY_POLICY", a press
  on a button that was not there. Play with nothing loaded is the same; idle with content is not.
- **A refusal is said in words where the code has been met** (`plainRefusal`, 2026-10-10):
  `ERROR_DISALLOWED_BY_POLICY` "this source doesn't allow it", `ERROR_PLAYBACK_NO_CONTENT` "nothing is
  loaded", `ERROR_ACCOUNT_INVALID_ID` "it isn't available from this system's accounts". Any other
  code stays as the player sent it: a code nobody has seen cannot be worded truthfully, so add one
  here only with the case that produced it.
- **Alarms are the household's, so they live in Settings** (`AlarmsScreen`, 2026-10-10), every
  room's in one list: "06:45 · Kitchen", days, sound, volume, On/Off; Select arms or disarms. Times
  are the household's clock, said at the top with its time now, and **never converted** — with no
  timezone set it runs UTC, and the screen says so. A new alarm is one step to a screen (room, time,
  how often, chime or a favourite, then volume) and is made armed. Nothing pushes alarms, so the list
  is read on opening and after each change made here. **Menu or a hold on one changes or removes it**
  (T-19, 2026-10-11): it opens on its summary, each change is its own step and comes back to it, Save
  writes a fresh read back whole with only those fields changed, and Remove takes two presses, as the
  queue's Clear. An alarm already gone (`DestroyAlarm` 800) counts as removed.
- **A screen that loads fast asks for first focus once it is resumed**, not before: during the
  navigation's fade the home screen is still composed and its Settings panel hands focus back to
  its button, and when it left, focus fell to Back (Alarms on the Shield). Lists that load slowly
  were spared by timing alone.
- **A stream by its address is Radio's "Play a URL"** (2026-10-10), one press up from Popular, which
  keeps first focus: the directory is the cheap path on a remote and typing the exception. It plays
  as a directory station does, named by its **host** (x2rock's rule: the path is a bitrate slug), and
  takes http and https only, since the speaker fetches it; typed without a scheme it is taken as
  http. The keyboard is the URL one; with it up, its GO key sends — the remote's keys are the
  keyboard's then, so a scripted Enter does nothing.
- **A pin at the right end of every Browse header says what starting something does next**
  (2026-10-15, `StayPin`, `AfterPlaying`): Browse, Radio, Music Services and a service's pages, Apple
  Music. Outlined, the default, it returns to the player, where what started is shown; filled, it
  stays in the browser, so the next station or track is a press away and Back leaves. Named while
  focused by what a press will do — a verb, not the state: "Hold" while off, "Release" while on.
  One per-device rule for everything that plays, Search included, so it does not depend on how the
  viewer got there. Radio carries Browse's now-playing bar. Browse's header reads Music Services, Radio.
- **The type floor is 14sp**, set once in `TenFootTypography` rather than call site by call
  site: tv-material3 puts most secondary text at 12sp, Google's absolute minimum, and that was
  the text people read. Each style keeps its rank, so a call site's choice still means what it did.
- **What a room is doing has one rule** (`RoomActivity`): Playing, Paused, Stopped, Nothing
  playing. The list, pane and panel used to disagree — a stopped stream read "Smooth Jazz" in
  the list and "Idle" in the panel. "Stopped" is a stream a pause left IDLE; its button already
  says Stop.
- **Every screen opened from a room names it** ("in Kitchen", `ScreenHeader`). With five rooms,
  playing into the wrong one is the classic mistake, and nothing said which room a list was for.
- **Every list row is `MediaRow`**: the 56dp art slot held whether or not there is art, the
  focused title scrolling rather than ellipsizing. Back from a screen returns focus to the button
  that opened it, not to the room.
- **Search is one field across every service** (`SearchViewModel`): two categories per service,
  signed-in services first, sections filling in as each answers, on submit only — per keystroke
  would be thirty requests a letter. An album or artist found there opens in its service's
  browser rather than being played blind.
- **Preferred services lead** (`PreferredServices`, Settings → Preferred services, 2026-10-09):
  an ordered tier this device keeps, above the signed-in rule, in Search and Music Services alike.
  The Sonos app has a single "Preferred Service"; a tier was asked for, since nothing is gained by
  ranking a household's second service by accident. Nothing is hidden. Search asks preferred
  services first and holds its first focus until none that would rank above the first section is
  still out — focus used to go to whichever service answered first, which on a slow box was Hype
  Machine, with the household's own services sorting in above it, off-screen.
- **Recent searches** (`RecentSearches`, 2026-10-11): before a search, this device's last eight
  terms are rows under the field; a press searches again, Menu or a hold forgets one, and focus
  then goes to the next row (or the field), never left to fall. The Sonos app keeps a search
  history; on a remote the typing is the cost. **The D-pad leaves the field** once the keyboard is
  closed (`SearchField`): Up and Down always, Right and Left from the end of the text — a text field
  keeps every arrow for its cursor otherwise, and the Search button and the results were
  unreachable (P-19, Shield).
- **Presets** are this device's (`PresetStore`): players, each one's level, and a Sonos favourite,
  saved from the room panel and played from Browse. Players, not groups, for the reason the TV
  room stores a player. Applying one sets levels **before** it starts the music, so nothing starts
  loud. Music that is not a favourite is not kept, and saving says so.
- **A book's chapters and a service's playlists are the item menu's** (2026-10-10). On a book,
  **Chapters** lists them as a Browse level, opening on the chapter its service has the listener
  in, marked "Resumes at"; that one carries on from the place, any other plays from its start, and
  pressing the book still resumes. On a track, **Add to playlist…** lists the account's playlists
  that may be written — found by the collections' own `userContent`/`readOnly`, never by guessing at
  titles — and adds at the end. Shown only where the account has such a place (Deezer does; YouTube
  Music's SMAPI refuses outside callers). Making or deleting a service playlist is not offered:
  creating a service's content belongs to the service's own app.
- **Sonos favourites are added and removed here, as the owner asked** (T-31, T-32, 2026-10-16). The
  sibling project declined UPnP `DestroyObject` on the rule that creating or destroying content
  belongs to the Sonos app; this project no longer follows that rule for Sonos favourites. Removing is
  two presses of Menu (or two holds) on a Browse row, as a preset is deleted — it is every
  controller's list — and focus goes to the next row. Adding is the item menu's, and the player
  pane's star for the song playing; both are Beta until pressed on a TV. A duplicate is never made:
  the player refuses one (803). That is a *Sonos* favourite. A *service's*
own favourites — Deezer's Favourite Tracks, Qobuz's — are the account's library in that service,
  and the item menu adds to and removes from them, as the Sonos app's does (asked for, 2026-10-08).

### Browse against the Sonos app (2026-10-08)

**The current comparison, service by service, with testing status and the gaps — attainable and
not — is `docs/sonos-app-comparison.md`** (2026-10-11, Sonos app 90.00 against the Shield).

Compared screen by screen with the Sonos app on a phone; the record is the punch list's "Against
the Sonos app" and `lan-transport.md`'s "A service item". What it settled:

- **A service with a page of its own opens on it** (T-15, 2026-10-11): the manifest's `browse`
  endpoint, read with the account's token — Sonos Radio's shelves, Spotify's and Deezer's personal
  ones — then SMAPI's root rows after them, so nothing the root reached is lost. A shelf's albums
  and playlists open; stations play. See `lan-transport.md` for the 401-and-refresh path.
- **A service opens on its library, never on a keyboard.** Browsing is the cheap path on a remote;
  "Search <service>" is one press up. A searchable service used to open on its search and hid every
  library behind it.
- **Hold or Menu on an item is its menu** (`ServiceItemMenu`), Sonos's actions first and the
  service's under its name. **An `ItemMenu` ignores a select release it did not see pressed**
  (2026-10-17): a hold opens it on the key's repeat, and the release then clicked its first action,
  so the menu closed as it opened and the row underneath, pressed but never released, ignored Select
  until focus moved. Seen on the Shield's sort menu after a cold start; elsewhere timing hid it. **Play next only while the room plays from its queue**, as the Sonos
  app; it is a queue slot, not a flag. **Add to Sonos Favorites** follows Add to end of queue for
  what a queue can hold — a track, an album, a playlist — or **Remove from Sonos Favorites** once the
  item is found among them by its service's id (T-32, Beta). Browse and Search share the menu.
- **An album's tracks carry their numbers; a playlist's do not** (T-24, 2026-10-12), as the Sonos
  app draws them: in a playlist a track's number is its own album's. **A container that lists nothing
  offers no Play or Shuffle** (P-23): SoundCloud's albums say `canPlay` and hold nothing, and the app's
  page for one has no Play either.
- **A household token the service refuses is read again once** (P-32, 2026-10-12): tokens are read per
  connection, and a TV outlives them. See `lan-transport.md`, "A household token can go stale".
- **Save favorite in the player pane saves to household Sonos Favorites** (asked for by the owner,
  2026-10-16). **Out of Beta since 2026-10-17**: saves pressed on the Shield (a release build, so
  `withIdentity`'s `Transformer` ran under R8), a duplicate press answered "Already", and a saved
  track played from Browse. It is offered for identified service tracks with UPnP available, not TV or radio. It captures the selected track,
  reads its real playback metadata, avoids duplicates including modern shortcuts, and reports
  success or refusal without changing playback. This is separate from service-library ratings.
  `CreateObject FV:2` works for a current track; earlier empty-resource shortcut failures did not
  prove otherwise. See `TrackFavorites.kt` and `docs/lan-transport.md` (T-31).
- **A favourite says `REPLACE`.** `loadFavorite` appended an album to the queue without it.
- **The content colour is provided once, at the root** (`X2RockTheme`). tv-material derives a
  transparent container's content colour from `LocalContentColor`, whose default is black, and the
  home screen has no Surface at its root: the pane, the room panel's title and every room name drew
  near-black on the dark theme, and were first fixed one at a time by naming the colour. Do not
  name it again on a new transparent container; the root covers it.
- **Back-to-opener focus has one owner, the player pane** (`startFocusRequester`), which starts on
  the opener from its first frame. Effects meant for later events — the source-change rescue, the
  sleep picker's close — act on changes only (`snapshotFlow … drop(1)`): run on the first
  composition, each took focus to the primary control, which is how Back from Queue showed Browse.

### Focus, and why it is handled as keys

`RoomSidebar` ⇄ `PlayerScreen` traversal is wired with explicit `onKeyEvent` handlers, not
`focusProperties`. Declaring `right`/`left` on the pane or the room `Card` does not govern
the search, because each wraps its own focusable inside the modifier chain it is given:
right-press landed on whichever control lined up geometrically (the rightmost transport
button), and left-press escaped the pane entirely and **lost focus**, which on a remote
leaves nothing to press but Back. Right from a room enters at Play/Pause; left from the
leftmost control of any row returns to the room list.

The household this was developed against is a good test of that layout, because bonded and
soundbar cut across each other: Living Room (Beam + Sub + 2x Play:1), Bedroom (Beam + 2x One
SL), Guest TV (Beam), Dining Room (2x Symfonisk, bonded but no HDMI), Kitchen (One SL).
