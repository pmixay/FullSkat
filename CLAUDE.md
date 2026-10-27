# FullSkat

## Building and testing

* **Never install or use the Android SDK locally, and never run Gradle builds of the watch app** (`./gradlew assemble*`, unit tests, lint). The APK is built by the GitHub Actions workflow (`.github/workflows/build-apk.yml`), which runs only on pushes to `main` and `claude/**` branches and on tags (no pull-request or manual triggers). Push the branch and read the CI result instead (job logs via the GitHub tools).
* The Python server (`server/`) is cheap to test locally: `cd server && pip install -e ".[dev]" && pytest -q`. CI runs the same.
