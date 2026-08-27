# x2rock

A Sonos controller. Started as a Google TV app; the Sonos layer is a plain Kotlin/JVM library
(`:core`) so the same code also drives a Linux command line (`:cli`).

## Android TV

Open in Android Studio, put `SONOS_CLIENT_ID` / `SONOS_CLIENT_SECRET` in `local.properties`, run `:app`.

## Linux CLI

```sh
./gradlew :cli:installDist
export PATH="$PWD/cli/build/install/x2rock/bin:$PATH"

x2rock config --client-id ID --client-secret SECRET   # from the Sonos integration manager
x2rock install-handler                                 # once: lets the browser hand the sign-in back
x2rock login
x2rock config --room Kitchen                           # default room

x2rock now                 # what's playing
x2rock toggle              # play/pause
x2rock vol +5
x2rock -r Office next
x2rock now --oneline       # for a status bar
x2rock rooms --json        # for scripts
```

The client id/secret can also come from `SONOS_CLIENT_ID` / `SONOS_CLIENT_SECRET`, and the room
from `X2ROCK_ROOM`. Everything else lives in `$XDG_CONFIG_HOME/x2rock/` with mode 0600.

If the browser can't invoke the handler (no `xdg-mime`, a locked-down browser), `x2rock login --manual`
lets you paste the redirected URL instead.

Hyprland binds, for example:

```
bind = , XF86AudioPlay,       exec, x2rock toggle
bind = , XF86AudioNext,       exec, x2rock next
bind = , XF86AudioRaiseVolume, exec, x2rock vol +3
```

## Tests

```sh
./gradlew :core:test :cli:test :app:testDebugUnitTest
```

See `ARCHITECTURE.md` for the layout.
