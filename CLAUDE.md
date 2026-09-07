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

> **The cloud transport is on its way out.** As of 2026-09-07 the LAN Control API is proven
> on real Android TV hardware — same API, no account, no OAuth, push instead of polling —
> and the cloud OAuth flow *cannot be completed with a TV remote at all*, so the current
> `:app` login path cannot ship. **Read `docs/lan-transport.md` before touching the network
> layer**; it records the verified handshake, protocol, discovery and Android specifics.
> The description below documents what `:core` does *today*, not what it should do.

- Uses the **Sonos cloud Control API** (OAuth 2.0 + REST, `api.ws.sonos.com`) — not local
  UPnP/SOAP. A Sonos account can have more than one *household*; nothing in the API assumes one.
- Docs are in `/docs/` — read them before implementing Sonos features, especially
  `docs/sonos-control-api.md` (mirrors Sonos's own reference, including its WebSocket
  subscription support, which nothing here uses yet — see the Known Gaps section below).

## Architecture
- MVVM with clean architecture in `:app`
- Repository pattern for Sonos API calls (`SonosRepository`, `SonosAuthRepository` in `:core`)
- Kotlin Coroutines + Flow for async/reactive state

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
`SONOS_CLIENT_ID` / `SONOS_CLIENT_SECRET` are read from `local.properties` but default to empty
strings, so **the project builds without Sonos credentials** — useful when working on the LAN
transport, which needs none.

**Do not guess or reuse Sonos client-id/secret from memory** — always ask the user; signing in
requires an interactive browser step only the user can complete (and see the note above about
that step being impossible on a TV remote).

### Multiple households
A Sonos account can have more than one household — e.g. a standalone speaker set up separately
from the rest of the system — and a room missing from a listing is more often that than a
network or discovery problem. Nothing in the API assumes a single household.

## Known Gaps / Deferred Work
- Everything polls the REST API on an interval; the Control API supports WebSocket push
  subscriptions that nothing here uses yet. **This is no longer just an efficiency gap** — the
  same API is served by the speakers themselves over the LAN with no account, and that is now
  verified working on Android TV. See `docs/lan-transport.md`. Fixing it in `:core` removes
  the OAuth layer entirely.
- Untested pieces of that port, in likely order of surprise: hosting a long-lived socket (no
  `Service` exists in `:app` yet; API 34+ needs `foregroundServiceType="mediaPlayback"`),
  network-change and wake handling via `ConnectivityManager.NetworkCallback`, and the Google TV
  Streamer (Android 14) as the stricter device target.
