# CLAUDE.md

## Project
**x2rock** — A Sonos controller. Started as a Google TV app; the Sonos layer is a plain
Kotlin/JVM library (`:core`) shared with a Linux CLI (`:cli`). See `ARCHITECTURE.md` for the
full module/file map before making non-trivial changes.

## Language & Platform
- Kotlin only — no Java
- `:app` — Google TV / Android TV (Jetpack Compose for TV), min SDK 21, target SDK 34
- `:cli` — Linux command line (Clikt + Mordant), JVM via Gradle's `application` plugin
- `:core` — plain Kotlin/JVM, no Android dependency; both frontends depend on it
- Build system: Gradle with Kotlin DSL (`build.gradle.kts`)

## Sonos Integration
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

```sh
git clone git@github.com:rahga/x2rock.git
cd x2rock
./gradlew :cli:installDist
cp -r cli/build/install/x2rock ~/.local/share/x2rock
ln -sf ~/.local/share/x2rock/bin/x2rock ~/.local/bin/x2rock   # ~/.local/bin must be on PATH

x2rock config --client-id ID --client-secret SECRET   # ask the user — not committed anywhere
x2rock install-handler                                 # once: registers the x2rock:// OAuth redirect
x2rock login                                            # interactive: user completes browser sign-in
x2rock households                                       # if the account has more than one, see below
x2rock config --household "<a room in the target household>"
x2rock config --room "<default room>"
```

If Java/Gradle aren't present, the project pins Gradle 8.7 (`gradle/wrapper/gradle-wrapper.properties`);
install a JDK (17+ is safe, 21 is what's been tested) via the system package manager or `mise`. If
`gradle-wrapper.jar` is missing (has happened on a fresh clone in the past — check `.gitignore`
isn't excluding it unintentionally), regenerate it with `gradle wrapper --gradle-version 8.7` using
any locally installed Gradle before running `./gradlew`.

**Do not guess or reuse Sonos client-id/secret from memory** — always ask the user; `x2rock login`
requires an interactive browser step only the user can complete.

### Multiple households
If `x2rock rooms` doesn't show a room the user says exists, check `x2rock households` before
assuming a network/discovery problem — the CLI only talks to the cloud API, never local UPnP, and
an account can have more than one household (e.g. a standalone speaker set up separately from the
rest of the system). `-H "<room>"` overrides the household for one command without touching config.

### MPRIS daemon / systemd
`x2rock daemon -r "<room>" -H "<household room>" --bus-name <unique-name>` publishes one room to
the D-Bus session bus as an MPRIS2 player (works with Waybar, playerctl, media keys — see README
for a systemd `--user` unit template). One daemon process per room; each needs a distinct
`--bus-name`. Systemd unit files for a given machine are NOT checked into this repo (they live in
that machine's `~/.config/systemd/user/`) — if asked to set up autostart on a new machine, create
them fresh from the README template rather than looking for them in the repo.

## Known Gaps / Deferred Work
- Everything polls the REST API on an interval; Sonos's Control API supports WebSocket push
  subscriptions (see `docs/sonos-control-api.md`) that nothing here uses yet. Worth raising as an
  option if asked to improve `daemon` responsiveness or reduce API call volume — it would benefit
  `:core` (and therefore both `:app` and `:cli`) if implemented there.
