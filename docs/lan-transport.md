# The LAN transport, verified on Android TV

**Status: proven on hardware, 2026-09-07.** Every claim below was run against a real
NVIDIA Shield (Android 11 / API 30, Ethernet) and a real Sonos household. Nothing here is
inferred from documentation.

This document was written when `:core` was built on the wrong transport, to record what
the replacement was and what it costs on this platform. The rewrite it was for landed in
2026-09; what it records is still how `:core` works.

---

## Why we don't use the cloud API

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

OkHttp (5.x since 2026-10-19; 4.12.0 when this was written) is already a dependency (`core/build.gradle.kts`) and has native `WebSocket`
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

The first fallback for a seed with no household, `SonosSocket.householdId()`, sent a frame
with no namespace and read the household out of the resulting error reply — "the cheapest
question is a deliberately invalid one". A Sonos One SL on firmware `p20.96.1-79270`
**ignored it entirely**: no reply, no error, the socket otherwise healthy and answering a
properly-scoped `getGroups` on the same connection a moment later. It was only reached when a
seed carried no household, which SSDP always supplies — which is why the weakness went
unnoticed. It is gone: `Upnp.householdId` reads `/status/zp` instead (see the table above).

### Discovery: SSDP is not guaranteed

On one office LAN a raw `M-SEARCH` to `239.255.255.250:1900`, sent from the correct source
address, drew **zero replies**, while mDNS resolved the same speaker normally and TCP 1443
was reachable throughout. Multicast filtering of that group is real and the app has no
fallback, so a first run on such a network finds nothing. mDNS is the natural answer: one
`_sonos._tcp` TXT record carries `uuid`, `location` and `mhhid` — a complete seed.

*Since 2026-10-06 that fallback exists*: when SSDP's window draws nothing, `SonosHousehold` asks
`MdnsDiscovery` (`NsdMdnsDiscovery` over `NsdManager` in `:app`) and seeds from the TXT record
(`Discovery.fromSonosTxt`). Run on a moto g (Android 15) at the office with SSDP switched off: it
found Media Room and connected. The paragraph above is the finding as it stood before.

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

**`playback:1` sends two shapes**, told apart by the body's `_objectType`: `playbackStatus`,
and `playbackError` when the room could not play something. An error carries none of a
status's fields, so a parser that treats every field as optional reads it as "nothing
changed" and the only notice that the music stopped is lost. Captured 2026-10-01 off a One SL
(display version 18.8, build 97180312), playing a URL that does not resolve and then one that
refuses the connection — the same body both times, `core/src/testFixtures/resources/fixtures/
event.playbackError.json`:

```json
{"_objectType":"playbackError","errorCode":"ERROR_PLAYBACK_NO_PLAYABLE_CONTENT",
 "reason":"ERROR_NO_PLAYABLE_CONTENT","serviceId":-1}
```

It arrived after three IDLE statuses, not before them. x2rock's capture of an expired stream
was `ERROR_PLAYBACK_FAILED` / `ERROR_CANT_REACH_SERVER` with a `trackName` and an `itemId`,
and a second error three seconds later had no `itemId`; no field can be relied on.

**The sleep timer is pushed, by `sleepTimer:1`** — a namespace in no published reference, found
in x2rock's prefix walk and first probed here (office One SL, 2026-10-05). Group-scoped: player
and household scope answer `ERROR_MISSING_PARAMETERS (Missing groupId)`. `subscribe` answers
empty and then sends the state as an event, and sends one again on every set and cancel,
whoever made it — `x2rock sleep 15` and `sleep off`, here. Captured verbatim
(`core/src/testFixtures/resources/fixtures/event.sleepTimerStatus.json` and `.none.json`):

```json
{"_objectType":"sleepTimerStatus","active":true,"remainingTimeDuration":"PT0H15M0S"}
{"_objectType":"sleepTimerStatus","active":false}
```

The time left is ISO 8601 and is what was left *when the event was sent*; it is not re-sent as
it runs down, so the household fixes the end as each report arrives. `getSleepTimer` answers
the same body. Nothing in the namespace sets one — `setSleepTimer`, `set`, `cancel`, `start` and
the rest are `ERROR_UNSUPPORTED_COMMAND` — so setting stays `ConfigureSleepTimer` over UPnP.
A timer running out sends `active: false` too, and the room pauses a few seconds after it:
a 20-second timer (`x2rock sleep 20s`) on the playing Media Room was reported as
`PT0H0M20S`, then `active: false`, and the transport went PLAYING → PAUSED about five seconds
after the countdown reached zero (2026-10-05, 16:49).

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
`dechunk`** (OkHttp handles `Transfer-Encoding: chunked`), and **do not add standing UPnP
eventing (GENA)** — it requires the player to connect back to the app, which is a worse idea on
a TV than a request when you need one. (There is **one bounded exception**: the household-token
read below opens a listener for a single GENA NOTIFY and closes it. That is a one-shot capture,
not standing eventing — nothing hosts a listener between captures.)

**An inactive queue can change without a usable Control API notification.** On Kitchen's
One SL, 2026-10-11, while a direct SomaFM stream played at volume 1, a track was added to
the initially empty queue and removed again. UPnP `UpdateID` moved 11 → 12 → 13 and
`GetMediaInfo` confirmed the queue was not the source. Separate `playback:1` and
`playbackMetadata:1` subscriptions captured their initial snapshots and a stream metadata
update before the add; no further event arrived through the end of the observation window,
roughly eight seconds after removal. The initial playback snapshots omitted `queueVersion`.
The queue was left empty and its original `x-rincon-queue:` source restored, with playback
stopped and volume unchanged. Commands and timestamped captures are kept locally under
`.local-work/reviews/2026-10-11-codex/capture/`.

This was a direct stream and CLI-issued UPnP edits, not a Sonos-app edit while a SMAPI
station played. It does not settle every station/firmware combination. It does establish
that checking `UpdateID` on playback events cannot guarantee freshness: there may be no
event to trigger it. The earlier observation that every queue edit moves `queueVersion`
applies to idle/queue playback, not all sources. External edits while on another source
remain a documented limit; no timer polling or standing GENA listener is added.

A player answering **HTTP 403** on 1400 has UPnP disabled in the Sonos app under
Account → Privacy and Security → Connection Security → UPnP (the path x2rock confirmed on
iPhone and Android, 2026-09-28; an older one was given here).

**A directory station plays through `playbackSession:1`**, verified on Kitchen 2026-10-02.
`createSession {appId, appContext}` is group-scoped and answers a `sessionStatus` naming a
`sessionId`; `loadStreamUrl {streamUrl, playOnCompletion, stationMetadata}` is then addressed by
that **`sessionId`**, not the group. The player accepts a URL it cannot play — a dead one went
straight to IDLE — so whether it played is read from the transport, which `playStream` does by
counting starts of play. With `stationMetadata.name` given, the room named the container by
it; a stream loaded without one is named by its host.

**A ringing alarm is asked about, on the group's coordinator** (`AVTransport`, captured on
Kitchen 2026-10-02 with the built-in chime at volume 3). `GetRunningAlarmProperties` answers
`AlarmID` while it rings, and the fault **800** when nothing is running — an answer, not a
failure. A `SnoozeAlarm` of `00:01:00` paused the room (`PAUSED_PLAYBACK`) and it rang again
62s later; **while snoozed it still answers its `AlarmID`**, so "ringing" is that *and* playing.
`Pause` while it rang left the room `STOPPED` and the next ask answered 800: that ends it.
What `Pause` does to an alarm that is snoozed, not ringing, was not captured. The chime itself
arrives as `playbackMetadata:1` with a container named `x-rincon-buzzer:0` and no track.
Seen again 2026-10-03 from the TV app: a nine-minute snooze on a three-minute alarm rang
back **when the alarm's duration ran out**, 178s after the snooze — the duration counts from
the scheduled time (x2rock) and seems to end a snooze early too. Alarms set 40–50s ahead fired
on the second here, where x2rock's firmware fired one set 82s ahead two minutes late.

**The alarms themselves are `AlarmClock:1`, household-wide** (captured off Kitchen 2026-10-10 with
disarmed alarms made for it, then removed). Any player answers for every alarm, and each names its
room by a *player* id. `ListAlarms` carries its `<Alarms>` document as escaped **text**, so it is
parsed twice. `UpdateAlarm` takes all ten fields or answers **402** and leaves the alarm as it was,
so a toggle is read, copy, write back whole — read fresh, or an older record undoes another
controller's edit. `CreateAlarm` answers `AssignedID`, and **801 when that room already has an
alarm at that start time, whatever the recurrence** (DAILY against an existing WEEKDAYS at 06:45
was refused too); a refused create still uses up an id. `ON_135` was accepted and read back.
An alarm's program is a favourite's `res` and `r:resMD`, which only `Browse FV:2` gives — the
Control API's `favorites:1` carries ids. The clock is `GetTimeNow` (`CurrentLocalTime`) and
`GetTimeZone` (`Index`, -1 for unset); this household is index 9, so local time is Central.

**A speaker's volume limit is readable here and not writable** (Kitchen, from the emulator,
2026-10-10). `settings:1 getPlayerSettings` carries `volumeScalingFactor` (0.0–1.0, 1.0 none) to
anyone; `setPlayerSettings` with it was refused **`ERROR_NO_PERMISSION`** to this app's unsigned
connection, while x2rock set and lifted the same speaker's limit seconds later. x2rock was signed in
to a Sonos account and sends `authorization: Bearer …` in every command header, so its note that the
write is "accepted" holds for a signed-in caller only; this app has no account, so it shows the limit
and does not offer to change it.

**A speaker's own switches and self-description** (Kitchen and Bedroom's Beam, 2026-10-10, T-08):
`DeviceProperties` `GetLEDState`/`GetButtonLockState` answer `On`/`Off` (lock On = touch controls do
nothing); the setters take `Desired…State` and light the light for any word but `Off` (x2rock), so
only the two words are sent. A soundbar's `HTControl` answers `GetLEDFeedbackState` (`LEDFeedbackState`)
and `GetIRRepeaterState` (`CurrentIRRepeaterState`), and its feedback setter alone takes
`LEDFeedbackState` with no `Desired`. `/xml/device_description.xml` carries About My System's fields
(`modelName`, `displayVersion`, `softwareVersion`, `serialNum`). `ZoneGroupTopology CheckForUpdate`
(`Software`, `CachedOnly` 1) offers an `UpdateItem` as text; **its `DownloadSize`, not its version,
says whether an update waits** — Dining Room's SYMFONISK pair runs 86.10-80260 and is offered 97.1
with `DownloadSize` 0. `/status/batterystatus` on a speaker on mains is an empty `<ZPSupportInfo>`;
no portable has been read here.

**Android's XML parser takes no Xerces features.** `DocumentBuilderFactory.setFeature` for
`disallow-doctype-decl` throws `ParserConfigurationException` on the Shield (Android 11), so
there no UPnP reply parsed and every fault read as a bare HTTP 500 — unseen because the JVM's
parser accepts the feature and the live suite runs there. Found 2026-10-03 when the alarm never
showed; the DOCTYPE guard is now a string check before the parser, and the queue listed on the
Shield the same afternoon.

**Apple Music plays from its public catalogue ids** (home, Dining Room, 2026-10-06). The iTunes
Search API's `trackId` and `collectionId` are the numbers behind the player's `song:` and
`album:` ids, so a search result needs nothing looked up:

- *Play*: `playback:1 loadContent` with `{"serviceId":"204","accountId":"sn_28","objectId":"song:1485581309"}`
  and `type` `track` (or `album:…` and `album`), never played in the household before. It replaces
  the queue; it cannot append (x2rock). The account is the household's, read from Recently Played
  or a playing track — favourites name none.
- *Add to queue*: UPnP `AddURIToQueue`, with the forms the player writes for itself:
  `x-sonos-http:song%3a<id>.mp4?sid=204&flags=8232&sn=28` for a song, and
  `x-rincon-cpcontainer:1004206calbum%3a<id>?sid=204&flags=8300&sn=28` for an album, which the
  player expands into its tracks; each with a DIDL carrying the cdudn
  `SA_RINCON52231_X_#Svc52231-0-Token` (x2rock's form).

**A `play` straight after `loadContent` can hang a speaker.** The same night: pause, load 12-39ms
later, and play ~30ms after that hung the Dining Room Symfonisk pair (86.10) entirely — port 1400
too — until it restarted itself two minutes later. Three times from the app, once from `:core`
alone; the same commands a second apart played every time. `replay` now waits 1.5s after the load
before its first press.

## The household's own service tokens, and searching with them

Every other music service the household has added — Qobuz, TIDAL, Deezer, Saavn, Amazon, Sonos
Radio — can be **searched and browsed** from the TV, with no account linking and no browser,
because the credential is never minted here: it is **read off a player**. The player publishes
every configured account, encrypted, in the **initial `ZoneGroupTopology` GENA event** under a
variable named `ThirdPartyMediaServersX`, and the key to it is derived from the household id,
which any device on the LAN can ask for. This is the SoCo #1010 technique; x2rock reimplemented
it in Rust (`src/sonos/stored.rs`), and this is the Kotlin port of that (`:core`'s `smapi`
package). Linking an account and `match` stay out — those do need a browser.

- **Capture** (`AccountCapture`): SUBSCRIBE to the player's `/ZoneGroupTopology/Event` on
  cleartext 1400 with a `CALLBACK` naming a listener the app opens for the moment; the player
  POSTs its initial state back; pull the one variable out; UNSUBSCRIBE. This is the one time a
  player reaches *out* to the app — a one-shot capture, not standing eventing. On a laptop a
  firewall must allow the inbound TCP; on Android the app's own listening socket needs no such
  rule.
- **Decrypt** (`StoredAccounts`): the value is `2:` + base64 of `iv + AES-128-CBC(…)`. The key is
  `md5(iv + md5(householdId + SALT))` with the fixed SALT from SoCo #1010 and the **short**
  household id (`Sonos_xxx`, *not* the long form the SMAPI header wants). Strip PKCS#7, then a
  trailing 4-byte `md5(payload)` is the integrity tail that tells a right key from a wrong one.
  Each `SA_RINCON<type>_…` element is one service account: `type / 256` is the service id,
  `SerialNum<i>` is the `sn_N`, `Token<i>`/`Key<i>` the credential, `Username<i>` the account
  selector.
- **Search / browse** (`Smapi`): SMAPI `search`, `getMetadata` and `getMediaURI` over the service's
  own endpoint from `ListAvailableServices`, with the stored token in the `loginToken` credentials
  header (the **long** household id goes here). Search categories come from the service's
  presentation map. **A `User-Agent` is required** — Deezer and Amazon answer an empty 500/200
  without one, which reads like a broken endpoint (x2rock, `sonos/http.rs`).
- **Play**: `loadContent` with the item's `{serviceId, accountId: sn_N, objectId}` — the same path
  Recently Played and Apple Music use. Queueing — at the end, or next — is UPnP `AddURIToQueue`;
  see the next section.

**Verified against the office household, 2026-10-06** (`StoredAccountsLiveTest`, opt-in
`-Dx2rock.capture=<player-ip>`, read-only, prints only byte lengths and public titles). The
Kotlin capture + decrypt reproduced x2rock's `--from-household` **byte-for-byte**: 11 records,
Qobuz token 86/key 8, TIDAL 805/376, Deezer 50/6, and the rest. A credentialed `search` for
"miles davis" then returned real hits — Deezer 30, Qobuz 1000, Saavn 4, Sonos Radio 2 programs —
with titles, artists and item types parsed. Amazon Music answered its `search` HTTP 500 (x2rock
saw the same); its catalogue is finicky. **Not yet run on a TV device**: the inbound GENA socket
and `.local`→IP path want one run on a Shield/Streamer, as the mDNS fallback did — the capture is
the same JVM code either way.

*Since run on TVs*: the stored-token capture, search and browse are what Music Services and Search
use on the Shield and the Streamer — the service-by-service comparison of 2026-10-11 ran on the
Shield (`docs/sonos-app-comparison.md`), and on 2026-10-18 a build with a deliberately wrong salt
showed the "sign-ins could not be read" note there while the normal build listed the accounts again.

---

## A service item, beyond play and search (2026-10-08)

Learned comparing the app with the Sonos app (89.01, a phone) on the office household, each run
against real services, Media Room playing.

- **The Sonos app's per-item actions are `getExtendedMetadata`.** Browse Artist and Album Info are
  the item's own `artistId`/`albumId` (in `trackMetadata` for a track — Deezer, Qobuz and Saavn
  alike); Start Radio is a `relatedPlay` (a `program`, Deezer's "Mix …", Saavn's "… Radio");
  "Add to favourites" is `canAddToFavorites`, with the state in `dynamic/ISFAVORITE` where the
  service says (Deezer does, Qobuz does not). Captures: `fixtures/getExtendedMetadata.*.xml`.
- **A service favourite is set one of two ways.** Deezer's `NowPlayingRatings` are its heart:
  `rateItem` with `SAVE_TRACK` (1) or `DELETE_TRACK` (0), and its ban is `SKIP_TRACK` (3, which
  skips). Qobuz has no such ratings and takes SMAPI `createItem`/`deleteItem` with a `favorite`.
  Both were set and cleared on the real accounts and read back.
- **"Play next" is a slot.** `AddURIToQueue` with `DesiredFirstTrackNumberEnqueued` = the playing
  track (`GetPositionInfo`'s `Track`) + 1 put Drive My Car in slot 2 behind Michelle. Only while
  the queue is the source (`GetMediaInfo` `x-rincon-queue:`); on a station the track number reads
  1 and means nothing, and the Sonos app offers no Play Next there either.
- **`favorites:1 loadFavorite` appends an album unless told `REPLACE`.** Saavn's Irumudi on a
  one-track queue left five tracks, pressed again nine; with `"action": "REPLACE"` the queue was
  the album's four. A station favourite loads the same with it. x2rock recorded the opposite
  ("replaces without being asked"), true for what it was tried on.
- **A favourite's `objectId` is not always the service's id.** Containers carry the queue's
  prefix: `1004206cALBUM:79488223`, `1006706cplaylist:109815423`. Saavn answers either whole with
  `Item not found` and lists both with the eight characters taken off.
- **A service may have a page SMAPI does not.** Sonos Radio's SMAPI `root` is empty, but its
  manifest names `"endpoints":[{"type":"browse","uri":"https://sali.sonos.superhi.fi/browse/v1"}]`,
  which answers with **no credential at all**: 28 `views` (Trending Now, Sonos Presents, moods,
  genres), each with its stations inline as `trackList.program` items whose ids are the ones its
  SMAPI search returns (`sonos:2997` is Hit List in both). That is the Sonos app's Sonos Radio
  page. A view's own id is not fetchable by any form tried (path, `?objectId=`, `?id=` — 404 or
  the root again), so a view with nothing inline ("Browse Radio") is not reachable yet. Deezer,
  Amazon Music and Apple Music declare a `browse` endpoint too, and answer 400/500 without a
  credential; what they take was not tried.

---

**Adding to an account's own playlist** (Deezer, home, 2026-10-10, owner-approved, with a scratch
playlist made and deleted three times; the account's one real playlist untouched). Nothing on a
track says it can be added to a playlist — `getExtendedMetadata` has no such field — so it is the
*containers* that say where: a `mediaCollection` carries `userContent="true"` when it is the
account's own and `readOnly="false"` when it may be written. Deezer's root has **My Music**
(`mylibrary`, own, read-only), which holds **My Playlists** (`playlists`, own, **writable**), whose
playlists are each `readOnly="false"`. `addToContainer(id, parentId, index, updateId)` puts a track
in; Deezer leaves `updateId` empty in every reply and takes an empty one; **an index equal to the
playlist's count appends** (a second track at 1 followed the first at 0). `createContainer` answers
the bare `playlist-…` id while listings name the same playlist `playlist_spotify:playlist-…`, and
`addToContainer` takes the listed form. `removeFromContainer(id, indices, updateId)` and
`deleteContainer(id)` cleaned up. **YouTube Music refused every SMAPI call from outside a player**
(403, "unregistered callers"), so it was not probed; Qobuz is not linked at home.

**Sonos Radio's "Browse Radio" opens over SMAPI** (2026-10-11). Its page, from the manifest's
`browse` endpoint, lists four rows (Music, News & Talk, Sports, Locations) with `canPlay` false,
`canEnumerate` true and path ids like `/stations/en-US/US/EgdVUzpHOjIy`. The browse endpoint ignores
an id in its query and 404s one in its path; **`getMetadata` on Sonos Radio's SMAPI takes the path
id as it stands**, with no token: News & Talk answered 5,637 `stream`s (iHeart's, `ihr:` ids), Music
17 genre containers in the same path form.

**A room's Sub and surrounds are named by `zones:1`** (home, 2026-10-11). `activeZonesChange`, which
the household already follows for disconnected satellites, gives every member's `channelMap`: Living
Room's Beam `LF,RF` with a Sub `SW` and surrounds `LR` and `RR`; Bedroom's Beam with `LR` and `RR`;
Dining Room's pair `LF,LF` and `RF,RF`. `settings:1 getPlayerSettings` carries no Sub or surround
field at all, so the levels are RenderingControl `GetEQ`/`SetEQ` on the room's own player:
`SubEnable`, `SubGain`, `SurroundEnable`, `SurroundLevel` (TV), `MusicSurroundLevel`. Living Room read
1/0/1/0/0; `SubGain` stepped to 1 and back read back each time. Kitchen (One SL) answers `SubGain` 0
and refuses the surround types, as x2rock's 2026-09-05 sweep found.

**A bonded satellite is not a household speaker for UPnP** (home, 2026-10-11). Discovery hears subs,
surrounds and a stereo pair's second half, and a session can be seeded from one. Asked `Browse FV:2`,
every satellite answered HTTP 500 (.33, .34, .35, .39, .44) and every room's own player 200, so calls
that any speaker could answer go to a room's player (`householdSpeaker`).

**A service's own page takes its account's token as a bearer** (home, 2026-10-11). The manifest's
`browse` endpoint, sent `Authorization: Bearer <the SMAPI token>`, answers Spotify's personal page
(`spotify-v5.ws.sonos.com/v1/browse`, 22 views — "More like <artist>", "Recommended for today", "Your
top mixes" — where SMAPI's root has 5 rows) and Deezer's (`platform.deezer.com/api/sonos/browse`, 9:
Favorites, Recently played, the Flow moods, Made for you, Charts). The JSON is Sonos Radio's shape.
Spotify's items carry **no `browsePolicies`**; Deezer's albums say `canPlay` and `canEnumerate` both.
Every view id and item id tried opens with SMAPI `getMetadata` (albums, playlists, artists, Deezer's
`flow` program), except Deezer's `user-recentlyplayed` view (`Client.ItemNotFound`) and Spotify's
"Your Library" `playlists` (empty) — so a shelf opens on the items it carries inline. **A stale token
is answered 401**, not with a refresh fault: Spotify's page went from 200 to 401 within the hour, and
one SMAPI call (which takes the `tokenRefreshRequired` replacement) then lets the page answer the new
token. Amazon Music answers its endpoint 401 to any token and Apple Music 500. TuneIn (New) answered a
Python client 403 (Cloudflare 1010) and OkHttp 200 with the bearer (2026-10-12): 19 views, its stations
typed `station` (canPlay, not canEnumerate) under the ids its SMAPI search calls `stream`; one played in
Dining Room as a stream. Sonos Radio's page refuses a token and is asked without one. TuneIn's page and
its SMAPI root both carry Music, News & Talk and Sports, under different ids (2026-10-12), so the root's
entries are appended after the shelves by **title** as well as id.

**A household token can go stale with no refresh offered** (x2rock, home, 2026-10-12). Pandora's,
read a day earlier, was refused with only a message, "Failed to reauth device id", while the Sonos app
kept the household's own copy fresh; reading the account store again gave a working one. So a refusal
that reads as the credential's (`login`, `auth`, `token`, `linked`, `expired` in its code or message;
no code is common to them) and carries no `refreshAuthTokenResult` reads the store once more, matches
the account by service and `SerialNum`, and retries once with its token if it differs. One read serves
every refusal for ten minutes, failed reads included: it asks a player to call back into the device.

**A SoundCloud album lists nothing** (home, 2026-10-12): search returns it `canPlay`, and its own
`getMetadata` answers `total` 0. The Sonos app's page for one shows no tracks and no Play, and its menu,
once loaded, offers only Save to Favorites and Browse Artist. A container that lists nothing is not
offered Play here either.

**Current tracks can be saved as Sonos Favorites over UPnP** (T-31, home, 2026-10-16).
`ContentDirectory CreateObject`, `ContainerID FV:2`, accepts an `object.item.sonos-favorite`
wrapper with `r:type instantPlay`, the actual `GetPositionInfo.TrackURI` as its resource, and
`TrackMetaData` escaped in `r:resMD`. The returned `ObjectID` confirms creation. This changes
neither playback nor the queue. TIDAL's “So What” was created, listed by `getFavorites`, and
seen in the Sonos phone app. The release TV app on an API 34 emulator subsequently saved
Saavn’s “God Mode (From Karuppu)” against the office One SL (Media Room), showed the
already-saved notice on a repeat press, and listed it under Browse → Favorite songs. Rust
`x2rock favorites --json` also reported it as `TRACK`, `Saavn`; playback remained stopped.

The current metadata may have `id="-1"` and no `cdudn` after `loadContent`. Saving it unchanged
produces an untyped favorite that the phone hides. Supply a missing id as `00032020` plus the
percent-encoded current track's Control API `objectId`, and a missing descriptor as
`SA_RINCON<advertised serviceType>_X_#Svc<serviceType>-0-Token`. Preserve existing metadata,
including any account descriptor and the resource. The advertised type comes from
`ListAvailableServices`, not a guessed account credential. Captures: `GetPositionInfo.tidal.xml`,
`ListAvailableServices.favorite.xml`, `CreateObject.favorite.xml`, `Browse.FV2.created-track.xml`.
The save action checks the selected track before and after these reads, checks existing favorites
by URI or service/catalogue identity (including shortcuts), and never automatically retries a write.
This was verified for individual service tracks, not arbitrary containers or radio sources.

**A service item is saved as a queue is given it** (T-32, office One SL, 2026-10-16). The same
wrapper, with `ServiceContent.enqueueUri` as the resource (protocol `<its scheme>:*:*:*`) and
`enqueueDidl` in `r:resMD`, makes a favorite `getFavorites` types and names: a Saavn album as
`x-rincon-cpcontainer:1004206cALBUM%3a48037104?sid=164&flags=8300&sn=13` (the shape the Sonos app
wrote for another album, Irumudi) is `ALBUM`, Saavn, `sn_13`; a track as
`x-sonosapi-hls-static:TRACK_NEW%3a-sJuFNFc?sid=164&flags=65544&sn=13` is `TRACK`. A track written as
`x-sonos-http:` with no file extension was saved untyped, with no service; with `.mp4` and a
`sonos.com-http` protocol it was typed — the queue's own scheme needs neither.

**803 is "already a favorite".** `CreateObject` answers it whenever the item is already in `FV:2`,
under any title and any form of its URI (with and without an extension, `%3A` or `%3a`): once the
first copy went, the same request succeeded, and a second answered 803 again. That is what every
803 of the night before was — copies of favorites that were there — not a refusal to create.

**`DestroyObject` on `FV:2/<n>` removes one**, with an empty reply; one already gone answers **701**.
Captures: `CreateObject.serviceTrack.xml`, `CreateObject.exists.fault803.xml`,
`DestroyObject.favorite.xml`, `DestroyObject.gone.fault701.xml`.

**Today's phone-created favorites are also shortcuts the Control API does not list.** What the
Sonos app wrote for an album ("Kind Of
Blue (Legacy Edition)", TIDAL) is `r:type` **`shortcut`** with an **empty `<res>`** — older favorites are
`instantPlay` with a URI — and the item only in `r:resMD`: id `10082064album__UNENCODED_SLASH__2178485`
(an 8-hex prefix, then the service's own id with `/` written `__UNENCODED_SLASH__`), class
`object.container.album.musicAlbum`, cdudn `SA_RINCON44551_X_#Svc44551-0-Token` (TIDAL's type). **The
Control API's `favorites:1 getFavorites` leaves it out**: eight favorites there, nine in `Browse FV:2`. It
plays through the service path as any album does (`album/2178485`, TIDAL, Dining Room).

An artist and a song saved the same way are shortcuts too (`static/artist/55`, class `…person.musicArtist`;
`track/2178486`, `…audioItem.musicTrack`); a Sonos Radio station saved alongside is still `instantPlay`
and still listed. `loadFavorite` of a shortcut's id answers `ERROR_INVALID_OBJECT_ID`. The ids are the
service's SMAPI ids as they stand: TIDAL's browse opens both the album and the artist on them. The service
is the cdudn type over 256 (44551 → 174); the account is not named (`-0-Token`). So Browse lists them
after `getFavorites`' own (`Upnp.favoriteShortcuts`), each with the household's account for its service:
an album or artist opens on its service's page, an artist without Play; a song, or an album from a preset,
plays through `loadContent` when `loadFavorite` refuses its id. x2rock filters shortcuts out as Sonos Radio
navigation entries, which they also are; only one pointing at an album, artist, song or playlist is kept.

**Plex plays through `loadContent`** (home, 2026-10-12, Kitchen): a track, an album and Shuffle All
each started within 5-7s. The track came up as `x-sonos-http:…track.unknown?…&flags=0`, where the
queue path writes `track.mp3?…&flags=24616`; it played regardless.

## Recorded by x2rock, and relied on here

The sibling project measured these on its own household's hardware between 2026-09-04 and
2026-10-01. They are protocol facts rather than facts about one house, so they are kept here
with the rest; where this repo has since seen one for itself, that is said, and where the code
leans on one, the place is named.

**Messages and namespaces**

- **A namespace resolves by prefix** — `home:1` answers as `homeTheater:1` — so read the
  namespace echoed in the reply's header rather than assuming the one sent. 41 namespaces were
  enumerated on API 1.54.1; `info:1 getInfo` gives the player's `restUrl`, its API version and
  its capabilities.
- **An event that omits a field is not setting it false.** Merge each one into what is held
  (`SonosHousehold` does, field by field) rather than replacing it.

**Volume**

- **Both volume setters unmute** — `setVolume` and `setRelativeVolume`, group and player. A
  muted room stepped by a remote press therefore unmutes as it moves, which is the behaviour
  the player pane leaves to the speaker rather than reproducing. Seen here too, on the Shield
  (punch list 1.9).
- **A second volume command within about 260ms is deferred**, and `getVolume` reads stale
  inside that window, so a read straight after a write can report the old level. Never treat a
  read-after-write as confirmation; the pushed event is the answer. The view models' 300ms
  volume debounce keeps a held key from sending a second command inside that window at all.
- **A group's volume is its members' average**, and moving the group scales the members. The
  room panel draws both without doing the arithmetic, because both arrive as events.

**Playback**

- **A live stream is known only by `container.type == "station"`** (`isRadio`). Nothing in
  the track says so.
- **TuneIn sends `canPause: false`, and a pause sends it to IDLE**, not PAUSED; Sonos Radio
  sends `canPause: true`, and resuming restarts the track. `PlaybackActions` reads
  `canPause` as a fact for that reason.
- **`queueVersion`** was absent on the firmware x2rock measured, but is sent by the office
  One SL and the home household, and appears in this repo's captures (punch list 3.5). UPnP's
  `UpdateID` is the signal known to work everywhere. A queue item's `itemId` is its 1-based
  position while the queue is driving and an opaque hash otherwise, and **it renumbers on
  every edit** — never hold one across an edit.

**Home theatre**

- **`homeTheater:1 subscribe` succeeds and never fires.** Night Sound and dialog travel only
  in UPnP eventing's `LastChange`, which this app does not use, so they are re-read after a
  write rather than waited for. `getPlayerSettings` reflects a `SetEQ` write at once, which
  is what makes that re-read correct.
- **`homeTheater:1 loadHomeTheaterPlayback` switches a solo soundbar to its TV** over the
  Control API. A grouped room needs the UPnP route described under "Switching a room to its TV
  input" in `CLAUDE.md`, which is the one this app uses for both.
- **`DialogLevel` is 0 or 1 on a Beam**; 1-4 exist only on hardware neither household has.

**The household's security switches**

- They are the `effectiveSettings:1` security group: **Authentication**, **UPnP** and **Guest
  Access**.
- **Authentication on: every Control API command answers `ERROR_NO_PERMISSION`**
  (`SonosCommandException.isPermissionRefusal`), `getGroups` first, while UPnP keeps answering.
- **UPnP off: every SOAP call answers HTTP 403**, while the Control API and `getaa` album art
  keep working. `Upnp.switchedOff` follows the switch from `effectiveSettings:1` so the pane can
  say why the queue and TV input are gone before anyone presses them.

**The player itself**

- **The TLS certificate** is leaf-only, `CN=<MAC>`, with SANs `sonos-<MAC>.local`,
  `urn:sonos:udn:RINCON_…` and `sonos-<MAC>.smartspeaker.audio`; valid for about six months;
  issued by "Sonos Device Authentication Root CA". See "TLS" above for what that costs.
- **Open ports:** 1400 (UPnP, cleartext), 1410 (answers 404 to everything), 1443 (the
  Control API) and 7000 (AirPlay).

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
