# The LAN transport, verified on Android TV

**Status: proven on hardware, 2026-09-07.** Every claim below was run against a real
NVIDIA Shield (Android 11 / API 30, Ethernet) and a real Sonos household. Nothing here is
inferred from documentation.

This document was written when `:core` was built on the wrong transport, to record what
the replacement was and what it costs on this platform. The rewrite it was for landed in
2026-09; what it records is still how `:core` works.

---

## Why the cloud API went

Two independent reasons, either sufficient on its own.

**1. It cannot ship on a television.** Sonos's OAuth consent page cannot be completed with
a remote — reaching and activating its Sign In control wants a pointer — and Sonos offers
no device-code or second-screen grant. The app's old `LoginScreen` → `SonosAuthWebViewScreen`
was a dead end on the target device, whatever else was right about it. The account path is not
undesirable here; it is closed.

**2. It buys nothing.** The Control API is the *same API* over the LAN, unauthenticated.
The only cloud-exclusive capability is control from outside the house. YouTube Music search
is closed on both transports — by a Google project gate on its partner API, x2rock found on
2026-09-23 — while SMAPI search works over the LAN with no account at all.

Sibling research: `../x2rock/docs/architecture.md`, especially "Integration path". Its
porting guidance for this app now lives here, in `docs/porting-from-x2rock.md`.

---

## The handshake

```
wss://<player-ip>:1443/websocket/api

X-Sonos-Api-Key: 123e4567-e89b-12d3-a456-426655440000
Sec-WebSocket-Protocol: v1.api.smartspeaker.audio
```

The API key is a **well-known sample key**, not one issued to this project. There is no
login, no token exchange, no refresh.

**Send no `Origin` header.** The player answers `403 Forbidden` if one is present, and
`400 Bad Request` if the API key is missing. OkHttp does not add `Origin` on its own — it is
not a browser — so a plain `Request.Builder` is correct. A `WebView`-based client could not
do this at all, since the browser WebSocket API forces an `Origin` that cannot be removed.

OkHttp 4.12.0 is already a dependency (`core/build.gradle.kts`) and has native `WebSocket`
support. **No new library is required.**

### TLS

The player presents a **leaf-only chain**: its own certificate, `CN=<MAC>`, signed by
`CN=Sonos Device Authentication Root CA`, with the root *not* included.

- TLS 1.3, `TLS_AES_256_GCM_SHA384`, RSA-2048 / SHA-256. All modern — Android's Conscrypt
  raised no objection below the TrustManager, which was the main platform unknown.
- Leaf validity is roughly six months, so **do not pin the leaf**.
- A custom `X509TrustManager` is still required, because the root is not obtainable from the
  handshake. If that root certificate can ever be sourced out of band, a depth-1 chain would
  validate normally against it and the last relaxation disappears. See "Open questions".

### Hostname verification — no relaxation needed

The leaf's SAN carries `DNS:sonos-<MAC>.local` and **no IP address**, so connecting by IP can
never verify. It does not have to:

```kotlin
OkHttpClient.Builder()
    .sslSocketFactory(sslSocketFactory, trustManager)   // still needed, see above
    // NO .hostnameVerifier(...) -- the default is correct
    .dns { hostname ->
        if (hostname.equals(playerName, true)) listOf(InetAddress.getByName(playerIp))
        else Dns.SYSTEM.lookup(hostname)
    }
    .build()
```

Connect to `wss://sonos-<MAC>.local:1443/…` with that `Dns` mapping and **OkHttp's default
hostname verifier passes**, verified on device. The name is used for SNI and matching; the
mapping supplies the address.

### The name is derivable — no mDNS lookup required

`InetAddress.getByName("…​.local")` throws `UnknownHostException` on Android: the platform
resolver has no mDNS, and `NsdManager` would otherwise be needed. It isn't, because the
RINCON player id already contains the MAC:

```
RINCON_48A6B818D138 01400   ->   sonos-48A6B818D138.local
       ^^^^^^^^^^^^
```

Strip the `RINCON_` prefix and the trailing `01400`. Verified against all five players in
the household. `groups:1 getGroups` returns both the player id and its `websocketUrl`
(carrying the IP), so one call yields everything the `Dns` mapping needs.

### The household id has three names, and two of them are wrong

Whatever the source, the id the Control API accepts is the **long** form: two segments
either side of a dot, `Sonos_xxxxx.yyyyy`. The short `Sonos_xxxxx` is a different string
rather than a prefix, and a command scoped with it is refused `ERROR_INVALID_OBJECT_ID` —
observed against a player that accepted the long form seconds later on the same socket.

| Source | Field | Form |
|---|---|---|
| SSDP `M-SEARCH` reply | `HOUSEHOLD.SMARTSPEAKER.AUDIO` | **long** |
| SSDP `M-SEARCH` reply | `X-RINCON-HOUSEHOLD` | short — refused |
| mDNS `_sonos._tcp` TXT | `mhhid` | **long** |
| mDNS `_sonos._tcp` TXT | `hhid` | short — refused |
| TLS certificate SAN | `URI:urn:sonos:hhid:` | short — refused |
| `http://<ip>:1400/status/zp` | in the body | **long** |

The last row is the useful one: a player at a known address can yield both its id
(`/xml/device_description.xml`, `<UDN>uuid:RINCON_…</UDN>`) and its household (`/status/zp`)
over cleartext 1400, with no multicast involved at all.

### Asking a player for its household does not always work

`SonosSocket.householdId()` sends a frame with no namespace and reads the household out of
the resulting error reply — "the cheapest question is a deliberately invalid one". A Sonos
One SL on firmware `p20.96.1-79270` **ignored it entirely**: no reply, no error, the socket
otherwise healthy and answering a properly-scoped `getGroups` on the same connection a
moment later. Do not rely on it. It is only reached when a seed carries no household, which
SSDP always supplies — which is why the weakness went unnoticed.

### Discovery: SSDP is not guaranteed

On one office LAN a raw `M-SEARCH` to `239.255.255.250:1900`, sent from the correct source
address, drew **zero replies**, while mDNS resolved the same speaker normally and TCP 1443
was reachable throughout. Multicast filtering of that group is real and the app has no
fallback, so a first run on such a network finds nothing. mDNS is the natural answer: one
`_sonos._tcp` TXT record carries `uuid`, `location` and `mhhid` — a complete seed.

---

## The message protocol

Every frame — command, reply and event alike — is a **two-element JSON array**,
`[header, body]`:

```json
[{"namespace":"groups:1","command":"getGroups","householdId":"Sonos_…","cmdId":"1"}, {}]
```

- `cmdId` is a **string**, assigned by the client, incrementing, echoed back in the reply.
- **Replies carry `success`; unsolicited events never do.** That is the only discriminator.
- `subscribe`'s own reply *is* the first state snapshot; later changes arrive as pushes.
- Subscribe before you need state, and **start listening before subscribing**, or the initial
  snapshot is missed.

Scoping rules, which are not optional:

| Namespace | Send to |
|---|---|
| `groups:1` | any player, `householdId` scope |
| `playback:1`, `playbackMetadata:1`, `groupVolume:1` | that group's **coordinator's** socket |
| `playerVolume:1` | that **player's own** socket |

Anything else fails `ERROR_INVALID_OBJECT_ID`.

Keepalive is `pingInterval(30, SECONDS)` on the OkHttp client — one builder call reproduces
the Rust daemon's ping behaviour, and its dead-socket rule too: OkHttp fails a socket whose
last pong has not arrived when the next ping is due, so a silent peer is dead within 60s.
x2rock's 90s-of-silence timer has no counterpart here because it needs none. Subscriptions
do **not** survive a reconnect and there is no replay buffer: re-subscribe and take the fresh
snapshot as truth rather than merging with pre-disconnect state.

---

## Discovery: SSDP works here

`../x2rock` scans TCP port 1443 instead of using multicast, and its own docs say **not to
inherit that** — it is a workaround for `ufw default deny incoming` on a laptop, not a fact
about Sonos or the network. Confirmed: a stock Android TV has no such firewall.

One `M-SEARCH` for `urn:schemas-upnp-org:device:ZonePlayer:1` returned **11 replies in under
700ms**, on Ethernet, with no `MulticastLock` involved. It found more devices than the LAN
CLI's cached topology lists, because the extras are the surrounds and subs that are
`Invisible` in group topology.

Discovery on this platform is a ~40-line `DatagramSocket`, not a subnet scan. Reaching any
single player is enough — `getGroups` then reports every other player's address.

Two caveats kept from the sibling research: hold a `WifiManager.MulticastLock` if the device
is ever on Wi-Fi (irrelevant on Ethernet, which is what an Android TV box usually uses), and
keep the port-1443 connect-scan documented as a fallback because it works through anything.

---

## Cleartext on port 1400, scoped tightly

The Control API has **no queue capability at all** — `queue:1` / `playbackQueue:1` /
`cloudQueue:1` all answer `ERROR_UNSUPPORTED_NAMESPACE`. The queue lives behind UPnP/SOAP on
`http://<player>:1400`, which is plain HTTP with no TLS alternative.

Android refuses cleartext by default since API 28, and **it fails loudly**, not silently:

```
java.net.UnknownServiceException: CLEARTEXT communication to 192.168.86.26
not permitted by network security policy
```

The exemption does not have to be blunt. Because the `.local` name is derivable, it can be
the scoping key:

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">local</domain>
    </domain-config>
</network-security-config>
```

Verified on device, with the same `Dns` mapping as above:

| Target | No config | `.local`-scoped config |
|---|---|---|
| `http://192.168.86.26:1400` | blocked | **still blocked** |
| `http://sonos-48A6B818D138.local:1400` | blocked | **HTTP 200, 21 queue tracks** |

Cleartext stays off for the internet and off for raw IPs. It needs no build-time knowledge of
any address. `res/xml/network_security_config.xml` already anticipated this in a comment; the
only correction is that no mDNS lookup is involved.

Queue reads use `ContentDirectory` `Browse` with `ObjectID=Q:0`; the control paths and SOAP
envelope are in `../x2rock/src/sonos/upnp.rs`. Note two things when porting: **do not port
`dechunk`** (OkHttp handles `Transfer-Encoding: chunked`), and **do not add UPnP eventing
(GENA)** — it requires the player to connect back to the app, which is a worse idea on a TV
than a request when you need one.

A player answering **HTTP 403** on 1400 has UPnP disabled in the Sonos app under
Settings → Privacy & Security → UPnP.

---

## Test evidence

Run 2026-09-07 against a Shield (`ro.build.version.sdk=30`, `eth0`) and five Sonos players.

| Check | Result |
|---|---|
| WebSocket handshake from device | `101 Switching Protocols`, `server=Linux UPnP/1.0 Sonos/96.1-79270` |
| `getGroups` | full household returned |
| Subscriptions | `groups:1`, `playback:1`, `playbackMetadata:1`, `groupVolume:1` all accepted |
| Push, externally triggered | `volume=15` → `volume=20` in 979ms, matching a CLI-issued change |
| Push, unsolicited | `metadataStatus` + `playbackStatus` on a track change, nothing polling |
| Idle survival | 122s silent, zero failures, next event still delivered |
| SSDP | 11 replies in <700ms |
| Default hostname verifier via `.local` | passes (`http=101`) |
| Cleartext, raw IP | blocked, with and without the config |
| Cleartext, `.local` + scoped config | HTTP 200, `TotalMatches=21`, titles match the CLI |
| Album art off a player, via the LAN client | HTTP 200, `image/jpeg` |

The probe used is a throwaway standalone project, not part of this repo.

---

## What this meant for `:core`

Done in 2026-09. The model layer survived intact, as predicted: the LAN events carry exactly
the Control API's own shapes. The Retrofit REST client, the OAuth flow and its token store,
the login screens and the polling machinery with its backoff curve were all removed, along
with the `delay(300L)`-then-refetch workarounds that existed only for want of an event
stream. `ARCHITECTURE.md` describes what replaced them.

---

## Open questions

- **The Sonos root CA.** Sourcing it out of band would remove the last trust relaxation and
  let the chain validate normally. Not chased.
- **Long-lived socket hosting.** No `Service` exists in `:app`. The sockets live in an
  application-scoped `CoroutineScope` and the `MediaSession` in a singleton behind
  `NowPlayingPublisher`, so both survive Activity changes but not the process being
  reclaimed, which costs a reconnect on the next launch. If a service is ever added, it is
  **not `mediaPlayback`**: this app plays nothing, the speakers do, and that type is for apps
  that are themselves playing. `connectedDevice` is the honest description of talking to
  speakers on the network. Untested, and quite possibly unnecessary — see CLAUDE.md.

Answered since this was written:

- **Network-change handling** — `NetworkMonitor` feeds `ConnectivityManager.NetworkCallback`
  into `SonosHousehold.onNetworkChanged()`, which rebuilds at once rather than waiting out a
  backoff.
- **The Google TV Streamer** (Android 14, Wi-Fi) raised nothing: the WebSocket, SSDP over Wi-Fi
  and cleartext UPnP to `.local` all worked unchanged. See `CLAUDE.md`, "Tested on two devices".
