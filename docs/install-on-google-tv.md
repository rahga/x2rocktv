# Installing a test build on Google TV

Test builds of x2rocktv are not on Google Play yet. Each one is an APK attached to a pre-release at
<https://github.com/rahga/x2rocktv/releases>, and is installed by hand ("sideloaded"). The TV has to
be on the same network as the Sonos speakers. No Sonos account is needed or asked for.

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

Turn on Developer options as above, then **Developer options → USB debugging** (or **Wireless
debugging**, which some devices need pairing for), and from a computer on the same network:

```sh
adb connect <tv-address>
adb install -r x2rocktv-<version>.apk
```

## Updating

Install the newer APK the same way; it replaces the old one and keeps your settings. Every test
build is signed with the same key, and a later Google Play release will be too.

## Trying what is new, and reporting

**Settings → Beta features** shows what has never run on real hardware of that kind, each marked
"Beta" where it appears. **Send a report**, in the same place, shows a QR code: scanned with a phone,
it opens a prefilled GitHub issue with the speakers' models and versions and nothing that identifies
your home. Nothing is sent unless you submit it.
