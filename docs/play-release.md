# Releasing on Google Play

How a release goes from a tag to Play's closed test (T-12). The build side is done in this repo; the
steps marked **owner** are the Play Console's and the key holder's, and cannot be done from here.

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

## The upload key (owner, once)

Play signs what it delivers with its own key; the upload key only proves an upload is yours. Make it
once, keep it and its passwords somewhere backed up, and never commit either:

```sh
keytool -genkeypair -v -keystore ~/keys/x2rock-upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then `keystore.properties` at the repo root (ignored by git):

```properties
storeFile=/home/<you>/keys/x2rock-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

**The house's TVs keep the debug key.** Release builds are signed with it unless the upload key is
asked for, so `adb install -r` keeps working on them. A TV that installs x2rock from Play gets Play's
signature instead, and the two cannot update each other: moving a TV from one to the other means
uninstalling first, which clears its settings (the TV room, presets, preferred services).

## Each release

1. Move `versionCode` up by one in `app/build.gradle.kts`, and write the release's section in
   `CHANGELOG.md`, including what is still untested on hardware.
2. Commit, then tag: `git tag -a v0.1.0 -m "0.1.0"`. The tag is the version name.
3. Build: `./gradlew :core:test :app:testDebugUnitTest :app:bundleRelease -Px2rock.upload`.
4. **Owner**: upload the bundle to the closed test track, with the changelog's section as its notes.
5. Push the tag: `git push origin v0.1.0`.

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
