# Android Production Release

## Signing authority

The Sonviaf Android release key is a long-lived product identity. It is stored outside this repository and must never be committed, uploaded as a release asset, or copied into CI logs.

Local builds read `keystore.properties` from the repository root. Use `keystore.properties.example` as the schema. The real keystore and its recovery record must be backed up together in at least two encrypted locations controlled by the product owner.

## Build

```powershell
cd apps/android
.\gradlew.bat clean testReleaseUnitTest assembleRelease
```

Release builds enable R8 code optimization and Android resource shrinking. If `keystore.properties` is absent, Gradle produces an unsigned release artifact; when it is present, the APK is signed with the strongest schemes applicable to the configured minimum Android version. The current build verifies with APK Signature Scheme v2/v3 and emits a v4 `.idsig` sidecar.

Current production signing certificate SHA-256:

```text
36F7396BCC3BEB7E1F048E5964092FC9E1A94865E8D3019E64208FD10CEABFA0
```

Any APK using the production package name but a different certificate is not an authorized Sonviaf production update.

## Verification

Before publishing:

1. Run unit tests and the Release build from a clean tree.
2. Verify the APK certificate with `apksigner verify --verbose --print-certs`.
3. Confirm the package, version, label, SDK bounds and launcher icon with `aapt dump badging`.
4. Install on a clean test device and verify cold start, playback, seeking, gestures, playback modes and local-library persistence.
5. Publish the SHA-256 digest with the release.

## Compatibility boundary

The current production package remains `com.moodify.app` so installations can retain local user data. A device carrying a Debug-signed build cannot accept a Production-signed APK as an in-place update. Do not uninstall or clear that device until its local data has been exported or the user explicitly accepts the reset.

## Rollback

Rollback uses the same production signing key and a new, higher `versionCode`; Android does not permit an ordinary downgrade. Never replace or regenerate the signing authority for an existing package.
