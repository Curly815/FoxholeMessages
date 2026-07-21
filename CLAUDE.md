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

## Self-hosted F-Droid repo

The app was never submitted to the official F-Droid store — `fdroid/`
in this repo is a self-hosted repo (published to GitHub Pages) that
someone would add manually as a custom repo source in the F-Droid app.
It's separate from the GitHub Releases, which have always worked fine.

Investigated why it never actually populated anything on GitHub Pages
(user asked "why won't the app show up on F-Droid"). Turned out to be
five stacked bugs, not just the one originally suspected — verified
each by installing fdroidserver 2.2.1 locally (matching what CI
installs) and running `fdroid update` against this repo's actual
config/metadata:
1. The `publish` job's "Update F-Droid repo contents" step referenced
   `presentation/build/outputs/apk/release/...`, a path that only
   exists on the `build` job's runner — `publish` runs on a separate
   runner with just the `downloaded/` artifact. Fixed by locating the
   APK under `downloaded/` instead.
2. The `publish` job had no source checkout at all, so `config.yml`
   and `fdroid/metadata/` never existed there either.
3. The "restore previous gh-pages content" step ran in the `build`
   job, whose filesystem `publish` never sees — dead code. Moved it
   into `publish`.
4. `fdroid update --config config.yml` isn't a real flag — fdroidserver
   auto-discovers `config.yml` from the working directory. Fixed by
   running from `fdroid/` (which is also where `fdroid/metadata/`
   already lived, so that mismatch is now resolved too).
5. `fdroid/metadata/com.foxhole.messages.yml` had a `PackageName`
   field left over from an older metadata format — current fdroidserver
   gets the package id from the filename and treats `PackageName` as
   an unrecognized field, aborting `fdroid update` before it writes
   anything. Removed.

Fixed 1-5 above (commit on `master`, doesn't touch app releases at
all — separate job, separate signing key, no version bump). **Still
not fully working**: `fdroid update` hard-requires a repo signing
keystore to produce an index at all — confirmed locally, there's no
flag/config combination that produces a fully unsigned repo despite
older fdroidserver docs suggesting otherwise. `fdroid/config.yml`
documents what's needed (`repo_keyalias`/`keystore`/`keystorepass`/
`keypass`, keystore path + password via `{env: VAR}` indirection so no
secret lives in the committed file). This needs a new keystore
generated for the repo's signing identity (separate from
`ANDROID_KEYSTORE_BASE64`, which only signs the APK) and new GitHub
secrets for it — both require the repo owner, follow-up work.

None of this blocks the GitHub Release itself, which publishes fine
before this step runs.
