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

If Java/Gradle aren't present, the project pins Gradle 8.7 (`gradle/wrapper/gradle-wrapper.properties`);
install a JDK (17+ is safe, 21 is what's been tested) via the system package manager or `mise`. If
`gradle-wrapper.jar` is missing (has happened on a fresh clone in the past — check `.gitignore`
isn't excluding it unintentionally), regenerate it with `gradle wrapper --gradle-version 8.7` using
any locally installed Gradle before running `./gradlew`.

An Android SDK is needed for `:app` (`sdk.dir` in `local.properties`, or `ANDROID_HOME`).
**There are no credentials to supply** — no client id, no secret, nothing in
`local.properties` beyond the SDK path. If you find yourself looking for Sonos API keys,
you are working from a stale mental model of this project.

Much of `:core` can be tested without a device at all: it is plain Kotlin/JVM, so a JUnit
test can open a real socket to a real speaker. That is how the transport was built, and it
is far faster than a build-install-logcat cycle. Reach for the Shield when the question is
about *Android* — cleartext policy, permissions, lifecycle — not about the protocol.

### Households
A household is discovered, not configured: SSDP's reply carries
`HOUSEHOLD.SMARTSPEAKER.AUDIO`, which is the full id the WebSocket needs (note
`X-RINCON-HOUSEHOLD` is a truncated form and will not work). One network, one household in
practice; a room missing from a listing is more likely a player that did not answer
discovery than a second household.

## Known Gaps / Deferred Work
- **No foreground service.** The sockets are held by an application-scoped `CoroutineScope`,
  which survives Activity changes but not the process being reclaimed. A long-lived
  connection wants a `Service`; on API 34+ it must declare
  `foregroundServiceType="mediaPlayback"`. Nothing has needed it yet on a TV that rarely
  sleeps, but it is the next real gap.
- **Only tested on one device.** An NVIDIA Shield (Android 11, Ethernet). The Google TV
  Streamer (Android 14) is the stricter target and would surface newer local-network policy
  first.
- **A custom `X509TrustManager` is still required**, because players present a leaf-only
  chain whose root is not in any store. Hostname verification is *not* relaxed — see
  `LanHttp` for why, and do not "simplify" it by adding a permissive verifier.
- The queue does not update by itself: UPnP eventing needs the player to connect back to us,
  which is deliberately not used, so the queue screen re-reads instead.
