# x2rock

A Sonos controller for Google TV / Android TV. The Sonos layer is a plain Kotlin/JVM
library (`:core`) with no Android dependency; `:app` is the TV frontend.

## Status

Not stable at present, primarily due to GUI bugs.

There is also a larger problem than the GUI: the app talks to Sonos over the **cloud** API,
and Sonos's OAuth consent page cannot be completed with a TV remote — so the sign-in flow
cannot ship on the device this is for. The same Control API turns out to be served by the
speakers themselves over the LAN, with no account and no polling; that has been verified
end to end on an NVIDIA Shield. See [`docs/lan-transport.md`](docs/lan-transport.md).
Replacing the transport is the next significant piece of work.

## Android TV

Open in Android Studio, put `SONOS_CLIENT_ID` / `SONOS_CLIENT_SECRET` in `local.properties`, run `:app`.

## Tests

```sh
./gradlew :core:test :app:testDebugUnitTest
```

See `ARCHITECTURE.md` for the layout.
