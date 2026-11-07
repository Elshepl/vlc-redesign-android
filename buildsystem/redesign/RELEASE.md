# VLC Redesign for Android: building a release

## Version

- `redesignVersionName` (root `build.gradle`) is the app's version: About, What's new and the APK name
  (`VLC-Android-<version>-<abi>.apk`). It follows the desktop's VLC Redesign version.
- `versionCode` comes from `redesignVersionName` (from 0.5.5): `3100000 + 10 * (major * 10000 + minor * 100 +
  patch)`, so 0.5.5 = `3105050`; each APK adds `10000000` and its ABI digit (arm64-v8a = `13105056`). Nothing to
  raise by hand, and it only grows with the version whatever upstream's base. (Up to 0.5.4 the code was upstream's
  3.7.2 = `30702xx` with a release counter in the tens digit: 0.5.4 = `3070290`, its last step; every new code is
  above it, so 0.5.5 installs over 0.5.4.)

## Signing

No key is kept in the repository. The `signedRelease` build type reads the key from the environment at release
time:

| Variable | Meaning |
|---|---|
| `RD_KEYSTORE_FILE` | path to the keystore (`.jks` / `.keystore`), outside the repository |
| `RD_KEYSTORE_PASSWORD` | the keystore's password |
| `RD_KEY_ALIAS` | the key's alias |
| `RD_KEY_PASSWORD` | the key's password (the keystore's when not set) |

```
RD_KEYSTORE_FILE=... RD_KEYSTORE_PASSWORD=... RD_KEY_ALIAS=... \
  gradle --no-daemon :application:app:assembleSignedRelease
```

Without the variables `signedRelease` falls back to upstream's `keyStoreFile` / `storealias` / `storepwd`
Gradle properties; with neither, use `assembleRelease` (unsigned) and sign with `apksigner` afterwards.
The application id is `org.kuchisofts.vlcredesign` (`.debug` for debug builds): the app installs beside VLC. The
release key is kept outside the repository; every release must be signed with the same key, or users cannot update.

Check the result: `apksigner verify --print-certs <apk>` (v1 + v2 schemes) and `aapt dump badging <apk>`
(versionCode, versionName, the four ABIs or the split APKs).

## What the release check expects (desktop `extras/juxtopposed/release/release-check.py`)

- The designer credit: "Juxtopposed" is named in a string the app shows (`application/*strings*.xml`, About).
- The version: `redesignVersionName` equals the desktop's release (About shows "Version 0.4.0 for Android
  <ABI> - based on VLC <libvlc>").
- The What's new card (`res/values/rd_whats_new.xml`) lists the release's changes.
- Nothing private: no owner name, paths, hosts or e-mail in the APK, the strings or the commit messages; no key,
  no IPTV playlist (`*.private.*`) and no Azure material in the repository.
- Upstream: how far `redesign` is behind VideoLAN's master, merged and built before the release.
- coverage.md and parity.md (in the shots folder) describe the release's state; the items that need the real
  phone and tablet are listed there.
