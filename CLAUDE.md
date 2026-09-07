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
- **Only tested on one device.** An NVIDIA Shield (Android 11, Ethernet). The Google TV
  Streamer (Android 14) is the stricter target and would surface newer local-network policy
  first.
- **A custom `X509TrustManager` is still required**, because players present a leaf-only
  chain whose root is not in any store. Hostname verification is *not* relaxed — see
  `LanHttp` for why, and do not "simplify" it by adding a permissive verifier.
- The queue does not update by itself: UPnP eventing needs the player to connect back to us,
  which is deliberately not used, so the queue screen re-reads instead.

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

### Planned rework: party mode and grouping

Party mode belongs in the room view, not the rooms panel: it hinges on a *source* player
that the others join, so it needs a room already chosen. Grouping generally goes with it.

Where the sidebar's control row (leave party, settings, collapse) belongs is **undecided**.
Moving it to the bottom would make a room the first thing the panel offers; keeping it at
the top matches what most Android apps and Plex do. Not a settled question — do not move it
without asking.

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
