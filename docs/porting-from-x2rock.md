# Porting to Android TV (Kotlin), with no Sonos account

Moved from x2rock's `docs/architecture.md` on 2026-09-23, where it was written on 2026-08-29 for
this project. The finding it rests on - that the LAN Control API needs no Sonos account - lives
and is kept current there; this is the porting guidance that only x2rocktv needs.


Written 2026-08-29 for the `x2rocktv` line, whose driving requirement is an Android TV app that
does **not** depend on logging in to a Sonos account.

That requirement is not a preference. **Sonos's OAuth consent page cannot be completed with a
remote** - reaching and activating its Sign In control wants a mouse and keyboard, which is not
what a television has. A login flow that assumes a pointer is a login flow an Android TV app cannot
ship, whatever else is right about it. The usual escape on this platform is a second-screen or
device-code grant, where the TV shows a short code and the sign-in happens on a phone; that is not
offered here. So the account path is not merely undesirable on TV, it is closed, and a transport
that never asks for one is the only way the app exists at all.

**That requirement is already met by the central finding here, and cheaply.** All of the control -
rooms, transport, volume, grouping, favorites, queue read *and* write, soundbar TV input, what the
TV is sending - runs over the LAN with no login, no token, no OAuth, no internet. See "Integration
path". The features that do leave the LAN (music-service search, browse and linking, and the radio
directory) talk to those services directly and never need a Sonos login either. The only
capability that needs a Sonos account is control from outside the house.
So the port does not need a reduced feature set to avoid OAuth; it needs the same feature set over
a different transport than the cloud API, and that transport is fully documented above.

### Re-derive, do not inherit: discovery

This is the one place where copying a conclusion from this document would be a mistake.

"Discovery" says multicast is not dependable and to use an outbound TCP connect-scan of port 1443.
That is a true statement about **an Omarchy laptop**, and the cause is named in "The firewall
problem": `ufw default deny incoming` drops the speakers' unicast SSDP replies because they do not
match the conntrack entry for the multicast query. It was proven by fixing it with a single
`ufw allow from <speaker-ip>`, and re-confirmed 2026-08-29 - an M-SEARCH for
`urn:schemas-upnp-org:device:ZonePlayer:1` from this host still gets **zero replies** while five
players sit on the same subnet answering everything else instantly.

The network passes multicast fine. The speakers answer. A **stock Android TV device has no host
firewall doing this**, so SSDP is likely to work there and would replace the subnet scan entirely -
faster, politer, and without the "looks like reconnaissance on corporate gear" problem. Test it
first rather than porting the scan.

Two Android caveats if you do: multicast receive needs a `WifiManager.MulticastLock` held across
the query, and an Android TV box is usually on Ethernet, where that lock is not the relevant
control - so verify on the transport the device actually uses, not on an emulator. Keep the port
1443 connect-scan as the documented fallback, because it works through anything.

### Platform translations

| This implementation | Android TV equivalent | Note |
|---|---|---|
| `rustls` verifier accepting any cert | custom `X509TrustManager` (the Sonos root is not in the handshake), and the **default** hostname verifier via the `sonos-<MAC>.local` name | the cert is CA-signed (Sonos root), SAN `sonos-<MAC>.local` with no IP — so only the trust anchor needs supplying, not the hostname check; connect by the `.local` name (derivable from the RINCON id) with a name→IP mapping. x2rocktv verified this on device 2026-09-07 |
| `tokio-tungstenite` on `wss://ip:1443` | OkHttp `WebSocket` | `Sec-WebSocket-Protocol: v1.api.smartspeaker.audio` and **no `Origin` header**; confirm the client library lets you control both before building on it |
| MPRIS2 over D-Bus, one bus name per group | `MediaSession` per room, or one session plus a room switcher | this is the biggest design decision in the port and has no obvious right answer |
| `x2rock:*` MPRIS metadata keys | `MediaMetadata` / `MediaSession` extras | same idea: the standard has no notion of "which rooms are grouped", "is this on TV", or "what channels is the TV sending", so they ride as custom keys |
| systemd user unit | foreground service | Android TV rarely sleeps, but a background service will still be killed |
| logind `PrepareForSleep`, NetworkManager `StateChanged` | `ConnectivityManager.NetworkCallback` | the *reasons* in "Connection lifecycle" all still apply; only the signal changes |
| `$XDG_STATE_HOME/x2rock/` keyed by network | app-private storage keyed the same way | "Identify the network before deciding what to try" is not Linux-specific |
| `/proc/net/arp` + interface netmask | `ConnectivityManager` `LinkProperties` | only needed if you keep the connect-scan |

### Android traps this codebase never had to face

- **UPnP is plain HTTP, and Android blocks cleartext by default.** Everything in "Queue mutation
  over UPnP" runs over `http://<player>:1400` with no TLS. Since API 28 that is refused unless a
  network security config permits it. Get this wrong and the whole queue layer fails - possibly
  quietly, which is the worst kind. It is the first thing to prove on device, before writing any
  SOAP.
- **Two different trust relaxations, for two different ports.** 1443's cert is CA-signed (Sonos
  root) but the root is not sent in the handshake, so it needs *either* the Sonos root supplied as
  a trust anchor - after which it validates normally by the `sonos-<MAC>.local` name - *or*
  verification relaxed; 1400 needs cleartext allowed. They are configured in different places and
  neither implies the other.
- **Scope both narrowly.** These are local speakers on a home LAN; a blanket "trust everything"
  config is a real weakness in a shipped app, not a shortcut.
- **Chunked responses.** The players answer UPnP with `Transfer-Encoding: chunked` and
  `Connection: close`. A normal HTTP client handles this; this codebase hand-rolls it only because
  its client is deliberately minimal. Do not port `dechunk`.

### What does not transfer at all

D-Bus and MPRIS, systemd, XDG paths, `/proc`, logind, NetworkManager, Quickshell and every QML
section, and the Rust crate choices. The Quickshell notes are still worth skimming for *what a
control surface needs to show* - per-room volume, group membership, the TV format at a glance -
which was learned from use rather than from the protocol.

### What the Rust version learned that the Kotlin one predates

Beyond the protocol sections, four hard-won bugs are worth carrying over as design rules, all
written up above: one connection **per coordinator** rather than one per household (routing group
commands down an arbitrary socket published one room out of five and then retried forever);
`playerVolume:1` addressed to the player itself and never the coordinator; topology compared
properly before republishing, or every snapshot flaps every bus name; and a member's socket treated
as best-effort so one flaky portable cannot tear down the household.
