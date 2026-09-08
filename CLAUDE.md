# CLAUDE.md

## Project
**x2rock** — A Sonos controller for Google TV / Android TV. The Sonos layer is a plain
Kotlin/JVM library (`:core`) with no Android dependency. See `ARCHITECTURE.md` for the
full module/file map before making non-trivial changes.

A Linux CLI (`:cli`) and its MPRIS daemon lived here until 2026-09. They were removed
because the separate Rust project `rahga/x2rock` (cloned alongside as `../x2rock`) does
the same job better on the desktop: local-first, no account, event-driven. Do not
reintroduce a CLI here without deciding what it does that `x2rock` does not.

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
  not a failure, and only the coordinator's own answer is treated as real. **Nothing polls
  to find out** — unlike the sibling project, which had to: the switch arrives as a
  `playbackMetadata:1` event carrying `htInputFormat`, the same thing that lights the row.
  Measured on hardware: TV audio at ~4-5s, the format settled by ~9s.
- The **queue is not in the Control API at all** (`ERROR_UNSUPPORTED_NAMESPACE`). It lives
  behind UPnP on cleartext port 1400 — see `Upnp` — and is the one thing still asked for
  rather than pushed.
- Docs in `/docs/` mirror Sonos's own reference for the namespaces and body shapes, which
  are the same over either transport. `docs/sonos-auth.md` describes the account flow this
  project no longer uses; it is kept for reference only.

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

An Android SDK is needed for `:app` (`sdk.dir` in `local.properties`, or `ANDROID_HOME`).
**There are no credentials to supply** — no client id, no secret, nothing in
`local.properties` beyond the SDK path. If you find yourself looking for Sonos API keys,
you are working from a stale mental model of this project.

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
```

The live suite is **read-only unless a room is named**, because it may run against a
household someone is listening to; what does change is restored afterwards. Its assertions
are about the *protocol*, never about one house — they have to hold for a single Sonos One
SL on a desk as much as for five rooms with a soundbar, so nothing asserts a room name, a
group count, or a populated queue.

**The discipline that makes the fake trustworthy: capture fixtures, never invent them.**
Everything in `core/src/test/resources/fixtures/` was recorded verbatim off a real household
and redacted for identifiers only. The invented payloads these replaced had no `_objectType`
anywhere, no `queueVersion`, no `availablePlaybackActions`, and no stereo pair — a fake
built from what one *assumes* the protocol looks like tests those assumptions against
themselves and passes for the wrong reasons. `x2rock`'s `docs/architecture.md` holds more
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
`HOUSEHOLD.SMARTSPEAKER.AUDIO`, which is the full id the WebSocket needs (note
`X-RINCON-HOUSEHOLD` is a truncated form and will not work). One network, one household in
practice; a room missing from a listing is more likely a player that did not answer
discovery than a second household.

## Anything on the network can change this household

Other controllers — the Sonos app, a voice assistant, another copy of this one — regroup,
retarget and retransport at will, and the app has to keep working through changes it did
not make rather than merely not crash on them.

Two things this costs, both of which were wrong once:

- **A regroup mints new group ids.** Subscribing only at connect left every group formed
  afterwards listed but permanently frozen — no playback, metadata or volume subscription
  at all. `SonosHousehold` tracks group id to the coordinator it subscribed on, and brings
  subscriptions back in line on every `groups:1` event; unchanged groups are left alone.
- **A selected group can simply cease to exist.** `HomeViewModel` follows the *speakers*
  rather than the id: whichever group now holds them is the same room to a listener, even
  though it is a different group. Only if that fails does it fall back to the sorted first.

When writing a test for this, a derived topology must change the host group's **id**, not
just its membership — `FakePlayer.groupedTopology` does. Keeping the id made the fixture
look right while testing nothing, because a household that never re-subscribes passes it.

## Known Gaps / Deferred Work
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
- The queue does not update by itself: UPnP eventing needs the player to connect back to us,
  which is deliberately not used, so the queue screen re-reads instead.
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

The room list now carries art and the soundbar's three lines. Still missing against the
reference: a TV badge (`hasTvInput` is modelled and plumbed as far as `RoomInfo`, but
nothing draws it), and a placeholder for a TV input, which has no art of its own — the
player really does send `images: []` for `TV Audio`, so anything shown there is the app's
invention rather than data.

**Not** a per-row volume slider: every remote has volume keys. The mechanism that makes
them work is `MediaSession.setPlaybackToRemote(VolumeProvider)`, which tells the system to
send volume keys to the selected room instead of local output. Worth knowing before
building it that the Living Room Beam is on HDMI ARC, so the Shield's remote may already
drive that one room over CEC and the two could disagree.

### The room panel

Click (DPAD centre) on a room opens `RoomPanel`: everything that room can be told to do, on
one surface. It replaced a context menu that opened two further dialogs — `RoomContextMenu`
led to `GroupPickerDialog` and `SeparateRoomDialog` — so three surfaces became one.

The shape follows the sibling project's Quickshell group selector (`BarWidget.qml`, the
`groupingPanel`), which is worth reading before changing this:

- **This room** / **Playing together** — the room itself, plus whatever is grouped with it.
  Drawn even when it is alone, because its level belongs here either way. A `leave` target
  on every member except the coordinator: the coordinator *is* the group, so removing it
  would dissolve the group rather than free the room.
- **Add another** (or **Play together with** when the room is solo) — every other group,
  joining this one. "Every room is in this group." when there is nothing left.

**Every room row carries a level, on left and right.** Nothing else in the panel uses those
directions, so they were free to take. A member row moves that *speaker* (`playerVolume:1`);
a joinable row moves that whole *group* (`groupVolume:1`), because a row in that list stands
for a group and may be several rooms. The two stay in step with no arithmetic here: Sonos
scales a group's members when the group moves and moves the group's average when a member
does, and both come back as events. Draw the bar in `LocalContentColor`, not a fixed colour
— a focused button's container *is* the primary colour, so a primary bar vanished on the one
row being adjusted.

The panel opens focused on **the room's own row**, not on Party: the row most likely to be
wanted, and one press from either neighbour.

There was a **favourites** tier — a second sort rank between the pinned room and the rest,
with a star in the list. It is gone: pinning a primary room already does the useful version,
and the owner's verdict on the Sonos feature it mirrored was "probably the least used Sonos
feature on the planet". If it ever comes back it belongs as a star off to the side, not as a
row in this panel. Sonos *content* favourites (`favorites:1`, the Favorites screen) are a
different thing entirely and remain.

Two things sit outside the widget's version, in the order asked for:

- **Party is first**, because it is the one press that answers "put this everywhere". It
  hosts from *the room the panel was opened on* — `partyMode(hostGroupId)` — since party
  mode hinges on a source, which is the whole reason it moved off the sidebar.
- **TV Input is last**, and only for a room with a soundbar in it. It is the one row that
  changes what the room is *playing* rather than which rooms are listening.

The sidebar's control row **stays at the top**, matching what most Android apps and Plex do.
Reordering rooms would live here too, if Sonos had an order to reorder — it does not.

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

The first-launch case needs care, because detection lands a moment *after* the first room
is auto-chosen. Moving the selection then is right, but only while it is still the app's
own choice — and intent cannot be read from `selectGroup`, because the sidebar selects on
*focus*, so focusing the current row programmatically is indistinguishable from a viewer
pressing towards it. What is decidable is whether the selection is still what
`defaultSelection` would have picked; if so it moves, and if the viewer has gone elsewhere
it does not.

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
