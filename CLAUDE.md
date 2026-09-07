# CLAUDE.md

## Project
**x2rock** — A Sonos controller. Started as a Google TV app; the Sonos layer is a plain
Kotlin/JVM library (`:core`) shared with a Linux CLI (`:cli`). See `ARCHITECTURE.md` for the
full module/file map before making non-trivial changes.

## Language & Platform
- Kotlin only — no Java
- `:app` — Google TV / Android TV (Jetpack Compose for TV), min SDK 23, compile/target SDK 35
  (`app/build.gradle.kts` is the source of truth; this line has been wrong before)
- `:cli` — Linux command line (Clikt + Mordant), JVM via Gradle's `application` plugin
- `:core` — plain Kotlin/JVM, no Android dependency; both frontends depend on it
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
- MVVM with clean architecture in `:app`; `:cli` calls `:core` repositories directly (no ViewModel layer)
- Repository pattern for Sonos API calls (`SonosRepository`, `SonosAuthRepository` in `:core`)
- Kotlin Coroutines + Flow for async/reactive state; `:cli`'s `daemon` command polls and pushes to MPRIS

## Code Style
- Idiomatic Kotlin
- No unnecessary abstractions
- Prefer simplicity over cleverness

## Setting up the Linux CLI on a new machine

> **This CLI is `x2rocktv`, not `x2rock`.** A separate Rust project (`rahga/x2rock`, cloned
> alongside this repo as `../x2rock`) owns the name `x2rock`: its binary is what
> `~/.local/bin/x2rock` is on the developer's machine, with a `systemd --user` unit running
> its daemon, and it uses `~/.config/x2rock/` and the `org.mpris.MediaPlayer2.x2rock-<room>`
> bus names. This project was renamed off all of those in 2026-09 so the two can coexist.
> **Never install this one as `x2rock`.** The Rust binary is local-first (no account, LAN
> only) and supersedes `:cli` on the desktop; whether `:cli` should exist at all is still
> open — see `docs/lan-transport.md`.

```sh
git clone git@github.com:rahga/x2rocktv.git
cd x2rocktv
./gradlew :cli:installDist
cp -r cli/build/install/x2rocktv ~/.local/share/x2rocktv
ln -sf ~/.local/share/x2rocktv/bin/x2rocktv ~/.local/bin/x2rocktv   # ~/.local/bin must be on PATH

x2rocktv config --client-id ID --client-secret SECRET   # ask the user — not committed anywhere
x2rocktv install-handler                                 # once: registers the x2rock:// OAuth redirect
x2rocktv login                                            # interactive: user completes browser sign-in
x2rocktv households                                       # if the account has more than one, see below
x2rocktv config --household "<a room in the target household>"
x2rocktv config --room "<default room>"
```

If Java/Gradle aren't present, the project pins Gradle 8.7 (`gradle/wrapper/gradle-wrapper.properties`);
install a JDK (17+ is safe, 21 is what's been tested) via the system package manager or `mise`. If
`gradle-wrapper.jar` is missing (has happened on a fresh clone in the past — check `.gitignore`
isn't excluding it unintentionally), regenerate it with `gradle wrapper --gradle-version 8.7` using
any locally installed Gradle before running `./gradlew`.

**Do not guess or reuse Sonos client-id/secret from memory** — always ask the user; `x2rocktv login`
requires an interactive browser step only the user can complete.

### Multiple households
If `x2rocktv rooms` doesn't show a room the user says exists, check `x2rocktv households` before
assuming a network/discovery problem — the CLI only talks to the cloud API, never local UPnP, and
an account can have more than one household (e.g. a standalone speaker set up separately from the
rest of the system). `-H "<room>"` overrides the household for one command without touching config.

### MPRIS daemon / systemd
`x2rocktv daemon -r "<room>" -H "<household room>" --bus-name <unique-name>` publishes one room to
the D-Bus session bus as an MPRIS2 player (works with Waybar, playerctl, media keys — see README
for a systemd `--user` unit template). One daemon process per room; each needs a distinct
`--bus-name`. Systemd unit files for a given machine are NOT checked into this repo (they live in
that machine's `~/.config/systemd/user/`) — if asked to set up autostart on a new machine, create
them fresh from the README template rather than looking for them in the repo.

## Known Gaps / Deferred Work
- Everything polls the REST API on an interval; the Control API supports WebSocket push
  subscriptions that nothing here uses yet. **This is no longer just an efficiency gap** — the
  same API is served by the speakers themselves over the LAN with no account, and that is now
  verified working on Android TV. See `docs/lan-transport.md`. Fixing it in `:core` benefits
  both frontends and removes the OAuth layer entirely.
- Untested pieces of that port, in likely order of surprise: hosting a long-lived socket (no
  `Service` exists in `:app` yet; API 34+ needs `foregroundServiceType="mediaPlayback"`),
  network-change and wake handling via `ConnectivityManager.NetworkCallback`, and the Google TV
  Streamer (Android 14) as the stricter device target.
