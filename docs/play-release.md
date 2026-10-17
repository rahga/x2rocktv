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

**The house's TVs still take debug-key builds** from a plain `assembleRelease`, so `adb install -r`
keeps working on them. A TV moves to the release key once, by uninstalling first (which clears its
TV room, presets and preferred services), and from then on takes `-Px2rock.upload` builds.

## Each release

1. Move `versionCode` up by one in `app/build.gradle.kts` if a build has left the machine since the
   last, and write the release's section in `CHANGELOG.md`, including what is still untested on
   hardware.
2. Commit, then tag: `git tag -a v0.1.0 -m "0.1.0"`. The tag is the version name.
3. Build with the release key: `./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
   :app:bundleRelease -Px2rock.upload`. The APK is for testers, the bundle for Play.
4. Push the commit and the tag, then make the pre-release with the APK, named
   `x2rocktv-<version>.apk`, and the changelog's section as its notes:
   `gh release create v0.1.0 --prerelease --title "x2rocktv 0.1.0" --notes-file … <apk>`.
5. Later, **owner**: upload the bundle to Play's closed test track, with the same notes.

## The listing (owner, once)

- **Privacy policy**: [`PRIVACY.md`](../PRIVACY.md), by its GitHub address.
- **Data safety**: the facts are in `PRIVACY.md`. Nothing goes to the developer; search terms go to
  the music services the household already uses, and a report goes to GitHub only if the tester
  submits it. How Play's form classes those is the owner's call.
- **Graphics**: the TV banner (1280×720) and the feature graphic (1024×500) are still to make, from
  the wordmark; TV screenshots at 1920×1080 are in `docs/images/`.
- **Closed test**: a new personal developer account needs at least 12 testers opted in for 14 days
  before it can apply for production. Testers are added by email or Google Group in the Console,
  never in a public issue.
