# Foxhole Messages — project notes

Foxhole Messages is a rebrand of QUIK/QKSMS (GPLv3). `applicationId` is
`com.foxhole.messages`. The internal Kotlin/Java package intentionally
stays `dev.octoshrimpy.quik` (directories under `com/moez/QKSMS/`) —
changing the AGP `namespace` would require rewriting imports across
~400 source files, so only `applicationId`/branding was changed, not
the source package.

## v1.0 work in progress

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

## Known issue

The `publish` job's "Update F-Droid repo contents" step references
`presentation/build/outputs/apk/release/...`, a path from the `build`
job's filesystem — but `publish` runs on a separate runner that only
has the artifact downloaded to `downloaded/`. This step fails every
run (pre-existing bug, not something we introduced). It does **not**
block the GitHub Release itself, which publishes fine before this step
runs. Only matters if/when someone wants the self-hosted F-Droid repo
on GitHub Pages working.
