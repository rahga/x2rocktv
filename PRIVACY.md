# Privacy

x2rock is a Sonos controller for Google TV and Android TV. It has no account, no server and no
analytics of its own, and its developer receives nothing from it.

## What it talks to

- **Your Sonos speakers**, on your local network, directly. This is where nearly everything goes:
  what to play, volume, grouping, settings.
- **The music services your Sonos system already has**, at each service's own address, using the
  sign-in your Sonos system already holds for it. That is how browsing and searching a service
  works. What you search for goes to those services, as it would from the Sonos app.
- **Apple's public iTunes search**, when you search, for Apple Music results.
- **radio-browser.info**, a public directory of radio stations, when you browse Radio.
- **Sonos's media server**, for music services' logos, and wherever a track's or station's cover art
  is hosted, to show it.

## What it keeps

On the TV device only, in the app's own storage: the speaker it last connected to on each network,
the room you named as the TV's, your theme, preferred services and which account of a service to
use, presets, recent searches, and whether Beta features is on. Uninstalling the app removes all of it. Nothing is backed up off the
device.

## Reports

**Send a report** (Settings → Beta features) shows a report on screen and a QR code. Scanning it
opens a draft issue on GitHub on your phone; nothing is sent unless you submit it there, and GitHub
issues are public. The report holds your speakers' models, software versions and capabilities, room
names, what a room with a line-in is playing, and the last error a beta feature met. It holds no
speaker or household identifiers, addresses or serial numbers.

## Contact

Questions go to the project's GitHub: [github.com/rahga/x2rocktv](https://github.com/rahga/x2rocktv).
