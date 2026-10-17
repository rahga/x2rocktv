# Installing a test build on Google TV

Test builds of x2rocktv are not on Google Play yet. Each one is an APK attached to a pre-release at
<https://github.com/rahga/x2rocktv/releases>, and is installed by hand ("sideloaded"). The TV has to
be on the same network as the Sonos speakers. No Sonos account is needed or asked for.

**Have an AI coding agent** (Claude Code, Codex and the like) on a computer on the same network? Give
it this, and it can do the rest with you:

> Install the latest x2rocktv test build on my TV, following
> https://raw.githubusercontent.com/rahga/x2rocktv/master/docs/install-on-google-tv.md

It will still need you at the TV once, to turn on debugging and pair it with the computer: on
some TVs that means reading out a code, on others accepting a prompt.

## With the Downloader app (no computer)

1. On the TV, install **Downloader** (by AFTVnews) from the Play Store.
2. Turn on Developer options: **Settings → System → About**, then select **Android TV OS build**
   seven times, until it says you are a developer.
3. Let Downloader install apps: **Settings → Apps → Security & restrictions → Unknown sources**, and
   switch on Downloader. Menu names vary a little between devices; on some it is under
   **Settings → Privacy → Security & restrictions**.
4. Open Downloader, type `github.com/rahga/x2rocktv/releases` into its address box, and choose the
   newest release's `x2rocktv-<version>.apk`. Downloader offers to install it when it has downloaded.
5. x2rocktv appears under **Your apps**. Downloader can delete the APK afterwards.

## With adb (from a computer)

Turn on Developer options as above, then debugging, and from a computer on the same network:

```sh
adb connect <tv-address>:5555
adb install -r x2rocktv-<version>.apk
```

That is for TVs with **ADB debugging** or **Network debugging** (Fire TV, an NVIDIA Shield, older
Android TV); accept the prompt on the TV when it appears. TVs with **Wireless debugging** instead (the
Google TV Streamer, an onn stick, most newer Google TV devices) refuse port 5555 and need pairing
first: see step 3 below.

## For an AI agent doing this

Steps for an agent asked to install x2rocktv on someone's TV. Ask the person before each step on the
TV itself, and change nothing else there.

1. **Get the newest build.** Test builds are pre-releases, which GitHub's `/releases/latest` leaves
   out, so take the newest release in the full list that has an APK:
   ```sh
   url=$(curl -fsS https://api.github.com/repos/rahga/x2rocktv/releases \
     | jq -r 'first(.[].assets[] | select(.name | endswith(".apk")) | .browser_download_url)')
   apk=${url##*/}
   if [ -n "$url" ]; then curl -fsSLo "$apk" "$url" && echo "$apk"; else echo "no APK found"; fi
   ```
   Stop if it prints `no APK found` or `curl` reports an error (GitHub limits API calls without a
   token to 60 an hour). Use the file name it printed below, not a wildcard: an older APK may sit
   in the same folder.
2. **Check it is ours.** Every build is signed with the same key; refuse to install one that is not.
   With the Android SDK's `apksigner`:
   ```sh
   certs=$(apksigner verify --print-certs "$apk") \
     && grep -q 'certificate SHA-256 digest: 8d6575ab21abafbf7fe25fcca2a56550da5dff3f8aa05cb69dbbfd355f120f04' <<<"$certs" \
     && echo "signed by x2rocktv" || echo "NOT x2rocktv's key: do not install"
   ```
   (The key is described in [play-release.md](play-release.md); its fingerprint there is the same,
   written in capitals with colons.)
3. **Connect.** The person turns on Developer options (above), then debugging, and reads you what
   the TV shows. Which switch there is depends on the device, not only its Android version:
   - **Wireless debugging** (the Google TV Streamer, an onn stick, most newer Google TV devices):
     **Pair device with pairing code** shows an address, a port and a six-digit code.
     `adb pair <address>:<pairing port> <code>`, then `adb connect <address>:<port>` with the port
     shown on the Wireless debugging screen itself, which is a different one. A plain
     `adb connect <address>:5555` is refused on these.
   - **ADB debugging** or **Network debugging** (Fire TV, an NVIDIA Shield, older Android TV): switch
     it on, then `adb connect <address>:5555`, and the person accepts the prompt on the TV. There is
     no code to read.

   Then `adb devices`. Use the TV's entry there as `<serial>` in every command below: adb may list
   the TV a second time under an `adb-…._adb-tls-connect._tcp` name (either works), and any other
   device it lists makes a command without `-s` fail.
4. **Install**: `adb -s <serial> install -r "$apk"`. An update keeps the person's
   settings. If it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, an x2rocktv signed with another
   key is installed: say so, and uninstall it only if the person agrees (that clears its settings).
5. **Open it**: `adb -s <serial> shell am start -n com.rahga.x2rock/.MainActivity`. It should list the Sonos
   rooms within seconds. If it finds none, the TV and the speakers are probably on different networks.
6. The person can switch debugging off again afterwards; x2rocktv does not need it.

## Updating

Install the newer APK the same way; it replaces the old one and keeps your settings. Every test
build is signed with the same key, and a later Google Play release will be too.

## Trying what is new, and reporting

**Settings → Beta features** shows what has never run on real hardware of that kind, each marked
"Beta" where it appears. **Send a report**, in the same place, shows a QR code: scanned with a phone,
it opens a prefilled GitHub issue with the speakers' models and versions and nothing that identifies
your home. Nothing is sent unless you submit it.
