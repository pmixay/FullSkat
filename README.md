# FullSkat

An AI assistant for your phone and your Wear OS watch. The **phone app** is the core: you sign up there,
chat with Claude, GPT and Kimi models by text, voice and photos, choose models and settings, and link your
watch. The **watch app** is for speaking: tap the mic (or hold the side key), say it in Russian, read a short
answer, with graphs and figures the model draws. Both show the same conversations: everything syncs through
the server you host, never directly between the devices. Several people can use one server, each with their
own account.

* **Phone app** (`phone/`): Kotlin, Jetpack Compose Material 3. Sign-up, dialogs, chat with images from the
  gallery or the camera, voice input, figures, model settings, linking a watch, devices. Its release build carries
  the watch app's APK and installs it on the watch over wireless debugging.
* **Watch app** (`app/`): Kotlin, Jetpack Compose for Wear OS (Material 3). Target device: Galaxy Watch7
  (SM-L305F), Wear OS 6/7, `compileSdk 37`, `targetSdk 37`, `minSdk 33`.
* **Shared core** (`core/`): the rich-text and figure renderer, the API client, the local copy of the account's
  dialogs (Room) with sync, chat, accounts and attachments; both apps are built on it.
* **Server** (`server/`): Python, FastAPI, SQLite. Accounts, code linking, sync, live events, image attachments
  and the chat relay to the Claude API, OpenRouter, Kimi Code and OpenAI Responses gateways, any of them at
  once. See [`server/README.md`](server/README.md).
* **Protocol** ([`docs/PROTOCOL.md`](docs/PROTOCOL.md)): v2, accounts, linking, sync, attachments, chat.

## How it fits together

1. Install the phone app; it puts the watch app on the watch (Settings → Install the watch app, see below).
2. On the phone, sign up with the server passcode (the first person on a fresh server sets it). There is no name or profile: the app makes a random account id at install and never shows it.
3. Open the watch app: it shows a 6-character code (letters and digits) that changes every 30 seconds, with a
   countdown ring around the bezel. On the phone: Settings → Link a watch, type the code. Done: the watch is
   part of your account.
4. Ask on either device. The server keeps the dialogs; the other device catches up when it opens and, while it
   is open, live (a question asked on the phone appears on the watch as it is being answered). Model, effort,
   web search and drawings are shared settings; vibration stays per device.
5. Answers are generated on the server, so they finish even if the asking device loses its connection.

## What the phone app does

| Feature | How |
| --- | --- |
| Account | Sign up with the server passcode; no name or profile (a hidden random id made at install names the account on the server). Settings lists the devices on your account with their last activity (unlink any of them) and has Sign out. Being unlinked from another device returns the app to sign-up. |
| Install the watch app | Settings → Install the watch app puts the watch app bundled in the phone app on the watch over Wi-Fi, without a computer: the phone finds the watch's wireless debugging on the network (mDNS), pairs once with the 6-digit code from *Pair new device* (the watch remembers the phone's key, kept in no-backup app storage; next time it connects straight away), shows the watch's model, Wear OS/Android version, processor and the FullSkat installed on it, and streams the APK into the package manager with a progress bar. A watch app signed with another key (e.g. a CI build with the debug key) can be replaced after a warning that the watch then has to be linked again. Android 17 asks for the local network ("Nearby devices") permission. Debug builds do not carry the watch app. |
| Link a watch | Settings → Link a watch: six boxes for the code the watch shows (only the code alphabet is accepted, auto-advance, paste); wrong or expired codes and too many attempts are explained. |
| Dialogs | The account's dialogs, newest first, with a one-line preview (formulas as Unicode, drawings and code as `[drawing]`/`[code]`); new dialog button, pull to refresh, swipe to delete, long press to rename or delete. Live updates while the app is open. |
| Chat | Your questions on the right with their photos; answers rendered like on the watch (Markdown, LaTeX, highlighted code, animated figures), with the collapsible reasoning summary and web sources (tap opens the browser, long press copies). A question that did not reach the server says so and can be resent; failed answers show the reason; usage limits show when to try again. Answers keep running if you leave the chat; Stop ends them. |
| Photos | Attach up to 4 images per question from the photo picker or the camera (plain Android capture); they are scaled to 1568 px before upload, shown as chips with progress, and sent to the model as images. Images shared from other apps open a new dialog with them attached. Tap a photo for a full-screen view with pinch zoom. |
| Voice | The mic button dictates with Android's speech recognizer, on the device when it can; the words appear in the text field while you speak. |
| Figures | Inline as on the watch; tap for a full-screen viewer with pinch zoom, drag and double tap to reset and replay. |
| Settings | Model (grouped by provider, with each provider's budget ring), thinking effort (only the levels the model supports), web search and drawings: shared with your watch. |

## What the watch app does

| Feature | How |
| --- | --- |
| On-device recognition | Russian speech is recognised on the watch with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and the streaming Zipformer `small-ru-vosk` model (int8, 23 MB, Apache-2.0); no audio leaves the watch. Partial transcripts are drawn in place as you speak, the final one becomes the user turn. The model and runtime are downloaded by the Gradle build (not stored in git) and packed into the APK. |
| Answers | Server-sent events stream the reply token by token. The system prompt asks for short plain text in the language you spoke, and allows Markdown, LaTeX and fenced code only when they really help. |
| Rich answers | Assistant messages are rendered as Markdown: **bold**, *italic*, lists, quotes, tables and `inline code`; LaTeX math in `$...$`, `$$...$$`, `\[...\]` and `\begin{align}` (fractions, roots, sums, integrals with limits, matrices, cases, aligned blocks, accents, braces, stretchy delimiters, colours, `\text` in Russian and English); fenced code with syntax highlighting for Kotlin, Java, Python, JS/TS, C/C++, C#, Go, Rust, Swift, Ruby, PHP, Lua, Bash, SQL, JSON, YAML, TOML, CSS, HTML/XML and diffs. The math engine is pure Kotlin (no WebView): a LaTeX parser plus a TeX-style layout (atom spacing, script and limit rules, constants from the font's OpenType MATH table) drawn on a Canvas with the bundled [Noto Sans Math](https://github.com/notofonts/math) font (SIL OFL 1.1, `assets/fonts/OFL-NotoSansMath.txt`). Wide equations shrink to fit down to 62 % and then scroll sideways; unsupported commands are shown literally in red instead of failing. |
| Figures | The model can draw: function graphs (also parametric and polar), line, scatter, bar and pie charts, geometry (angle marks, arrows, polygons, circles) and small diagrams, through the server's `draw` tool. The watch renders them natively on a Canvas (its own expression evaluator, π ticks, asymptote-aware sampling, LaTeX labels) and animates them as the model draws them: axes, then each curve traced with a pen dot, fills, points, bars growing, labels. Tap a figure for the full-screen viewer: bezel or crown to zoom, pinch, drag to pan, double tap to reset and replay. Settings → Drawings turns it off. |
| Dialogs | The account's dialogs, synced through the server (a local Room copy on the watch). Long-press a dialog in the list to delete it everywhere. Images attached on the phone show as thumbnails above the question. |
| Default assistant | `VoiceInteractionService` + `RecognitionService`. Choose FullSkat in *Settings → Apps → Default apps → Digital assistant app* (or Settings → Set as assistant inside the app). The assist gesture opens a fresh dialog and starts listening at once. |
| Tile | Swipe-left tile with a big amber mic and today's dialog count. |
| Complication | Round watch-face widget showing only a mic icon (monochromatic or small image) that starts a new dialog. |
| Linking | The app is fixed to `https://skat.arbuz.lol` (there is no address field). It shows a 6-character code (from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`) that changes every 30 seconds, with a ring around the bezel running down; enter it in the phone app. The token is kept AES-GCM encrypted with an Android Keystore key. Settings → Unlink this watch ends it (or unlink it from the phone). |
| Models | Settings → Model (shared with the phone): Claude Haiku 5.5 (default), Sonnet 5.5, Opus 5.5, Fable 5.1, and configured OpenRouter, Kimi Code and OpenAI gateway models (GPT-6.1 Terra, GPT-6 Luna, GPT-6.2 Sol, GPT-6.2 Astra). The list comes from the server and is grouped by provider (collapsed by default, each with a ring showing its budget left); it shows providers whose probes pass. Server-side additions need no watch reinstall. |
| Web search | The assistant searches the web on its own for news, weather, prices and other fresh facts (server setting `WEB_SEARCH`). An answer that used the web gets a collapsed "Sources (n)" row under it (Claude, OpenRouter and OpenAI models report them); expanded, it lists each page's site and title, and a tap opens the page in the paired phone's browser (or a browser on the watch, if there is one). |
| Languages | Speech recognition is Russian-only. The assistant answers in the language the text is in. UI is localized in English and Russian. |

### Power notes

* Charcoal ground, no animation outside the listening state, no wake locks: the screen is
  kept on only while a turn is running.
* The microphone opens for the turn only; an on-watch endpoint detector stops streaming
  2.5 s after you stop talking once words were recognised, gives up after 30 s of silence
  when nothing was heard, and caps a turn at 60 s.
* One HTTP client with a warm TLS connection is reused for every request; there are no
  background services, polling or push registrations.
* Recognition runs on two CPU threads only while a turn is listening; the model is loaded when the app opens.
* Release builds are minified and resource-shrunk (R8), with only `en` and `ru` resources.

## Build

Requirements: JDK 21, Android SDK with `platforms;android-37` and `build-tools;37.0.0`
(set `sdk.dir` in `local.properties` or `ANDROID_HOME`).

```sh
./gradlew :app:assembleRelease :phone:assembleRelease
# app/build/outputs/apk/release/app-release.apk     (watch)
# phone/build/outputs/apk/release/phone-release.apk (phone, with the watch APK inside)
```

The phone release build depends on the watch release build: `phone:bundleWatchApk` copies the watch APK into the
phone's assets as `watch/fullskat-watch.apk` (stored uncompressed). Debug phone builds (unit tests, screenshots) do
not build the watch app. The phone app is built for `arm64-v8a` and `armeabi-v7a` only.

Release signing reads `FULLSKAT_KEYSTORE_FILE`, `FULLSKAT_KEYSTORE_PASSWORD`,
`FULLSKAT_KEY_ALIAS`, `FULLSKAT_KEY_PASSWORD` from the environment and falls back to the
debug key when they are not set, so every build is installable.

### Installing on the watch

From the phone app: Settings → Install the watch app. Prepare the watch once:

1. Watch and phone on the same Wi-Fi.
2. On the watch: Settings → System → About → Versions, tap *Software version* (or *Build number*) 7 times to unlock
   Developer options; then Settings → Developer options → **Wireless debugging** on.
3. Tap **Pair new device** and type the 6-digit code into the phone (the phone usually finds the address itself).
   Pairing is needed once per watch; later the phone connects as soon as wireless debugging is on.
4. Install or update. Afterwards turn Wireless debugging off again: it costs battery.

The installer uses [libadb-android](https://github.com/MuntashirAkon/libadb-android) (Apache-2.0 option of its
GPL-3.0-or-later / Apache-2.0 dual licence; its SPAKE2 pairing library is LGPL-3.0), Conscrypt (Apache-2.0) for
TLS 1.3 and BouncyCastle (MIT-style) for the phone's ADB certificate.

Fallback with a computer (wireless debugging, `adb` 30+):

```sh
adb pair <watch-ip>:<pairing-port>      # the code and port from "Pair new device"
adb connect <watch-ip>:<port>           # the port on the Wireless debugging screen
adb install -r app/build/outputs/apk/release/app-release.apk
```

## CI

`.github/workflows/build-apk.yml` builds both release APKs on every push to `main` and `claude/**` branches, on
tags `v*` (push only: no pull-request or manual triggers). The uploaded artifacts `fullskat-watch-apk` and
`fullskat-phone-apk` are single zips, compressed at level 9, containing only `fullskat-watch.apk` and
`fullskat-phone.apk`; the phone APK also contains the watch APK (same version and key), so installing the phone app
is enough to put the watch app on the watch. It also runs the unit tests of all three Android modules, a client-server interop test
(the Kotlin client against the real server with a scripted model, `tools/interop/`) and, on `claude/**`
branches, renders the watch and phone screenshots (artifact `fullskat-screenshots`). To sign with
your own key add the repository secrets `FULLSKAT_KEYSTORE_B64` (base64 of the `.jks`),
`FULLSKAT_KEYSTORE_PASSWORD`, `FULLSKAT_KEY_ALIAS`, `FULLSKAT_KEY_PASSWORD`. The same
workflow runs the server test suite.

## Run the server

```sh
cd server
cp .env.example .env     # set ANTHROPIC_API_KEY
docker compose up -d --build
```

Put it behind HTTPS (Caddy example in the server README), then sign up in the phone app: the first
registration sets the server passcode; share it with the people who should get their own accounts.
Dialogs and images now live on the server (`DATA_DIR`: `fullskat.db` and `attachments/`), so back that up.

## Design

Three directions were drawn for the 1.3-inch round screen; the shipped one is
"Ember": charcoal ground, amber accent, chat bubbles, a round floating mic and a bezel
ring that breathes with your voice while listening. All colors and the bubble shapes
live in `core/src/main/java/com/fullskat/core/ui/theme/Ember.kt`, shared by both apps.

## Repository layout

```
phone/      Android phone application (Gradle module)
app/        Wear OS application (Gradle module)
core/       Android library both apps use: renderer, API client, sync, chat, accounts
server/     FastAPI server, Dockerfile, tests
tools/      screenshot rendering (render/), client-server interop server (interop/)
docs/       PROTOCOL.md
.github/    CI workflow
```
