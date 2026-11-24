# Apple mobile sync maintenance

This independent source fork is based on Recly. The maintenance branch is `mobile-sync`. Upstream release v0.3.5-build.50 is the common base (`2ff3ced4`), and upstream proposal #31 is `d4cc9a94`. The fork also bounds file lookup and digest checks before upload completion. A read still running for the same key prevents an overlapping read. A pending check leaves the job eligible for a later pass. The system still owns the upload. Container availability outside settlement, coordinated writes, and global iCloud outages can still delay work. A slow digest that repeatedly exceeds the budget can continue to wait.

## Build and sign

Use JDK 21, Xcode with the iOS and watchOS SDKs and the pinned Gradle wrapper. Use `make core-test` and `make ios-release-test`. See [development](development.md) for Apple frameworks, RecKit tests, provisioning and archives. Limit Gradle to one worker on a small Mac. `make core CORE_GRADLE_ARGS="--max-workers=1"` builds all release slices. The Mac and simulator refresh commands assume a previously staged XCFramework.

For an **in-place update**, use a build offered by the original publisher for the same App Store record. Verify that the publisher identifies the fix commit. Check the bundle identifier, signed application identifier, application groups and iCloud container against the installed original. Record the version and build on the phone after installation. A GitHub store-submission archive does not establish device installability.

For **side-by-side development**, from the desired committed revision run:

```sh
python3 apple/scripts/prepare-private-build.py ../field-loom-device --bundle com.example.fieldloom
```

Replace the example identifier with an identifier available to your team. Use a new destination. The script creates a detached checkout, rewrites Apple identities, gives the home-screen app and container a separate display name, generates distinct icons, separates Mac Application Support and disables the upstream Mac updater. Its local xcconfig selects the separate iCloud entitlements. The default private build number is 51; increment it using `--build-number` on subsequent releases. The generated app Info.plist records `FieldLoomSourceCommit`. Preserve the source commit and exact identifier for later builds. The script prepares source only.

Register the phone, watch companion, widgets and share extension identifiers shown in the generated Xcode project. Provision the phone group `group.<bundle>`, the watch group `group.<bundle>.watch`, and the shared phone/Mac container `iCloud.<bundle>`. The watch sends to its paired phone companion. In the generated checkout build the shared frameworks first. In Xcode open `apple/Rec.xcworkspace`, select the phone scheme and physical device, select the team and automatic development signing for the app and embedded targets, and run. Install the paired watch app and check its companion identity. Enable developer mode when required. For TestFlight use the fork's own App Store record and signing configuration. The release script also validates OAuth configuration; configure your own client IDs for a public release. Keep certificates, profiles, OAuth settings and device identifiers local.

The generated checkout is intended for private device testing. Before publishing a modified binary, finish a public branding review across all localizations, product surfaces and assets. Publish corresponding AGPL source with the same additional permissions and notices. See [license](../LICENSE), [additional permissions](../LICENSE-EXCEPTIONS.md), [trademark policy](../TRADEMARK.md) and [third-party notices](../THIRD-PARTY-NOTICES.md).

## Diagnose a device stall

For a development-signed device, record Points of Interest in Instruments while reproducing the stall. Select the phone app and its `iCloudSync` category. Each interval around a native file operation carries the Swift method name and an interval ID. A long open interval can distinguish container resolution, directory lookup, metadata, digest or import. The trace supplies no recording names or contents. A wait in the metadata query outside coordinated file work requires a separate stack sample. Record whether later jobs advance while that interval is open.

## Data and acceptance

1. Before changing the original, inventory each pending row on the phone. Keep a separate verified audio copy. Record its start time, duration, size and SHA-256 locally. Retain both apps for a parallel installation.
2. A separate identity starts with its own queue, app groups and cloud container. Use the original app's Share Audio and the new app's Import to transfer a copy as a **new recording/job**. Test with one copy, match decoded audio and duration, and record both identifiers before scaling. Import is not proven until exercised on the signed devices.
3. Record a new short clip in the matching watch app. Check its time and duration on the phone. On the Mac, inspect the matching container's Documents/recly/memo folder. Require finalized metadata, matching recording identity and start time, all named parts, byte lengths and SHA-256 for each part. Check for extra files and duplicate recording IDs across folders.
   Run `python3 apple/scripts/verify-mac-recording.py "<recording folder>"` with Python 3.9+ to verify the folder and print the part hashes locally. Compare the returned time, identity and duration to the watch and phone observations.
4. Recheck every old row after a scheduler retry and after an app restart. Record original-job completion, export-only and reimported jobs separately. Recheck a short recording while a long one waits, then verify the long recording finishes when the wait clears.
5. To roll back side-by-side testing, resume using the original phone/watch pair. Keep the new app and container until new originals have verified independent copies. For in-place updates, preserve the data and use a compatible build from the original team; an App Store downgrade or restoration of the task database cannot be assumed.

The shared-core tests include ICloudFilesTest, ICloudUploadTest, ICloudJobTest, RecordingDirectoryTest and ExecutorTest. These cover pending reads, quota failure, retry without another copy, paths after updates and queue recovery in fixtures. Signed device verification remains open. Never publish real media, filenames, hashes from a personal library, device logs or account information as test fixtures.
