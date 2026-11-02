# FullSkat

## Building and testing

* The APK is built by the GitHub Actions workflow (`.github/workflows/build-apk.yml`), which runs only on pushes to `main` and `claude/**` branches and on tags (no pull-request or manual triggers). Push the branch and read the CI result via the GitHub tools; do not build release APKs locally.
* The Android SDK may be installed locally for **screenshot rendering and compile checks** (owner's decision, replacing the earlier "never install" rule): SDK at `/root/android-sdk` (`local.properties` with `sdk.dir`, git-ignored), platform `android-37.0`. There is no `/dev/kvm`, so no emulator: real screens are rendered on the JVM with Robolectric/Roborazzi on a 216 dp round display (Galaxy Watch7 40 mm, SM-L305F): `./gradlew :app:testDebugUnitTest -Pscreenshots --tests 'com.fullskat.watch.ScreenshotTest'` writes PNGs to `app/build/screenshots`; cut them to the round display with `python3 tools/render/circle.py app/build/screenshots <out dir>`. HTML design mockups render with `node tools/render/render.js` (see `tools/render/`).
* The Python server (`server/`) is cheap to test locally: `cd server && pip install -e ".[dev]" && pytest -q`. CI runs the same.
