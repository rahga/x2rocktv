# x2rock

A Sonos controller for Google TV / Android TV. The Sonos layer is a plain Kotlin/JVM
library (`:core`) with no Android dependency; `:app` is the TV frontend.

It talks to the speakers directly over the local network, the same Control API the Sonos
apps use, with **no Sonos account, no sign-in and no polling**: subscribe, and the speakers
push every change. See [`docs/lan-transport.md`](docs/lan-transport.md) for the protocol and
the evidence behind it.

## Status

In use on an NVIDIA Shield (Android 11, Ethernet) and a Google TV Streamer (Android 14,
Wi-Fi). Rooms, transport, volume, grouping and party mode, the queue, favourites, a
soundbar's TV input with Night Sound and Speech Enhancement, and track ratings all work.
What is still to do is in [`docs/punch-list.md`](docs/punch-list.md).

Discovery is SSDP only, so a network that blocks multicast will find no speakers yet.

## Building

You need a JDK (17+; 21 is what has been tested) and an Android SDK, named by `sdk.dir` in
`local.properties` or by `ANDROID_HOME`. There are no credentials to supply. Gradle is fetched
by the wrapper.

Open in Android Studio and run `:app`, or:

```sh
./gradlew :app:installDebug
```

## Tests

```sh
./gradlew :core:test :app:testDebugUnitTest            # against a fake player; what CI runs
./gradlew :core:test -Dx2rock.live=discover            # also against real speakers, read-only
```

See `ARCHITECTURE.md` for the layout and `CLAUDE.md` for the conventions.
