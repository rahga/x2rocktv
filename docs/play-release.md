# Releasing: GitHub pre-releases now, Google Play later

How a release goes from a tag to a tester (T-12). Testers sideload an APK from a GitHub pre-release
until the Play account exists; see [installing on Google TV](install-on-google-tv.md). Sideloading is
allowed on Google TV as it stands: Google's developer verification reaches TV devices from 2027, and
an `adb` install stays exempt even then. The steps marked **owner** are the key holder's and the Play
Console's, and cannot be done from here.

## Checked against Play's rules (2026-10-12)

- **Target SDK**: Play's rule from 31 August 2026 is API 34 or higher for Android TV apps (36 for
  phones). The app targets 35.
- **TV**: `android.software.leanback` required, touchscreen not; a 320×180 banner; a
  `LEANBACK_LAUNCHER` activity; every screen works on a D-pad.
- **Hardware that is implied, not declared**: Play filters on it. The multicast permission implied
  Wi-Fi was required and the landscape activity implied a landscape screen; both are declared not
  required, so a box on Ethernet only still sees the app. Check with
  `aapt dump badging app-release.apk | grep feature`: only `android.software.leanback` may be
  required.
- **Format**: new apps upload an Android App Bundle, `app/build/outputs/bundle/release/app-release.aab`.

## The release key (made 2026-10-16)

One key signs every build that leaves the developer's machine: `~/x2rocktv-release.jks`, alias
`x2rocktv`, RSA 4096, valid to 2126, SHA-256 fingerprint
`8D:65:75:AB:21:AB:AF:BF:7F:E2:5F:CC:A2:A5:65:50:DA:5D:FF:3F:8A:A0:5C:B6:9D:BB:FD:35:5F:12:0F:04`.
`install-on-google-tv.md` checks builds against it too (in lower case, without colons): change
both if the key ever changes.
Its password is in `keystore.properties` at the repo root, which git ignores:

```properties
storeFile=/home/<you>/x2rocktv-release.jks
storePassword=...
keyAlias=x2rocktv
keyPassword=...
```

**Owner: back up the keystore and that file together, somewhere off this laptop.** A TV refuses an
update signed by another key, so a lost key means every tester uninstalls, losing their settings.

**On Play, give it as the app-signing key** ("use my own key" in Play App Signing), not as an upload
key only. Then a build from Play carries the same signature as the APKs testers sideloaded, and
updates them in place.

**Both house TVs now use the release key** (2026-10-17). Build updates for them with
`-Px2rock.upload`; plain `assembleRelease` still uses the debug key and will be refused there.
Keep their settings by updating in place. A device still on a debug key requires a one-time
uninstall before moving to the release key, which clears its settings.

## Each release

1. Move `versionCode` up by one in `app/build.gradle.kts` if a build has left the machine since the
   last, and write the release's section in `CHANGELOG.md`, including what is still untested on
   hardware.
2. Commit, then tag: `git tag -a v0.1.0 -m "0.1.0"`. The tag is the version name.
3. Build with the release key: `./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
   :app:bundleRelease -Px2rock.upload`. The APK is for testers, the bundle for Play.
   The maintainer's distributed build includes the salt from `~/x2rock-sonos.speaker.salt`, used
   for reading LAN service sign-ins and public SMAPI. It is separate from the private integration
   and cloud-content flags; a source build without that file intentionally has fewer services.
4. Push the commit and the tag, then make the pre-release with the APK, named
   `x2rocktv-<version>.apk`, and the changelog's section as its notes:
   `gh release create v0.1.0 --prerelease --title "x2rocktv 0.1.0" --notes-file … <apk>`.
   Before uploading, check the APK is not a private build: `aapt2 dump badging <apk> | grep versionName`
   must not say `+hardcore` (a build with `-Px2rock.sonosIntegration` or `-Px2rock.cloudContent`, which
   carries untracked code and is for the owner's own TVs only).
5. Later, **owner**: upload the bundle to Play's closed test track, with the same notes.

## Validated artifacts

Each published APK is checked as downloaded, never as rebuilt. The owner's `+hardcore` builds are a
different artifact and are not recorded here.

**`x2rocktv-0.1.3.apk`** (2026-10-19), SHA-256 `528cdc87…dd17e194`:
- Release key (cert SHA-256 `8d6575ab…5f120f04`, as before). Version `0.1.3`, code 6, minSdk 26, target 35.
  Leanback is the only required feature; both a leanback and a phone/tablet launcher entry.
- Nothing private (no integration key, secret, `callback.html` or cloud page source); the speaker salt
  is present, as `BuildConfig.SPEAKER_SALT`. The downloaded asset is byte-identical to the one tested.
- Installed fresh on the API-34 Google TV emulator (first connect timed out once, Retry connected) and
  on a moto g 5G 2024 (Android 15): opens from the launcher, connects, clear of the system bars.
- **On a Chromecast with Google TV (4K, 2020, Android 14)**, the downloaded APK installed fresh: in its
  launcher; cold start 1.4s, warm 0.1s; connected first time; Browse, Music Services (signed-in
  services by account), Search ("miles davis": 118 results from 17 services), a 39-track queue, both
  halves of the room panel (tone read off the speaker) and the report (no identifiers in its QR) all
  worked by remote. Nothing played or changed; Beta switched back off.
- **Stored sign-ins on the artifact itself** (the gap left open for 0.1.2, P-50): on that phone, on the
  office LAN, Music Services listed the household's signed-in services by account (Amazon Music by its
  user name, Audible, Deezer, iHeartRadio), and Deezer opened on its own Favorites and Recently played.

**`x2rocktv-0.1.2.apk`** (2026-10-18, T-35), SHA-256 `c35bb4d7…6e017fe2`:
- Signed v1+v2 by the release key (`CN=Richard Hoelscher, O=x2rock`, cert SHA-256 `8d6575ab…5f120f04`,
  the same as 0.1.1's). Version `0.1.2`, code 5. minSdk 23 (raised to 26 after it, P-39), target 35.
  `android.software.leanback` is the only required feature, and it has a leanback launcher entry.
- Nothing private: no integration key or secret, no `callback.html`, no cloud page source or its
  service file. It carries the speaker salt: 0.1.2 predates the salt leaving the source, and its
  `byteArrayOf` matches `~/x2rock-sonos.speaker.salt` and is present in `classes.dex`.
- On the API-34 emulator at home: a fresh 0.1.1 install found the household with no seed, and 0.1.2
  updated it in place with its state kept. Cold start 0.93s, warm 0.11s (`am start -W`). Browse
  (favourites), Music Services, Search ("miles davis": 55 results from 8 services), Kitchen's queue
  and Send a report (no ids, addresses or `sn_` in the decoded QR) all worked.
- **Not shown on the artifact itself:** services that need the household's stored sign-ins. The
  emulator's NAT drops the speaker's connection back to the app, and a release build takes no debug
  seed, so only Apple Music and the anonymous services were listed. The salt check above is the
  evidence they would decrypt. Seeing them needs the APK on a real device on the LAN that is not
  one of the owner's TVs.

## The listing (owner, once)

- **Privacy policy**: [`PRIVACY.md`](../PRIVACY.md), by its GitHub address.
- **Data safety**: the facts are in `PRIVACY.md`. Nothing goes to the developer; search terms go to
  the music services the household already uses, and a report goes to GitHub only if the tester
  submits it. How Play's form classes those is the owner's call.
- **Graphics** (first run 2026-10-18, from `~/x2rock.png`; remake from a better master when there is
  one): `docs/images/play/icon-512.png` (the brushed X2), `feature-1024x500.png` (wordmark and "Every
  room, on the big screen"), `tv-banner-1280x720.png` (the wordmark, as the in-app banner). White on
  black, as the app's icon and banner. The master is 752×229, so the wordmark is upscaled about 1.1×
  in the banner. TV screenshots at 1920×1080 are in `docs/images/`.
- **Closed test**: a new personal developer account needs at least 12 testers opted in for 14 days
  before it can apply for production. Testers are added by email or Google Group in the Console,
  never in a public issue. The group is `x2rock@googlegroups.com` (2026-10-20): anyone may join,
  only managers see members or post. Add it to the closed track, and put the track's opt-in link in
  the README beside the group's.

Rechecked 2026-10-18: those are **14 consecutive days**, not weeks, and testers must remain opted
in continuously. Applying for production includes questions about testing, feedback and engagement;
meeting the count and time alone does not grant production access. GitHub sideload testers do not
count as Play closed-test opt-ins.
[Google's testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465).
An organization account requires organization/identity verification, including a D-U-N-S number
in the usual case; obtaining the number alone is not Play approval.
[Google's verification requirements](https://support.google.com/googleplay/android-developer/answer/10841920).

The [2026-10-18 release-readiness review](review-2026-10-18-codex.md) records proposed fixes and
checks before wider distribution, including a bypass in the current private-bundle guard. Until
that is resolved, use the full task names above and inspect the actual artifacts before upload.
