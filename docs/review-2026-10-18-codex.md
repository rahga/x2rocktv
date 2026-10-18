# Release-readiness review, 2026-10-18 — awaiting review

Reviewed `4aa7f6a` on `master`, including the `50e59bd..c5435a8` hand-off. These are
proposals for the owner, Claude and Gemini to check before implementing or committing.
No production code was changed and no commit or publication was made during this review.
Record agreement, a narrower scope, or counterevidence against each ID first.

The current local queue is `.local-work/PUNCH.md` (defects) and `.local-work/TODO.md`
(engineering/release work). This tracked document preserves the findings for other machines.
The older catch-up list is historical; its closed items are not reopened by this review.

## Scope and release boundary

The pass covered both modules: LAN discovery and socket/session lifecycle; UPnP and SMAPI;
account capture, refresh and optional authentication; playback, queues, favourites, alarms,
presets and settings; view models, routes, Compose focus and screen layouts; Android network,
artwork, media-session and launcher adapters; preferences; build, shrinking, signing, CI,
assets, tooling, tests and release documentation. Existing tests and prior decisions were
checked alongside the implementation. This is a broad review, not a claim that every device,
service response or concurrency interleaving has been exercised.

Owner clarifications during review:

- The maintainer's distributed APK **includes the local speaker salt**. Its absence from
  source builds is deliberate. Inclusion in that APK is not a defect or a private-build flag.
  The public code uses it for LAN account reading and public SMAPI communication.
- Do not propose undocumented Sonos cloud catalogue access as a public browse/search fix.
  Speaker authentication is a separate concern. Private experimental code must remain out
  of public artifacts and tracked protocol notes.
- The owner is finishing a CS degree over the next eight months. Prefer bounded fixes,
  a usable sideload release and focused tester feedback over feature expansion or rewrites.

Priority here: **P1** should be addressed before wider distribution of the affected path;
**P2** is the next reliability batch; **P3** is polish or a bounded validation task. No P0
was established. Suggested reviewers are starting points, not claims or assignments.

## Findings in proposed order

| ID | Priority | Finding | Evidence | Suggested first review |
|---|---|---|---|---|
| P-47 | P1 | Private integration bundle guard can be bypassed by a task abbreviation | Gradle dry run, dummy credentials | Claude + Codex |
| P-39 | P1 | Advertised API-23 minimum is below APIs used in app and core | Release lint + Android API references | Gemini + owner decides floor |
| P-40 | P1 | Preset/volume-evening failures do not prevent starting or joining | Preset probe; party code trace | Codex + owner decides failure policy |
| P-41 | P1 | An open queue menu can edit a different track after an external edit | UI-to-view-model code trace | Claude + Codex |
| P-42 | P1 | Search's nested routes retain a group ID after it vanishes | Isolated target probe + route code | Claude + Gemini |
| P-48 | P1 | Report's free-text fields can contain addresses despite the promise | Pure report probe | Gemini + Codex |
| P-43 | P2 | Shared player controls stop following a room off the home screen | Isolated player probe | Claude + Codex |
| P-44 | P2 | Restored room targets never learn their speaker identity | Isolated cold-construction probe | Gemini + Claude |
| P-45 | P2 | Failed manifest fetch can become a permanent negative capability cache | HTTP-503/cache probe | Codex + Gemini |
| P-46 | P2 | Account-capture cancellation waits for blocking socket timeout | Loopback cancellation probe | Codex + Claude |
| P-49 | P2 | Failed service pagination leaves “Loading more” with no recovery action | Code trace; fault-injection check owed | Gemini + Claude |

### P-47 — enforce the private-artifact boundary on actual tasks

`app/build.gradle.kts:51` checks the **typed command-line task names** for `bundle`.
Gradle accepts abbreviations. With dummy key/secret files,
`:app:bR --dry-run -Px2rock.sonosIntegration ...` succeeded and scheduled
`:app:bundleRelease`; it did not encounter the guard. No real credentials were used in
this check and no private bundle was built. `x2rock.cloudContent` also has no corresponding
bundle prohibition of its own.

Review a guard on the resolved public distribution tasks/artifacts, independent of how
they were invoked. Keep the owner's release-signed private APK workflow. Do not classify
the speaker salt as a private flag. Accept when full task names, abbreviations and a task
depending on bundle generation all refuse private integration/content inputs; an ordinary
public APK/AAB still builds. Keep final APK inspection in the release process too.

### P-39 — make the minimum Android version truthful

`minSdk = 23`, without core-library desugaring. Release lint reports the
`HashMap.putIfAbsent` call at `NsdMdnsDiscovery.kt:57` as requiring API 24. The JVM module
has additional uses not identified by this source-level lint run:

- `SonosHousehold.kt:425`: `ConcurrentHashMap.newKeySet()` during household construction,
  [added in API 24](https://developer.android.com/reference/java/util/concurrent/ConcurrentHashMap#newKeySet()).
- `StoredAccounts.kt:70`: `java.util.Base64.getDecoder()`,
  [added in API 26](https://developer.android.com/reference/java/util/Base64#getDecoder()).
- Audit `Discovery.findPlayers`'s `putIfAbsent` and the session-loss `removeIf` too.

The JVM tests and API-30 Shield cannot establish compatibility with API 23. Choose either
an intentional higher minimum, or compatible implementations/desugaring while keeping
`:core` Android-free. Merely fixing the one lint line is insufficient. Accept with a release
launch, discovery and account-read check on the chosen minimum, and no unsupported calls
in its packaged core code.

### P-40 — volume preparation must have a defined failure outcome

`FavoritesViewModel.applyPreset` awaits a list of `Result`s from `setPlayerVolume`, then
discards them and calls `gatherRooms` and `loadFavorite`. The isolated probe refused
`setVolume`: `loadFavorite` still arrived, the completion callback ran, and no notice was
posted. `HomeViewModel.partyMode(evenVolume = true)` discards volume-write failures in the
same way before joining rooms. This defeats the documented reason for setting levels first:
a joining room can play immediately at its old level.

Agree on a small policy: failed preparation of an adjustable speaker should stop its
automatic join/start and name the failed room; known fixed outputs need an explicit
exception, not a catch-all. Preserve parallel volume writes and report partial success
truthfully. Test a refusal and a timeout, asserting no subsequent unsafe join/load. Include
a saved preset missing an adjustable speaker's level in the review of that policy.

### P-41 — bind a queue-menu edit to the entry that opened it

`QueueScreen.kt:126` keeps a `QueueEntry` as the menu target. If another controller removes
an earlier row while the menu stays open, the list/version refreshes but the menu target
does not. Its Remove/Move lambdas (`:213`) send only the old `trackNumber`.
`QueueViewModel.edit` (`:216`) captures the track from the **current** entries when the
menu action is pressed and uses the **current** UpdateID. Its existing queued-edit check
therefore compares the replacement track with itself. The speaker can accept an edit of
the wrong row; the version guard does not help once the UI has refreshed.

Pass the menu's original expected entry/version through to the operation, or dismiss the
menu when its target ceases to match. A title alone is not an identity. Accept with a focus
test opening the menu on B, externally removing A, waiting for the read, then pressing
Remove/Move: C must never be edited. Existing tests of two already-queued VM edits cover
a different timing and should remain.

### P-42 — pass the current room when navigating deeper from Search

`NavGraph.kt:169` captures the route's initial `groupId`. Both opening a found container
and “More from” reuse it. `ServiceBrowse`'s Apple route similarly reuses its original
argument. A `RoomTarget` already alive across a regroup follows its speaker, but a **new**
one created from that vanished group ID cannot recover: its captured `opened` group is
null. The probe demonstrates that distinction against `FakePlayer.groupedTopology`.

Expose the current target for navigation as Favorites already does, or carry a stable
speaker identity in routes. Test Search → external regroup → album/More, and Music Services
→ external regroup → Apple. Playback from the newly opened page must still address the
original room's current group.

### P-48 — redact free text before constructing a public report

The report deliberately omits structured IDs and serials, but appends `refusal` and
`StoredAccountsRead.problem` unchanged (`BetaReportViewModel.kt:129`, `:149`).
`BetaFeatures.noteRefusal` stores `Throwable.message`; a real socket failure message names
`sonos-<MAC>.local`, and another failure may name a group/player ID or address.
The report probe verifies that a hostname and private IP in a refusal survive in both
displayed text and the QR's issue draft. The current tests use an error-code-only refusal.

Apply one redaction/allowlist rule to free-text diagnostic fields before display and URL
encoding; keep normal room names as the policy already states. Test socket/refusal and
account-read errors with identifiers embedded in text, without recording real credentials.
Accept only when the displayed report and decoded issue body satisfy their on-screen
promise. Do not silently expand the privacy promise instead.

### P-43 — room tracking for shared controls must outlive HomeScreen

`HomeScreen.kt:194` is what calls `PlayerViewModel.selectGroup` after selection/topology
changes. It is not composed while Queue, Browse or Full screen is current. Those screens'
own targets may follow the speaker, but media keys (`MainActivity`), media-session callbacks
and the now-playing bar still use the shared player's old `_groupId`. The isolated probe
selected a room, replaced its group, then adjusted volume without another `selectGroup`:
the notice was `no group <old id>`. Full screen's independent view model only fixes the
display, not the shared commands.

Move target following to a lifecycle that remains active while these screens are open.
Test an external regroup with a secondary screen active, then explicit play/pause/skip
and its now-playing bar; both display and command must refer to the same room. Keep the
rule that hardware volume keys belong to the TV.

### P-44 — restored targets need durable room identity and connection-aware loading

`RoomTarget.kt:17` captures `opened` only once. A Navigation destination restored before
the new process connects has no topology then, so it never learns a coordinator. Even if
the original group ID reappears briefly, a later regroup loses the target permanently.
The cold-construction probe connects, records a route, disconnects, constructs the target,
reconnects and regroups; the room is then reported absent. Search/Browse/Queue also have
initial reads that can fail before reconnection and offer varying retry paths.

Persist the stable room target in route/saved state, or deliberately return restored
destinations to a usable home screen. Do not guess a different room. Accept with a real
background process-death/restore check and a fake test constructing targets before topology
arrives. Preserve the separate, previously accepted deep-link replay policy.

### P-45 — distinguish unavailable capability metadata from an empty capability

`SmapiClient.fetch` returns null for a non-success HTTP status. `presentationMap` turns
that into no map; `ratings`/`categories` turn it into an empty list. `RatingsCatalogue`
then persists it through `PrefsRatingsStore`, with no expiry; category results are cached
for the session by `SonosHousehold.serviceCategories`. A temporary CDN 503 can therefore
remove ratings across restarts, or remove a service's search until reconnection.

The probe returns HTTP 503 for the manifest and verifies that a second catalogue/client
sharing the store makes no request and still reports no ratings. Preserve caching of a
successful “this service publishes none”, but never persist a transport/HTTP/parse failure
as that answer. Define invalidation for existing poisoned entries. Test 503 → recovery
and genuine empty metadata separately.

### P-46 — cancel account capture at cancellation, not job completion

`AccountCapture.kt:51` uses `coroutineContext.job.invokeOnCompletion` to close the server.
That handler runs after the job finishes; the job cannot finish while its `accept` is
blocked. In the loopback probe, cancelling a capture with a 3-second accept deadline took
about **2.9 seconds**. Production uses 10 seconds, plus subscribe/unsubscribe work. A
replacement search waits behind the service lock while this cancelled capture unwinds.

Install cancellation that actually closes pending accept/read/connect work, with cleanup
registered before any fallible setup and disposed on every exit. Also bound the total
message size in the raw HTTP reader, which currently keeps growing until its declared
Content-Length or EOF; its read timeout alone is not a size bound. Accept with prompt
cancel-and-join probes while waiting for subscribe, callback and body, and a subsequent
capture that succeeds. The existing never-calls-back test proves timeout, not cancellation.

### P-49 — make a failed next page recoverable

`ServiceBrowseViewModel.loadMore` uses `attempt { ... }.getOrNull() ?: return`, retaining
`hasMore`. `ServiceBrowseScreen.kt:394` launches it using item-count keys, and its footer
is nonfocusable text saying “Loading more”. When a page fails, neither the items nor those
keys change: there is no failure notice or explicit retry. Returning to rows can happen
to retrigger it, but is not a visible recovery path. Initial service/read failures likewise
mostly offer text and require backing out and reopening.

Keep successful rows visible; expose the failed-page state and a focusable retry for that
page. Accept with page 1 successful, page 2 refused/timed out, then recovered: list position
and target room retained, no duplicate appends, no automatic retry loop. This is a code
finding; the fault-injection/device reproduction is still owed before implementation.

## Engineering and release tasks

### T-34 — P1: put the release checks in CI

`.github/workflows/build.yml` runs unit tests and `assembleDebug` only. Add the ordinary
R8 release build and release lint, resolving P-39 and the other lint diagnostics first.
Three of today's four errors concern `MainActivity.dispatchKeyEvent`'s `RestrictedApi`;
verify the AndroidX annotation/API use before changing working media-key handling or
applying a narrowly explained suppression. Do not baseline all 52 diagnostics or force
unrelated dependency upgrades. Preserve failed lint/build reports as artifacts.

Done: CI tests and shrinks the public configuration without integration keys or private
content; lint is green for explained reasons; P-47 has a check that fails on a guard
regression. The maintainer's local salt remains a supported input for distributed APKs.

### T-35 — P2: validate the exact public artifact, then recruit

The Shield observed here has `0.1.2-7-gd3374e5-dirty+hardcore`, code 5, API 30. It cannot
prove public service availability. Keep two clearly named validation records: the public
APK (with the maintainer's salt, no integration secret/private content) and the owner's
private build. Verify signatures, versions, launcher entry, cold/warm launch, services,
search, queue, error/report paths and update-in-place on the exact release artifact.
Do not uninstall the owner's TVs to make that check; use a spare emulator/device or a
same-key update after preserving the private build needed to restore it.

The hand-off owed the emulator focus suite after the recent pane/panel/button changes;
**all 11 passed during this review** on API 34. Continue running it on the emulator, never
the household TVs: the test task installs/removes its own APKs. Record remaining hardware
coverage from P-03/P-07/P-08/P-13/P-14/P-16/P-29
without reopening checks already done. Line-In and portable battery remain beta. Public
service refusals should get focused tester feedback, not an undocumented catalogue fallback.

### T-36 — P3: finish the small documentation consistency pass

Update build/release/help text that still describes the old code: `Smapi.kt`'s opening
comment says no browse/search and every token is null; `PlayerSettings` says only its
home-theatre block is read; `canSaveFavorite` still says beta-only; `Upnp.MAX_ITEMS` says
listing stops at 1000 although the household pages. `lan-transport.md` still has historical
“no mDNS fallback” and “not run on a TV” statements alongside newer evidence; mark their
dates/status rather than rewriting the original captures. `ARCHITECTURE.md` should be checked
against the recent room-panel split and public/private build inputs. Preserve the rationale
in `CLAUDE.md`; do not turn this into rewriting the history or protocol documents.

The small factual doc corrections in this review mark the old review closed, link this
open review, explain the source-build salt distinction, update the house's signing status,
and cite Google's test requirement. The separate local queue's obsolete “decided out”
entries for favourites and room ordering are marked superseded.

### T-12 follow-up — owner: a bounded tester plan and Play listing

Keep the existing release task; do not make a duplicate publishing project. A GitHub install
is useful feedback but does not count as Play closed-test opt-in. Google's current rule for
new personal accounts is **12 testers continuously opted in for at least 14 days**, followed
by an application for production access, with questions about testing and engagement.
[Google's testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465).
An organization account has its own identity/organization verification; a D-U-N-S number
is one input, not production approval by itself.
[Google's identity requirements](https://support.google.com/googleplay/android-developer/answer/10841920).

Before Reddit: one install link, supported/untested hardware and service caveats, a private
way to volunteer (never emails in public issues), a short test script and a place to record
feedback. Later, verify the production-access requirements in the actual Console, provide
the graphics/Data safety/usable hosted privacy page, and preserve the sideload signing key
when choosing Play App Signing. Owner handles accounts, publishing and any public outreach;
this review sends or posts nothing.

## Checks and limits

- Final normal JVM run: **core 415 tests, 30 opt-in live tests skipped; app 254 tests;
  639 passed, zero failures/errors**. The isolated probes were not part of this normal run.
  Normal APK and AAB release builds also succeeded; the AAB here uses the default local
  signing configuration and was not uploaded.
- `:app:lintRelease` failed: **4 errors, 48 warnings**. One NewApi error and three RestrictedApi
  diagnostics are described above; most warnings are version notices or known tradeoffs.
- Seven isolated detection probes (five app, two core) confirmed the behaviours above.
  They assert the **existing defect**, so green means reproduced, not fixed. They are not
  proposed regression tests to merge unchanged. Sources, XML results, dry-run output and
  lint output are in `.local-work/reviews/2026-10-18-codex/`, deliberately untracked.
- The working tree's tracked files were scanned for the local salt without printing it:
  **zero matches**. History was not rewritten or audited by that check.
- Shield: checked foreground window before keys, launched the existing release, selected
  Dining Room, opened Browse and Queue, returned to the player. Art, focused rows and Back
  worked on those paths; no crash/ANR match appeared in the inspected log window. No app
  install on the Shield, speaker-state write, queue edit, playback, grouping or volume change was made.
  Dining was already paused at 0:24, volume 2; other rooms and the Streamer were left alone.
- **Emulator focus suite: 11 passed, none skipped or failed**, API 34. First attempt failed
  at installation because the existing emulator APK had a different signing key; the test
  runner removed its emulator package during cleanup, and the retry passed. No manual
  uninstall or household-TV install was performed. The headless emulator was stopped afterward.
- Full logs and normal-suite summaries are recorded in the local evidence README.
  No new live protocol captures, minimum-SDK device run,
  authentication-switch change or Play Console operation was performed.

## Deliberately left alone

No new feature tier, repository-wide refactor, polling, volume-key interception, relaxed
hostname verification, Gson keep-rule narrowing, foreground media service or blanket
dependency update is proposed. Existing decisions about inactive-queue event limits,
duplicate-title markers and the absent CLI remain. The coordinator-event filter's
unknown-group/handoff edge still wants hardware evidence; the previous member-event fix
is supported by its regression test and is not reversed speculatively. The generic
unpressed-release guard merits device regression coverage under T-35, not a redesign
based only on symmetry. Sonos licensing decisions already taken by the owner are not
reopened by this review.
