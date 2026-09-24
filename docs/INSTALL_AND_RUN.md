# Installing a debug build on a device

This describes how to install the debug APK produced by
`.github/workflows/build-apk.yml` and get through first-run setup.

## You need two files, not one

A debug build does **not** carry its assets inside the APK:

```kotlin
// app/src/main/java/com/itsaky/androidide/assets/AssetsInstaller.kt
private val USE_BUNDLED_ASSETS = !BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "instrumentation"
internal val CURRENT_INSTALLER = if (USE_BUNDLED_ASSETS) BundledAssetsInstaller else SplitAssetsInstaller
```

So a debug build uses `SplitAssetsInstaller`, which reads the assets from a file on the
device. Only a release build reads assets bundled inside the APK. `SplitAssetsInstaller`
looks for exactly one path:

```java
// common/src/main/java/com/itsaky/androidide/utils/Environment.java
DOWNLOAD_DIR     = new File(FileUtil.getExternalStorageDir(), "Download");
SPLIT_ASSETS_ZIP = new File(DOWNLOAD_DIR, "assets-" + arch + ".zip");
```

If that file is absent, `preInstall` throws `FileNotFoundException`, and `install()` maps
it to a friendly message:

> Missing installation files. Code on the Go installation might be corrupt or incomplete.

That message means **the payload file is missing**, not that anything is corrupt. The APK
alone is not installable; it is 72 MB and the payload is about 1 GB.

## Requirements

| Requirement | Why |
|---|---|
| A real **arm64** device | `SplashActivity` calls `finishAffinity(); exitProcess(0)` on x86/x86_64 unless the emulator feature flag is on, so an emulator closes with no message |
| Android 9 or newer | `minSdk 28` |
| **At least 4 GB free** | `StorageUtils.getMinimumStorageNeeded()`; the experimental profile wants 6 GB |

The `v8` flavor is arm64-v8a only. On an armeabi-v7a device use the `v7` flavor and the
matching `assets-armeabi-v7a.zip`.

## Steps

1. Open the workflow run and download both artifacts:
   - `cogo-v8-debug-apk`
   - `cogo-v8-assets-payload`

2. Each artifact downloads as a zip wrapper that GitHub adds. Unwrap both. You should be
   left with `CodeOnTheGo-v8-debug-<date>.apk` and `assets-arm64-v8a.zip`.

   Do **not** unzip `assets-arm64-v8a.zip` itself. The installer opens it as an archive.

3. Copy `assets-arm64-v8a.zip` to the device's `Download` folder, keeping the name exactly:

   ```
   /storage/emulated/0/Download/assets-arm64-v8a.zip
   ```

   Or over ADB:

   ```sh
   adb push assets-arm64-v8a.zip /sdcard/Download/assets-arm64-v8a.zip
   ```

4. Install the APK, allowing installation from unknown sources.

   ```sh
   adb install -r CodeOnTheGo-v8-debug-<date>.apk
   ```

5. Launch the app and grant every permission it asks for. The permissions screen is a hard
   gate: `OnboardingActivity` will not advance until
   `areAllPermissionsGranted() && checkToolsIsInstalled()`. "All files access" is granted in
   a separate system settings screen, not in the normal permission dialog.

6. Press Finish. Installation now unpacks roughly 1 GB and takes several minutes. Keep the
   screen on.

Projects are created under `/storage/emulated/0/CodeOnTheGoProjects`.

## If Finish still reports missing installation files

Check, in order:

- the file is at `Download/assets-arm64-v8a.zip`, spelled exactly, not
  `assets-arm64-v8a(1).zip` and not inside a subfolder;
- the ABI matches — an `armeabi-v7a` device looks for `assets-armeabi-v7a.zip`;
- the app really has all-files access;
- the file downloaded completely. Compare its SHA-256 against the digest in the run's
  `Assets payload` annotation.

## Verifying what you downloaded

Each run annotates both files with their size and SHA-256. Compare before installing:

```sh
sha256sum CodeOnTheGo-v8-debug-<date>.apk assets-arm64-v8a.zip
```

## What this build has and has not been shown to do

Verified in CI: it compiles, is signed with a debug key, `aapt2` and `apksigner` accept it,
and the payload contains every entry `AssetsInstallationHelper.expectedEntries` opens, each
non-empty.

Not verified: nothing has launched this build on a device. There is no device or emulator
test anywhere in the repository, so first-run behaviour beyond the checks above is
unproven.

`targetSdkVersion` is 28. That is fine for sideloading and blocks a Google Play upload,
which requires 35.
