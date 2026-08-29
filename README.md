# x2rock

A Sonos controller. Started as a Google TV app; the Sonos layer is a plain Kotlin/JVM library
(`:core`) so the same code also drives a Linux command line (`:cli`).

## Status

Not stable at present, primarily due to GUI bugs.

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

`-r`/`--room` and `-H`/`--household` work either before or after the subcommand:
`x2rock -r Office next` and `x2rock next -r Office` are equivalent.

### Multiple households

A Sonos account can have more than one household — for example a standalone speaker that got set
up separately from the rest of your system. `x2rock rooms` (and everything else) only looks at one
household at a time.

```sh
x2rock households                        # list every household by the rooms in it
x2rock config --household "Media Room"   # persistent default: pick by naming any room in it
x2rock rooms -H "Media Room"             # one-off override, doesn't touch the saved config
```

### MPRIS (playerctl, Waybar, media keys)

`x2rock daemon` publishes the room on the session bus as `org.mpris.MediaPlayer2.x2rock`, so
anything that speaks MPRIS drives Sonos like a local player:

```sh
x2rock daemon &                      # or a systemd --user service / exec-once in hyprland.conf
playerctl -p x2rock play-pause
playerctl -p x2rock metadata title
playerctl -p x2rock volume 0.4
```

Waybar's built-in `mpris` module picks it up with no configuration. The daemon polls every
5 seconds (`--interval`), re-polls immediately after any MPRIS command, and backs off on errors.

Hyprland binds, for example:

```
bind = , XF86AudioPlay,       exec, x2rock toggle
bind = , XF86AudioNext,       exec, x2rock next
bind = , XF86AudioRaiseVolume, exec, x2rock vol +3
```

Running more than one room at once — e.g. a home system and a separate-household office
speaker — needs one daemon per room, each with its own `--bus-name`, as a systemd `--user` service:

```ini
# ~/.config/systemd/user/x2rock-media-room.service
[Unit]
Description=x2rock MPRIS bridge for Media Room
PartOf=graphical-session.target
After=graphical-session.target

[Service]
Type=simple
ExecStart=%h/.local/bin/x2rock daemon -r "Media Room" -H "Media Room" --bus-name x2rock-media-room
Restart=on-failure
RestartSec=5
Environment=XDG_RUNTIME_DIR=%t

[Install]
WantedBy=graphical-session.target
```

```sh
systemctl --user enable --now x2rock-media-room.service
```

The unit references `~/.local/bin/x2rock`, not `cli/build/install/...`, since a rebuild wipes the
build directory — copy (or symlink) the installed app there, e.g.
`cp -r cli/build/install/x2rock ~/.local/share/x2rock && ln -sf ~/.local/share/x2rock/bin/x2rock ~/.local/bin/x2rock`.

## Tests

```sh
./gradlew :core:test :cli:test :app:testDebugUnitTest
```

See `ARCHITECTURE.md` for the layout.
