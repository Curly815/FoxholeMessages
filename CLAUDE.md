# Foxhole Messages — project notes

Foxhole Messages is a rebrand of QUIK/QKSMS (GPLv3). `applicationId` is
`com.foxhole.messages`. The internal Kotlin/Java package intentionally
stays `dev.octoshrimpy.quik` (directories under `com/moez/QKSMS/`) —
changing the AGP `namespace` would require rewriting imports across
~400 source files, so only `applicationId`/branding was changed, not
the source package.

## v1.0.0 (released)

Finalizing v1.0 (`versionCode 2239`, `versionName '1.0.0'`, bumped in
`presentation/build.gradle`). Planned changes, in order:
1. Version bump + changelog (`data/src/main/assets/changelog.json`,
   F-Droid metadata) — done.
2. Venmo donation option on the Plus/About screen — done. The old
   PayPal button and the entire non-functional "upgrade"/paywall UI
   were removed (`BillingManagerImpl` was already a no-op stub that
   always reports everyone as upgraded, so there was no real paywall
   to begin with — see `feature/plus/`). `PlusActivity` now just shows
   a "Support the developer" Venmo button plus the always-unlocked
   feature list; the About screen also links to
   `ExternalNavigator.showVenmoDonation()` under a "Support the
   developer" row. Other already-inert `!upgraded` dead code (drawer
   badges, Backup/Scheduled FAB fallbacks, Settings gates) was left
   untouched since it was already invisible to users.
3. Link thumbnail previews in message bubbles — done, verified on
   device. Uses `me.saket.unfurl:unfurl:1.7.0` (Maven Central, Apache
   2.0) — pinned to 1.7.0 specifically because newer releases (2.x)
   are built with Kotlin 2.2, whose class metadata this project's
   Kotlin 1.7.21 compiler can't read (it broke compilation
   project-wide, not just the new file, when first tried at 2.3.0).
   1.7.0's `Unfurler.unfurl()` is a **blocking** call (no coroutines
   dependency), with its own built-in in-memory LRU cache (size 100)
   keyed by URL — no separate Realm/Room cache layer was needed.
   `LinkPreviewRepository` (`common/util/LinkPreviewRepository.kt`)
   wraps it as `Maybe<LinkPreview>` (empty = no preview, never throws)
   via `Maybe.defer { ... }.subscribeOn(Schedulers.io())` (plain
   RxJava2, not coroutines — `Maybe.fromCallable` doesn't work here
   since Kotlin infers a nullable type argument from a nullable
   lambda body, which doesn't unify with `Maybe`'s invariant generics).
   `MessagesAdapter` extracts the first URL per message with
   `Linkify`, fetches/binds/cancels per-ViewHolder (tag-based
   staleness guard + `onViewRecycled` disposal), and renders a card
   (thumbnail/title/description/host) below the message body in both
   `message_list_item_in.xml`/`_out.xml`. Previews are only fetched
   when the existing Settings "link handling" preference is not set
   to Block — fetching a preview means silently contacting whatever
   server is in the URL, which the Block setting exists to prevent,
   so it was tied to that instead of adding a new toggle.

All four v1.0 items above are now done and device-verified.

Verification workflow: this sandbox has no Android SDK and no device
attached, and Google/JitPack Maven access is blocked by network
policy, so builds can't happen here. Each step gets pushed and built
via the `.github/workflows/build-on-pull.yml` workflow
(`workflow_dispatch`), which produces a debug APK (`.debug` suffix,
installs alongside the release build) as a downloadable Actions
artifact for Erik to sideload and confirm before moving to the next
step.

Once all of the above is verified, cut the v1.0 GitHub Release the
same way as `v4.3.6` (see below).

## v1.1.0 — Message Sorting feature recovery

v1.0.0 shipped without a "Message Sorting" feature (auto-categorizing
messages as Personal/Transactional/Promotional, a tabbed inbox, etc.)
that had actually already been built once, in a *prior* ephemeral
Claude Code session — but that session's container was reclaimed
before its commits were ever pushed to git, so the work was
completely lost from the repo (confirmed via exhaustive git history/
branch search — zero trace anywhere). The only surviving artifact was
a `FoxholeMessages-v4.4.0.apk` the user had sideloaded from that lost
session. It was recovered by decompiling that APK (`androguard`,
isolated venv due to a system `cryptography` conflict —
`python3 -m venv /tmp/androguard_venv && pip install androguard`;
`AnalyzeAPK` + `DecompilerDAD` for pseudo-Java; R8 had obfuscated
resource *file* names but not resource/class *identifiers*, so string
tables, `strings -e s classes.dex`, and `ARSCParser.get_res_id_by_key`
were enough to recover exact regex patterns, Realm schemas, and
layout/resource contents) and faithfully reimplementing it as new
Kotlin source — not just approximating behavior, but matching the
decompiled logic line-for-line where feasible, then translating
Java-interop-shaped decompiled bytecode back into idiomatic Kotlin
(e.g. `RxView.clicks(x).map(VoidToUnit.INSTANCE)` → `x.clicks()`) to
match this codebase's actual conventions. This shipped in two passes:

**Pass 1 — classification engine + settings UI:**
- `classifier/` (domain module): `Category` enum, `OtpDetector`,
  `MessageClassifier` (18 transactional + 15 promotional regexes,
  recovered verbatim from decompiled bytecode), `MessageCategorizer`
  (trusted-sender/rule/classifier precedence), `MessageCategoryBackfill`
  (chunked batch classification for existing messages).
- New Realm models `TrustedSender`, `SenderCategoryRule` + repos.
- `Message` gained `category: String?` and `isOtp: Boolean`.
- `Preferences` gained `autoSortEnabled` and per-category
  notification prefs (`categoryNotifications`/`categoryPreviews`/
  `categoryVibration`/`categoryRingtone`) — **stored but not yet wired
  into actual notification delivery** in `NotificationManagerImpl`;
  flagged as a known follow-up if ever wanted, deliberately skipped to
  avoid a risky untested change to that method.
- `ReceiveSmsWorker`/`ReceiveMmsWorker` classify+OTP-tag every message
  as it arrives; `ClassifyExistingMessagesWorker` backfills on demand.
- Settings UI under `feature/settings/messagesorting/`: main screen,
  sender rules list, trusted senders list, per-category notification
  activity — all wired into `SettingsController`/`AppComponent`.
- Realm `SCHEMA_VERSION` 15 → 16.

**Pass 2 — tabbed inbox, starring, category override:** the first
pass only covered the *settings* screens; a second decompile pass
(triggered by the user noticing "the tabs are missing" on-device)
found the actual auto-sort *result* — a tabbed Inbox — hadn't been
rebuilt yet. Added:
- `feature/conversations/Tab.kt` enum (PERSONAL/TRANSACTIONS/
  PROMOTIONS/STARRED) + `ConversationsPagerAdapter` (ViewPager2-backed,
  one `ConversationsAdapter`/`ConversationItemTouchCallback` instance
  per tab so selection/swipe state stay independent). Inbox page shows
  this instead of the flat conversation list; Archived/Search are
  unchanged. `MainActivity`'s `conversationsSelectedIntent`/
  `swipeConversationIntent`/`clearSelection`/`toggleSelectAll` all
  merge across the main adapter + all 4 tab adapters now.
- `ConversationRepository.getConversationsByCategory`/
  `getUnreadCountByCategory`/`getStarredConversations`/
  `getUnreadStarredCount` — category tabs fall back to PERSONAL for
  anything not TRANSACTIONAL/PROMOTIONAL (i.e. unclassified mail
  defaults into Personal, there's no separate "Unclassified" tab).
- `Message.isStarred: Boolean` (new "Star" action in the compose
  toolbar menu, one message selected at a time) and
  `Conversation.categoryOverride: String?` (new "Move to..." action on
  selected conversations in the main toolbar menu — sets the override
  *and* persists a `SenderCategoryRule` for each recipient, so future
  messages from them sort the same way).
- Realm `SCHEMA_VERSION` 16 → 17.
- Tab strip UI went through several iterations before landing on
  `com.google.android.material.tabs.TabLayout` + `TabLayoutMediator`
  (scrollable, auto-width tabs with an underline indicator) to match
  a reference screenshot of the original 4.4.0 build the user
  provided — a hand-rolled equal-width `LinearLayout` strip was tried
  first and didn't match. This required bumping
  `ext.material_version` in the root `build.gradle` from `1.0.0` to
  `1.6.1` (**`TabLayoutMediator` and `tabGravity="start"` don't exist
  before Material 1.1.0** — this project had never needed anything
  past basic `Snackbar` before, so it was still pinned to 1.0.0).
- New app launcher icon recovered from the same APK: adaptive icon,
  solid `#2F4A3D` (dark green) background, `#F5F1E6` (cream) chat
  bubble with three dots foreground (`presentation/src/main/res/
  drawable/ic_launcher_{background,foreground,monochrome}.xml`).
  Notification icons (`ic_notification`/`_worker`/`_failed`) came back
  **pixel-identical** to what was already in the repo (verified via
  `PIL.ImageChops.difference`), so those were left untouched despite
  initially expecting them to also need updating.

Shipped as `versionCode 2240` / `versionName '1.1.0'` (semver minor
bump — new backward-compatible feature, not a fix). Changelog and
F-Droid metadata updated alongside the version bump per the process
below.

## v1.2.0 — Settings cleanup + OTP message retention

Requested changes to `SettingsController`/`AboutController`, device-verified
via sideload before cutting the release:
- Removed "Disable Screenshots" (toggle, pref, and the `FLAG_SECURE`
  logic it drove in `QkActivity`).
- Removed "Strip accents" (toggle, pref, and the `StripAccents` call in
  the MMS send pipeline — `QkTransaction.kt`/`MessageRepositoryImpl.kt`).
- Removed "Mobile numbers only" (toggle, pref, and the contact-filtering
  logic in `ContactRepositoryImpl`).
- Removed the "Developers" row from the About screen
  (`ExternalNavigator.showDeveloper()` and its strings).
- Bumped the default "Auto-compress MMS image attachments" threshold
  from 300KB to 1000KB (`Preferences.mmsSize` default; 1000KB was
  already a selectable option in `R.array.mms_sizes`, so no new array
  entry was needed).
- Added an "OTP message retention" picker (Never/1/7/30 days) to the
  Message Sorting settings screen, following the same
  toggle-then-schedule-a-job pattern as the existing "Delete old
  messages automatically" setting: a new `DeleteOldOtps` interactor
  (queries `Message` where `isOtp == true` and older than N days) plus
  a new `OtpRetentionService` daily `JobService`
  (`MessageRepository.getOldOtpCounts`/`deleteOldOtps` added
  alongside the existing `getOldMessageCounts`/`deleteOldMessages`).
- The message-deletion confirmation dialog the request also asked for
  turned out to already exist end-to-end (single and bulk, across
  Compose/Main/Scheduled/BlockedMessages/ConversationInfo, all with
  Cancel/Delete `AlertDialog`s gating the actual delete) — verified by
  reading the existing flow rather than re-implementing it.

Shipped as `versionCode 2241` / `versionName '1.2.0'` (semver minor
bump — a new feature (OTP retention) alongside settings removals, not
a pure fix).

## Cutting a release

1. Merge changes into `master`.
2. Bump `versionCode`/`versionName` in `presentation/build.gradle` if
   this should be a new version/tag. **`versionName` must be strict
   3-part semver (`X.Y.Z`, e.g. `1.0.0`)** — `generate-release-notes.yml`
   runs it through `semver.parse()`/`semver.lt()` to find the previous
   release for the changelog diff, and a 2-part version like `1.0`
   fails to parse (silently returns `null`), which throws
   `TypeError: Invalid version. Must be a string. Got type "object"`
   deep in the `generate_release_notes` job — hit this exactly once,
   with `versionName '1.0'`, fixed by using `1.0.0` instead.
3. Trigger the **Build and Release** workflow
   (`.github/workflows/build-and-release.yml`) via `workflow_dispatch`
   on `master` — it builds, signs, and publishes the GitHub Release
   automatically.

Required repo secrets (already configured as of this writing):
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` (`foxhole_messages_release`), `ANDROID_KEY_PASSWORD`.

**The release keystore is the app's permanent signing identity** — every
future release must be signed with the same key or existing users can't
update. Erik has a copy of `my-release-key.keystore`; if it's ever lost,
a new one will break update compatibility for anyone who installed a
prior release.

## Self-hosted F-Droid repo — dropped

`fdroid/` used to also drive a self-hosted F-Droid repo published to
GitHub Pages (a custom repo source someone would add manually in the
F-Droid app — separate from, and never required for, the GitHub
Releases, which have always worked fine).

Investigated why it never populated anything (user asked "why won't
the app show up on F-Droid") and found five stacked bugs verified by
installing fdroidserver 2.2.1 locally (matching what CI installs): a
publish-job/build-job path mismatch, a missing source checkout, dead
restore-from-gh-pages code in the wrong job, an invalid `fdroid update
--config` flag, and an unrecognized `PackageName` field in the app
metadata. Fixed all five, but hit a real wall: `fdroid update`
hard-requires a repo signing keystore to produce an index at all — no
flag/config combination produces a fully unsigned repo, contrary to
what the tool's own example docs suggest. That needs a new keystore
(separate from `ANDROID_KEYSTORE_BASE64`, which only signs the APK)
and new GitHub secrets to hold it.

Decision: dropped rather than finishing the keystore setup — priority
moved to a Google Play Store submission instead. Reverted the
`build-and-release.yml` publish job back to just the GitHub Release
(no F-Droid steps) and removed `fdroid/config.yml`. Left
`fdroid/metadata/com.foxhole.messages.yml` in place (including the
`PackageName` fix) since it's independently used by the *other*,
unrelated `fdroid-mr` job in `release-after-merge.yml` — a dormant,
unconfigured job (needs `FDROID_GITLAB_FORK`/`FDROID_GITLAB_TOKEN`,
neither set) that would submit a metadata MR to the official
`fdroid/fdroiddata` repo, the actual path to being listed in the real
F-Droid app. Not touched — out of scope here, noted for if it's ever
picked up.

## Preparing for Google Play

Erik has initiated a Play Console account and is waiting on Google's
verification. Did what's possible from the repo side ahead of that;
everything past this point needs Play Console access (his Google
account), which this sandbox can't touch.

**Done:**
- Bumped `targetSdkVersion` 33 → 34 in all modules (was already on
  `compileSdk 34`; Play requires targeting a recent API level). Verified
  this is safe rather than assumed: Android 14 (API 34) newly enforces
  a declared `foregroundServiceType` for any `startForeground()` call —
  `RestoreBackupService` was missing one and would have crashed at
  runtime on the backup/restore flow. Fixed (`dataSync` type +
  `FOREGROUND_SERVICE_DATA_SYNC` permission).
- Also fixed `BluetoothMicManager`'s `registerReceiver()` call, which
  listens for a system broadcast (`ACTION_SCO_AUDIO_STATE_UPDATED`)
  without specifying exported/not-exported — required since Android 13
  (API 33) for apps targeting 33+, so this was already a live crash on
  Android 13/14 devices before today's targetSdk bump, not something
  the bump introduced. Fixed via `ContextCompat.registerReceiver(...,
  RECEIVER_EXPORTED)`.
- **Fixed (was flagged as a follow-up, now resolved)**: the vendored
  `android-smsmms/` library had four more `registerReceiver()` calls
  with the same missing-exported-flag issue (`RateController`,
  `DownloadManager`, `TransactionService`, `MmsConfigManager` — the
  fifth, in `Transaction.java`, only runs on API ≤ 19 and was left
  alone since it can't hit this on any real device). Root-caused this
  as the actual explanation for Erik's "pictures aren't being
  received" report: `DownloadManager.downloadMultimediaMessage()` —
  the method that downloads MMS attachment content after a WAP push
  notification arrives — calls an unguarded `registerReceiver()` for
  its download-result receiver (`DownloadManager.java:56`). On Android
  13+ this throws `SecurityException`, which gets silently swallowed
  by an outer `catch (RuntimeException e)` in `PushReceiver.java` (no
  crash, no log the user would see) — so the message/notification
  still arrives, but the actual image content never downloads. This
  was pre-existing at the already-shipped targetSdk 33 (not introduced
  by this session's 34/35 bumps), same as the `BluetoothMicManager`
  issue above, just undiscovered until it actually broke picture
  receiving in practice. Fixed all four the same way as
  `BluetoothMicManager`: SDK-gated `Build.VERSION.SDK_INT >=
  TIRAMISU` branch adding `Context.RECEIVER_NOT_EXPORTED` (all four
  are app-internal/self-only broadcasts — custom actions or a
  system broadcast only this app needs, no cross-app delivery
  required, so `NOT_EXPORTED` is correct rather than `EXPORTED`).

  Device-verified this fix as necessary but not sufficient — pictures
  still didn't arrive after shipping it (v1.2.4). Diagnosed the
  remainder using the app's own built-in file logger (`FileLoggingTree`
  + `Preferences.logging`, toggled via long-pressing "About" in
  Settings — writes a plain-text log to the Downloads folder), since
  ADB wasn't available (Erik was traveling, no laptop) and an Android
  bug report's bundled logcat turned out to be uncaptured/empty on his
  device (`dumpstate_log.txt` showed `logcat: Logcat read failure`).
  The captured log confirmed the v1.2.4 fix itself worked
  (`DownloadManager: receiving with system method` now logs cleanly,
  no more crash) but showed `SmsManager.downloadMultimediaMessage()`'s
  completion callback never firing afterward — no success, no error,
  ever. Root cause: that download's `PendingIntent` was created with
  `FLAG_IMMUTABLE` (`DownloadManager.java`). Android's MMS download
  API needs to fill result data (HTTP status) into that intent when
  the download finishes; an immutable `PendingIntent` silently blocks
  that on-device, so the callback never dispatches at all. The
  send-side `PendingIntent` (`QkTransaction.kt`) uses the same
  `FLAG_IMMUTABLE` pattern but only needs a plain result code (no
  filled-in extras), which is why sending appeared to work — though
  "Sent"/"Delivered" was never a reliable signal of true delivery to
  begin with (`Message.isDelivered()` hardcodes MMS delivery to
  `false` with a `// TODO`; those two labels only reflect the local
  handoff to the carrier succeeding). Fixed by switching the download
  `PendingIntent` to `FLAG_MUTABLE`. Left the send-side one untouched
  since it isn't broken. Still needs device verification — this is
  the fix for the actual "pictures aren't being received" root cause,
  v1.2.4's fix was a real but incomplete piece of it.
- Removed the unused Firebase Crashlytics classpath (`build.gradle`) —
  never applied, no `google-services.json`, dead since the QUIK fork.
  Left as-is it would've been confusing noise when filling out Play's
  Data Safety form (looks like analytics collection; isn't).
- Added `bundleRelease` to `build-and-release.yml`'s build job — Play
  requires an Android App Bundle (`.aab`) for new app submissions, not
  a bare APK. The `.aab` is uploaded to the `build-artifacts` CI
  artifact (downloadable from the Actions run) alongside the APKs, but
  deliberately *not* attached to the public GitHub Release — an `.aab`
  isn't directly installable, and the public release page is for
  regular users sideloading `-release.apk`/`-fdroid.apk`.
- Fixed `metadata/en-US/short_description.txt` (was 91 chars, Play's
  limit is 80) and rewrote `full_description.txt` (was Markdown, which
  Play renders as literal text, not formatting; also stale — predated
  Message Sorting, link previews, OTP retention). Added
  `metadata/en-US/changelogs/2241.txt` matching the current release,
  fastlane-format, ready to paste into Play Console's release notes
  (500-char limit) if not automated. This is the same directory
  structure Google Play's `gradle-play-publisher`/fastlane tooling
  reads, so it's reusable once/if that gets wired up.

**Still needed, requires Erik's Google account (once verification
completes):**
- Store listing assets Play requires that can't be produced here: a
  1024×500 feature graphic, phone screenshots (min 2) — needs a real
  device or emulator, neither available in this sandbox. The existing
  `metadata/en-US/images/icon.png` (512×512) already meets Play's app
  icon spec.
- A **content rating questionnaire** and a **Data Safety form** are
  mandatory in Play Console. Based on an actual code read (not
  guessing): the app has no analytics/ads/crash-reporting SDK, no
  developer-run backend or account system, and no data leaves the
  device except (a) SMS/MMS send/receive over the carrier network
  (the app's core function) and (b) link-preview fetches, which
  contact whatever third-party host a URL in a message points to
  (same behavior already disclosed in the F-Droid metadata's
  `NonFreeNet` antifeature). Backups use Storage Access Framework to a
  user-chosen location — could be a cloud-backed provider, but only if
  the user themselves picks one via the system file picker; the app
  has no direct cloud integration. The existing root `PRIVACY` file's
  "I do not collect data" claim holds up against this reading.
- **SMS/Call Log permissions declaration**: the app requests
  `READ_SMS`/`RECEIVE_SMS`/`SEND_SMS`/`RECEIVE_MMS` (plus
  `READ_CONTACTS`), which puts it under Play's restricted "SMS or Call
  Log" permissions policy. This requires a separate in-console
  declaration justifying the core use case (replacing the default SMS
  app) — most rejections for messaging apps happen here. Distribution
  is also limited to users who can set the app as their default SMS
  handler. Budget real time for this before expecting a quick approval.
- **App signing**: Play App Signing is the standard modern setup —
  upload builds signed with an "upload key" (the existing
  `my-release-key.keystore`/`ANDROID_KEYSTORE_BASE64` can be reused for
  this, no new keystore required) and Google re-signs for distribution
  with a key it manages. Decide this in Play Console during the first
  release creation.
- Once there's a Play Console service account (Google Cloud → enable
  Play Developer API → create a service account → link it in Play
  Console → download the JSON key), automated publishing via
  `bundleRelease` + a plugin like Triple-T's `gradle-play-publisher`
  is realistic to wire into `build-and-release.yml` — deliberately not
  set up yet since it needs a secret that doesn't exist.

### v1.2.2 — targetSdk 35

Play's console flagged the v1.2.1 upload: it now requires targeting
API 35 (Android 15), not 34 — Google raises this minimum periodically
and this is expected, not something broken. Bumped `compileSdk`/
`targetSdkVersion` 34 → 35 in all five modules together this time
(missing this in just the `common` module is exactly what broke the
first targetSdk 34 attempt — see v1.2.1 above).

Checked what API 35 actually enforces before bumping blindly, same as
the 34 pass: the one real behavior change here is edge-to-edge display
being forced on by default for apps targeting 35, with the old
opt-out APIs becoming no-ops. The app has no explicit
`WindowCompat`/insets handling anywhere (verified — only one
`fitsSystemWindows="true"` in `collapsing_toolbar.xml`, nothing else).
This isn't a crash risk like the API 34 foreground-service/receiver
issues were, but it can mean content rendering behind the status/nav
bar on screens that don't already handle insets well — and it
potentially touches every screen, which can't be checked visually in
this sandbox. **Not fixed, flagged for Erik to eyeball after
sideloading v1.2.2** rather than attempting a blind UI-wide change.

**Confirmed broken on-device** — Erik reported message content blended
into the status bar and nav bar, matching the predicted risk exactly.
There's no user-facing Android Settings toggle for this (it's enforced
by the OS based on the app's declared `targetSdkVersion`, not a
runtime per-app setting; the "developer options app compatibility
override" trick only works on debuggable apps / userdebug-eng Android
builds, not a normal signed release APK on a normal phone).

Fixed in v1.2.3 with `android:windowOptOutEdgeToEdgeEnforcement` —
Google's own sanctioned temporary opt-out for exactly this transition
(restores the pre-35 behavior of respecting `statusBarColor`/
`navigationBarColor`/`windowLightStatusBar`, which is what this app's
whole theming system in `themes.xml` is built around, in both
light/`values/themes.xml` and dark/`values-night/themes.xml` — added
to `AppBaseTheme` in both, plus `AppLaunchTheme`, since
`QkThemedActivity` applies `AppTheme` → `AppBaseTheme` at runtime and
that's the only place proper insets handling would otherwise be
needed screen-by-screen). This is a stopgap, not a real fix: Google
has stated this opt-out attribute stops working in a future SDK
level, so this app will eventually need actual `WindowCompat`/insets
handling across its UI to look right edge-to-edge. Not attempted now
since it's a much bigger, unverifiable-in-this-sandbox change than a
one-line theme opt-out — noted here for whenever that becomes
unavoidable.

Discussed doing the real insets work immediately instead of the
opt-out. Decided against it for this pass: it touches essentially
every screen (Conductor Controllers + Activities + dialogs + the
full-screen gallery viewer), the message compose bar's IME-inset
handling specifically is the part most edge-to-edge migrations get
wrong, and none of it can be visually verified in this sandbox — a
wrong guess there risks trading one visible bug for several new ones,
discovered only after more sideload round-trips. **Erik agreed to
ship v1.2.3 with the opt-out now and tackle real insets handling next
week**, ideally screen-by-screen with device testing as we go rather
than one large blind change.

Also left `com.android.tools.build:gradle` at 8.2.2 (predates API 35's
release) rather than bumping AGP preemptively — compileSdk mismatches
with AGP are usually just a lint warning, not a hard failure, and an
AGP bump is a bigger, separate risk (often drags in a Gradle wrapper
bump too). Verify via the usual `build-on-pull.yml` pass before
assuming this is fine; if AGP itself needs bumping, that CI run will
show it.

Also worth knowing for later: Play is separately moving toward
requiring 16 KB memory page size support for apps with native
libraries (a different requirement than targetSdk). This project
bundles Realm, which ships native `.so` files — likely the next thing
Play flags after this, not urgent yet.

Play Console also warned (not blocking) that the `.aab` for v1.2.3
contains native code (Realm) with no debug symbols uploaded, making
native crash/ANR reports harder to read in Android vitals. Deferred —
not worth doing before real users exist to generate crash reports.
Whenever it's worth it, AGP can generate these automatically via
`android.buildTypes.release.ndk.debugSymbolLevel` in
`presentation/build.gradle`, wired into `build-and-release.yml`.

## Post-launch roadmap (after the first Play release)

Requested by Erik, deliberately deferred rather than done now — the
priority is getting v1 through Play review first.

1. **MMS backup support.** Extend backup/restore to cover MMS, not
   just SMS — MMS messages carry attachments and more structure than
   SMS, so the backup file format and `BackupRepositoryImpl`'s
   read/write logic both need to grow to handle them. Manual/
   user-triggered only, same as the existing SMS backup flow
   (`BackupController`/`BackupPresenter`) — **no auto-backup**. Erik
   explicitly wants the user to trigger backups themselves rather
   than the app doing it automatically (e.g. on Wi-Fi connect); that
   idea was considered and dropped.
2. **Remove the stale "SMS only" disclaimer.** Once (1) ships,
   update `R.string.backup_disclaimer` ("Currently, only SMS is
   supported by Backup and Restore. MMS support and scheduled backups
   will be coming soon!") on the Backup screen — at minimum drop the
   "MMS support... coming soon" half since it'll be true. The
   "scheduled backups" half should just be removed outright (not
   reworded to "coming soon" indefinitely), since auto/scheduled
   backup isn't planned at all now. This is a consequence of (1), not
   an independent task; don't touch the string until MMS backup is
   actually shipped.
3. **Deleted-message trash/recovery.** A new "Trash" entry in the
   main nav drawer (`presentation/src/main/res/layout/drawer_view.xml`
   — sits alongside the existing `archived` `LinearLayout` row at
   line 51, same `DrawerRow`/`DrawerIcon`/`DrawerText` style pattern,
   placed under/near "Archived") listing recently deleted messages
   with a restore action. Needs actual design decisions before
   building: this app currently hard-deletes messages
   (`MessageRepository.deleteMessages`) rather than soft-deleting, so
   this requires a schema change (a `deletedAt`/`isDeleted` field on
   `Message`, a new Realm schema version bump like the ones in the
   Message Sorting work), updating every delete call site to
   soft-delete instead, filtering soft-deleted messages out of normal
   conversation views, and auto-purging permanently after **30 days**
   in the trash (Erik confirmed this retention period) — likely a
   daily `JobService` following the same pattern as
   `AutoDeleteService`/`OtpRetentionService`.
4. **Custom inbox tabs.** Let the user create, rename, delete, and
   reorder tabs, instead of the fixed Personal/Transactions/
   Promotions/Starred set. Requested UX: a "+" at the end of the tab
   strip to add a new (user-named) tab; long-press an existing tab for
   a Delete/Rename menu; long-press also enables drag-to-reorder.

   Membership decision (Erik confirmed): custom tabs work exactly
   like the existing "Move to..." feature — moving a conversation to
   a custom tab sets an override *and* persists a rule for that
   sender, so future messages from them land in the same tab
   automatically. Same mechanism as today's
   `Conversation.categoryOverride` + `SenderCategoryRule` (the
   existing "Move to..." toolbar action from the Message Sorting
   work), just extended to arbitrary user-defined tabs instead of
   only the 3 fixed classifier categories.

   Still a bigger change than it sounds: today `Tab`
   (`feature/conversations/Tab.kt`) is a fixed 4-value enum, and
   `categoryOverride`/`SenderCategoryRule` are typed around the fixed
   `Category` enum (PERSONAL/TRANSACTIONAL/PROMOTIONAL). Needs a
   persisted, ordered list of user-defined tabs (new Realm model,
   another schema bump) to replace the hardcoded enum, and
   `categoryOverride`/`SenderCategoryRule`/`ConversationRepository.
   getConversationsByCategory`/`ConversationsPagerAdapter` all need to
   move from "typed to 3 fixed categories" to "keyed by an arbitrary
   tab ID." Starred stays separate either way (it's the `isStarred`
   flag, not a category).
5. **Change the notification bar icon.** Erik wants the status bar /
   notification tray icon changed. Current assets are flat PNGs, only
   at `drawable-xxxhdpi/` (no other density buckets exist for these):
   `ic_notification.png` (new message), `ic_notification_worker.png`
   (background sync), `ic_notification_failed.png` (send failure) —
   referenced from `NotificationManagerImpl.kt`.

   Design direction (Erik confirmed): reuse the app's current
   launcher icon design — the chat bubble with three dots
   (`ic_launcher_foreground.xml`: cream `#F5F1E6` bubble path, three
   `#2F4A3D` dot paths, recovered in the v1.1.0 decompile pass) —
   rather than a brand-new design. Since Android status bar icons must
   be a flat white silhouette on transparent (no color, per Android's
   notification icon guidelines), the three dots can't stay a second
   fill color like they are on the launcher icon — they'll need to
   become transparent cutouts in the bubble silhouette (e.g. an
   evenOdd/subtracted path) so the icon renders as one white bubble
   shape with three punched-out dots, not two colors. Needs new art
   for all three variants (new message/worker/failed) in this style —
   not yet produced. Note the actual app *launcher* icon itself is a
   separate asset and is unaffected by this — only the small
   status-bar notification icons change.
6. **Pinch-to-zoom text size in the message thread.** Erik wants a
   toggle (in addition to the existing font size setting) that lets
   the user pinch-to-zoom the message thread itself to freely resize
   text, instead of being limited to the 5 fixed steps
   (`Preferences.textSize`: SMALL/NORMAL/LARGE/LARGER/SUPER —
   `domain/.../util/Preferences.kt`) that `TextViewStyler.setTextSize`
   currently maps to fixed dp values per text role
   (`common/util/TextViewStyler.kt`) and which apply app-wide via
   every `QkTextView`/`QkEditText`. The existing "Font size" row lives
   in `SettingsController`/`SettingsState` (`binding.textSize`,
   `R.string.settings_text_size` picker dialog, `R.array.text_sizes`)
   — the new toggle should sit near it, following the same
   toggle-then-behavior-change pattern as other settings switches in
   that controller.

   This needs actual design decisions before building, since pinching
   only makes sense scoped to the conversation thread (not every
   `QkTextView` in the app the way the current preference works): a
   `ScaleGestureDetector` on the message `RecyclerView` in
   `MessagesAdapter`/the compose screen, a persisted scale factor
   (per-conversation or global — TBD), and a decision on whether it
   fully replaces the fixed-size setting when enabled or just acts as
   a temporary multiplier on top of whichever fixed size is currently
   selected.
7. **Rename the "QK Reply" section in Settings.** In
   `notification_prefs_activity.xml`, the category header
   (`qkreplyTitle`, `@string/settings_category_qkreply`, currently
   "QK Reply") should become "Quick Reaction Force Reply", and the
   toggle row underneath it (`qkreply` `PreferenceView`,
   `@string/settings_qkreply_title`, also currently "QK Reply") should
   be abbreviated to "QRF Reply". Just a strings.xml rename — the
   `settings_qkreply_summary`/`_tap_dismiss_*` strings and the actual
   QK Reply popup feature/functionality are unaffected.
8. **Move "Support the developer" out to the main Settings menu.**
   Currently it's a `PreferenceView` row (`R.id/support`,
   `@string/about_support_title` "Support the developer",
   `@string/about_support` summary) inside the About screen
   (`about_controller.xml`/`AboutPresenter.kt`, opens
   `ExternalNavigator.showVenmoDonation()`). Erik wants it moved to
   sit on the main Settings screen, under the existing "About Foxhole
   Messages" row (`R.id/about`, `settings_controller.xml:241`,
   `@string/settings_about_title`) — i.e. promoted up a level rather
   than requiring a tap into About first. Also append "Tap here" after
   the word "development" in the summary text (currently
   `@string/about_support`: "All features are free — donate via Venmo
   if you'd like to support development").
