# FullSkat

A voice assistant for Wear OS. Tap the mic (or hold the side key when FullSkat is the
default assistant), speak Russian or English, watch the words appear while you are still
talking, and read a short plain-text answer. Dialogs stay on the watch; a small server
you host holds the API keys and does the speech recognition.

* **Watch app** (`app/`): Kotlin, Jetpack Compose for Wear OS (Material 3), Room,
  DataStore, OkHttp. Target device: Galaxy Watch7 (SM-L305F), Wear OS 6/7, `compileSdk 37`,
  `targetSdk 37`, `minSdk 33`.
* **Server** (`server/`): Python, FastAPI. Pairing passcode, per-token rate limits,
  streaming speech recognition, chat relay to the Claude API (default, model
  `claude-haiku-5-5`, with web search) or OpenRouter. See [`server/README.md`](server/README.md).
* **Protocol** ([`docs/PROTOCOL.md`](docs/PROTOCOL.md)): pairing, SSE chat, WebSocket listening.

## What the watch app does

| Feature | How |
| --- | --- |
| Streaming recognition | 16 kHz PCM goes up one WebSocket; partial transcripts are drawn in place as they arrive, the final one becomes the user turn. |
| Answers | Server-sent events stream the reply token by token. The system prompt forbids LaTeX, Markdown, tables and emojis, and asks for short plain text in the language you spoke. |
| Dialogs | Stored in Room on the watch. Long-press a dialog in the list to delete it. |
| Default assistant | `VoiceInteractionService` + `RecognitionService`. Choose FullSkat in *Settings → Apps → Default apps → Digital assistant app* (or Settings → Set as assistant inside the app). The assist gesture opens a fresh dialog and starts listening at once. |
| Tile | Swipe-left tile with a big amber mic and today's dialog count. |
| Complication | Round watch-face widget (monochromatic image, small image or short text) that starts a new dialog. |
| Pairing | The app is fixed to `https://skat.arbuz.lol` (there is no address field). Enter a 4–8 digit passcode on a keypad. The first watch that reaches a fresh server sets the passcode. The token is kept AES-GCM encrypted with an Android Keystore key. |
| Models | Settings → Model: Claude Haiku 5.5 (default), Sonnet 5.5, Opus 5.5, Fable 5.1. The list comes from the server's allowlist, so it follows the provider the server runs (Claude API by default, or OpenRouter). |
| Web search | The assistant searches the web on its own for news, weather, prices and other fresh facts (server setting `WEB_SEARCH`). |
| Languages | Speech language Auto / Russian / English. UI is localized in English and Russian. |

### Power notes

* Charcoal ground, no animation outside the listening state, no wake locks: the screen is
  kept on only while a turn is running.
* The microphone opens for the turn only; an on-watch endpoint detector stops streaming
  5 s after you stop talking once words were recognised, gives up after 30 s of silence
  when nothing was heard, and caps a turn at 60 s. The server VAD is a fallback.
* One HTTP client with a warm TLS connection is reused for every request; there are no
  background services, polling or push registrations.
* Release builds are minified and resource-shrunk (R8), with only `en` and `ru` resources.

## Build

Requirements: JDK 21, Android SDK with `platforms;android-37` and `build-tools;37.0.0`
(set `sdk.dir` in `local.properties` or `ANDROID_HOME`).

```sh
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

Release signing reads `FULLSKAT_KEYSTORE_FILE`, `FULLSKAT_KEYSTORE_PASSWORD`,
`FULLSKAT_KEY_ALIAS`, `FULLSKAT_KEY_PASSWORD` from the environment and falls back to the
debug key when they are not set, so every build is installable.

Install on the watch over Wi-Fi debugging:

```sh
adb connect <watch-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

## CI

`.github/workflows/build-apk.yml` builds the release APK on every push to `main`, on
tags `v*`, on pull requests and on demand. The uploaded artifact `fullskat-watch-apk` is
a single zip, compressed at level 9, containing only `fullskat-watch.apk`. To sign with
your own key add the repository secrets `FULLSKAT_KEYSTORE_B64` (base64 of the `.jks`),
`FULLSKAT_KEYSTORE_PASSWORD`, `FULLSKAT_KEY_ALIAS`, `FULLSKAT_KEY_PASSWORD`. The same
workflow runs the server test suite.

## Run the server

```sh
cd server
cp .env.example .env     # set OPENROUTER_API_KEY
docker compose up -d --build
```

Put it behind HTTPS (Caddy example in the server README), open the watch app, enter the
address and pick a passcode.

## Design

Three directions were drawn for the 1.3-inch round screen; the shipped one is
"Ember": charcoal ground, amber accent, chat bubbles, a round floating mic and a bezel
ring that breathes with your voice while listening. All colors and the bubble shapes
live in `app/src/main/java/com/fullskat/watch/ui/theme/Theme.kt`.

## Repository layout

```
app/        Wear OS application (Gradle module)
server/     FastAPI server, Dockerfile, tests
docs/       PROTOCOL.md
.github/    CI workflow
```
