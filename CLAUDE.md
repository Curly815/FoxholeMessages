# Foxhole Messages — project notes

Foxhole Messages is a rebrand of QUIK/QKSMS (GPLv3). `applicationId` is
`com.foxhole.messages`. The internal Kotlin/Java package intentionally
stays `dev.octoshrimpy.quik` (directories under `com/moez/QKSMS/`) —
changing the AGP `namespace` would require rewriting imports across
~400 source files, so only `applicationId`/branding was changed, not
the source package.

## v1.0 work in progress

Finalizing v1.0 (`versionCode 2239`, `versionName '1.0'`, bumped in
`presentation/build.gradle`). Planned changes, in order:
1. Version bump + changelog (`data/src/main/assets/changelog.json`,
   F-Droid metadata) — done.
2. Venmo donation option alongside the existing PayPal button on the
   Plus/About screen.
3. Link thumbnail previews in message bubbles (Open Graph/Twitter Card
   metadata via `me.saket:unfurl`, fetched off the main thread, cached
   in-memory since this app uses Realm rather than Room).

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
   this should be a new version/tag.
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
