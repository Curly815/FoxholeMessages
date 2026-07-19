# Foxhole Messages — project notes

Foxhole Messages is a rebrand of QUIK/QKSMS (GPLv3). `applicationId` is
`com.foxhole.messages`. The internal Kotlin/Java package intentionally
stays `dev.octoshrimpy.quik` (directories under `com/moez/QKSMS/`) —
changing the AGP `namespace` would require rewriting imports across
~400 source files, so only `applicationId`/branding was changed, not
the source package.

## Upcoming plans

- Erik plans to make more changes/updates to the app and label the
  next release as **Version 1** (`versionCode`/`versionName` bump in
  `presentation/build.gradle`).
- When that happens, a GitHub Release will need to be cut the same way
  as the `v4.3.6` release (see below).

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
