# Changelog

Each release is tagged `v<version>` and its build takes that as its version name. Version codes
rise by one with every build that leaves the developer's machine.

## 0.1.2 — your own room order, Configure, and a fix for split parties

### New

- **Order the rooms yourself.** Hold Select (or Left) on a room to pick it up, move it with Up and
  Down, and put it down with Select or Back. Rooms you have placed come first, every other room
  follows alphabetically. Settings → Reset room order puts it back.
- **Play everywhere from the room list.** With a room picked up, Left asks to play everywhere from
  it: Select confirms, a second Left confirms and first sets every speaker to that room's volume.
- **This TV's room is marked** in the list with a coloured TV badge, which moves when you press
  "This is my TV".
- **Configure**, in the player's button row where Sleep timer was: Source, Sleep timer, Sound and
  Room settings for the room. Group now holds only which rooms play together.
- **Volume presses speed up** the way seeking does: 2, then 3, then 5, and finer when quiet (2 at
  a time below 10, 1 at a time below 4).
- **Saving to Sonos Favorites is out of Beta**: the player's star, and Add to and Remove from Sonos
  Favorites in an item's menu.

### Fixed

- After splitting a party, the room that hosted it could show "Playing" with no track or art
  until the app restarted. A speaker leaving the group sent an empty report naming the host's
  group, and the app took it as the host's own.
- A hold that changed what had focus could "click" whatever focus landed on when the button was
  let go: an item's menu closing as it opened, or Browse closing after its last preset was deleted.
- The player no longer scrolls the art away when you reach its lower row of buttons.
- A grouped room's speakers each get a full volume control, the same as the room's own.

### Thanks

To @BookCatKid, whose research into how today's Sonos controllers
reach the music services ([SoCo #1010](https://github.com/SoCo/SoCo/pull/1010)) saved this project a
great deal of time and effort.

### Still untested on hardware

Unchanged from 0.1.0, less saving to Sonos Favorites: Line-In, a portable's battery, a soundbar
that stalls while a room joins it, a satellite that drops off later, a change of network between two
Sonos systems, and alarms in a household with no time zone set.

## 0.1.1 — a home-screen row cannot close the app

- A TV whose home screen refuses the app's row of rooms no longer closes the app as the rooms load.
  Fire TV is the first such device expected: it has the Android TV provider the row is written to,
  but is not Google TV. Untested there; a tester is about to run it.

## 0.1.0 — first test build

The first release anyone outside the developer's household runs, as an APK from a GitHub
pre-release: see [installing on Google TV](docs/install-on-google-tv.md). Everything below was built
against two real Sonos systems, one of five rooms and one a single speaker on an office network,
and checked on an NVIDIA Shield, a Google TV Streamer and an emulator.

### Untested on hardware

Most of these need hardware neither household has, so they have run against a fake speaker only. The
first two and the last are hidden unless **Settings → Beta features** is on, and say "Beta" where they appear.

- **Line-In**, on the room's own speaker or another room's. No speaker with a line-in has been
  available; what a room on its line-in reports is unknown, so the app never says a room is on one.
- **A portable's battery** in Room settings. Never read off a Roam or Move.
- **A soundbar that stalls while a room joins it on its TV input.** The wait-and-check path for a
  player that answers late has only run against the fake; on hardware every regroup answered in time.
- **A satellite that drops off later**, such as an unplugged surround: whether the speaker reports it.
- **A change of network** with two Sonos systems on two networks: the app keeps one remembered
  speaker per network, and that switch has not been seen happening.
- **Alarms in a household with no time zone set**, which run on UTC. Both households have one.
- **Saving to Sonos Favorites** (Beta): the player's star for the song playing, and Add to Sonos
  Favorites in an item's menu. Saved and listed on a real speaker from a computer, never yet pressed
  on a TV.

If you have any of these, turn on Beta features and use **Send a report** from the same place.

### What it does

- **Rooms**: every room in the household with what it is playing, its art and a level meter while
  it plays. Play, pause, skip, seek, shuffle, repeat and crossfade where the source allows them.
  Volume for the room and for each speaker in it, and an exact level by holding.
- **Grouping**: a room panel per room, with what plays together, what can join, each one's level,
  and "Play everywhere". Rooms are followed across regroups made by any other controller.
- **TV**: a soundbar's TV input, with the input format shown, Night Sound and Speech Enhancement.
  The device's own television's room is found, or named once with "This is my TV", and the app opens
  on it.
- **Music**: Sonos favourites, including the albums, artists and songs today's Sonos app saves as
  shortcuts, and removing one (Menu twice on its row); the queue (move, remove, clear, save); radio
  by directory or by a stream's address; and presets of rooms, levels and a favourite. A pin on
  every Browse screen says whether starting something returns to the player or stays to pick again.
- **Music services**: each service opens on its own front page where it has one (Sonos Radio,
  Spotify, Deezer, TuneIn) and its library otherwise. One search across every service the household
  has, plus Apple Music, with recent searches and preferred services first. An item's menu plays it
  next or adds it to the queue, adds a track to the account's playlists, or opens an audiobook's
  chapters. Mixcloud plays through the queue, as the Sonos app does it, and TuneIn's podcast episodes
  play as streams. Bandcamp is left out: its
  household token works for the speakers only.
- **Room settings**: bass, treble, loudness and Trueplay; a Sub's and surrounds' levels; the status
  light, touch controls and a soundbar's remote settings; the volume limit, shown; a sleep timer.
- **The household**: alarms, made, armed, changed and removed here, and a ringing one snoozed or
  stopped from the player; which speakers have an update waiting, read only.
- **Google TV**: a home-screen channel of rooms, a launcher media card with the cover, and the
  remote's media keys. The volume keys are left alone: they control what the TV is playing.
- **Connecting**: no Sonos account. Speakers are found by SSDP, then mDNS where a network drops SSDP,
  and remembered per network.
