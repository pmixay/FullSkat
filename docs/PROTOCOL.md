# FullSkat client–server protocol (v2)

FullSkat has two client apps: the **phone app** (the core: people sign up there, chat with text, voice
and images, choose models, link their watch) and the **watch app** (voice chat, linked to a phone account
with a code). Both talk only to the server; they never talk to each other directly. The server holds the
provider keys (Claude API, OpenRouter, Kimi Code, OpenAI Responses), relays chat to the models, and keeps
every person's dialogs and images, so all devices of a person see the same conversations.

Base URL: both apps are built for a single fixed server, `https://skat.arbuz.lol` (self-hosting means
changing `ServerEndpoint.URL` in the apps).

Speech is recognised on the device (the watch with its own model, the phone with Android's on-device
recogniser); no audio is ever sent to the server.

## Conventions

* JSON bodies, UTF-8. Times are integers in **milliseconds** since the Unix epoch (`*_at`).
* IDs of dialogs, messages and devices are **client-generated** UUID v4 strings (so a device can create
  them before it hears back); user, attachment and link IDs are made by the server.
* `Authorization: Bearer <token>` on everything except `GET /healthz`, `POST /v1/register`,
  `POST /v1/link/start` and `GET /v1/link/status`. A token belongs to one **device** of one **user**.
* Errors: `{"error": "<code>", "message": "<human text>"}` with an HTTP status. Rate-limit rejections are
  `429` with a `Retry-After` header (seconds). `401 invalid_token` means the device was unlinked or the
  token revoked: the client forgets its session and shows its setup screen (`401 missing_token` without a
  token). Common codes besides the ones named below: `400 invalid_request` (malformed body, query or value),
  `404 not_found`, `409 conflict` (an id that belongs to someone else), `413 payload_too_large`.
* JSON bodies of the small endpoints (everything but chat and attachments) are at most 16 KiB.

## Users and devices

A **user** is a person; each has their own dialogs, images and preferences. A **device** (`kind`: `phone`,
`watch`, `other`) belongs to one user and has its own token. The server stores a passcode hash
(PBKDF2-HMAC-SHA256), users, devices (token hashes only, SHA-256) and the dialogs in
`DATA_DIR/fullskat.db` (SQLite), and images under `DATA_DIR/attachments/`.

### Sign up (phone)

`POST /v1/register` (no auth)

```json
{"passcode": "1234", "name": "Миша", "device_id": "uuid", "device_name": "Pixel 9", "device_kind": "phone"}
```

* The passcode (4–8 digits, else `400 invalid_passcode_format`) is the server's invitation: whoever knows it
  may create an account. The very first registration on a fresh server **sets** it (`201`,
  `first_setup: true`); later ones must match it (`401 invalid_passcode` otherwise).
* Creates a new user named `name` (1–40 characters after collapsing whitespace) with this device, and returns
  `201` (`200` when the `device_id` was already registered: the same device and user, a fresh token, the old
  token stops working; the user is not renamed, the device's name and kind are updated; reinstalling keeps
  the account only if the install kept its device id).
* `device_id` 1–128 characters, `device_name` up to 128 (may be empty), `device_kind` `phone` (default),
  `watch` or `other`. Other malformed bodies are `400 invalid_request`.
* Response: `{"token": "...", "first_setup": false, "server_name": "FullSkat", "user": User, "device": Device,
  ...model info}` (see [Model info](#model-info)).
* Per-IP limits: 5 attempts per minute (malformed requests count); 10 consecutive wrong passcodes lock the IP
  for 15 minutes (`429 locked`).

`User` = `{"id": "u_...", "name": "Миша", "created_at": ms}`;
`Device` = `{"id": "<device_id>", "kind": "phone", "name": "Pixel 9", "created_at": ms, "last_seen": ms, "current": true}`.

### Linking a device with a code (watch)

A new device (a watch, or a second phone or tablet) joins an existing account by showing a code that a
signed-in device enters.

1. The new device asks for a link: `POST /v1/link/start` (no auth)
   `{"device_id": "uuid", "device_name": "Galaxy Watch7", "device_kind": "watch"}` →
   `200 {"link_id": "<secret>", "code": "K7Q2ZP", "expires_in": 23, "period": 30}`.
   * `code`: 6 characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (upper-case letters and digits without
     the look-alikes 0/O and 1/I). The code **changes every 30 seconds**, aligned to the clock
     (window = `floor(unix_seconds / 30)`); `expires_in` is the seconds left in the current window, `period`
     the window length. The device shows it with a countdown ring.
   * `link_id` is a secret only this device knows (256-bit, URL-safe). A pending link expires after 10
     minutes without a status poll. Per-IP limit: 20 starts per minute. `device_kind` defaults to `watch`.
   * Codes are derived per link and window from a server secret (HMAC-SHA256); two pending links never show
     the same code in the same window, and a code never changes within its window.
2. It waits: `GET /v1/link/status?link_id=...&wait=25` (no auth; long-poll, `wait` 0–25 seconds, default 0,
   larger values count as 25). The server answers as soon as something changes, or when `wait` runs out:
   * `{"status": "pending", "code": "XA3M9Q", "expires_in": 30, "period": 30}`: the code rotated (or the
     wait ran out); show the new code and restart the ring. A poll answers at once when the device has not
     been told the current window's code yet (for example when it polls again only after a rotation), so
     re-polling right after each answer always shows the right code.
   * `{"status": "linked", "token": "...", "server_name": "...", "user": User, "device": Device, ...model
     info}`: done. The token is delivered once; afterwards the link is gone.
   * `404 link_expired`: start over (also when the link was claimed but the device was unlinked again before
     it collected its token).
3. On the phone, the signed-in user types the code: `POST /v1/link/claim` `{"code": "K7Q2ZP"}` (case,
   spaces and dashes do not matter) → `200 {"device": Device}` (the new device, now part of this user), or
   `404 invalid_code` (wrong, malformed or expired code: the code of the current window is accepted, and the
   code the link showed in the previous window during the first 5 seconds after a rotation). A device cannot
   claim a link started with its own `device_id` (`400 invalid_request`). Limits per user: 10 attempts per
   minute, 5 consecutive failures lock claiming for 5 minutes (`429 locked`).

A device that is linked again with the same `device_id` keeps its record (`created_at`; it moves to the
claiming user) and gets a new token; the old one stops working as soon as the code is claimed.

### Devices and the session

* `GET /v1/me` → `{"user": User, "device": Device, "server_name": "...", ...model info, "web_search": true,
  "figures": true, "limits": {"chat_per_min": 20, "chat_per_day": 500}}`.
* `GET /v1/devices` → `{"devices": [Device, ...]}` (all devices of the user, oldest first, `current` marks
  the caller).
* `DELETE /v1/devices/{device_id}` → `204`: unlinks a device of the same user (its token stops working;
  it falls back to its setup screen). Unlinking the calling device is signing out. `404 not_found` for a
  device that is not the user's.
* `PATCH /v1/me` `{"name": "Михаил"}` → `{"user": User}`: renames the user (1–40 characters).
* `last_seen` is refreshed at most once a minute.

## Preferences

Per user, shared by all their devices: `{"model": "claude-haiku-5-5" | null, "effort": "high" | null,
"web_search": true, "figures": true}`. `null` (or a missing key) means the server default.

* `GET /v1/prefs` → `{"prefs": {...}, "updated_at": ms}` (every key present; all `null` with `updated_at: 0`
  before the first change).
* `PUT /v1/prefs` with any subset of the keys → the merged `{"prefs": {...}, "updated_at": ms}`. Unknown keys
  are ignored; invalid values are `400 invalid_request` (`model` must be one the server is configured to
  offer, `effort` one of the levels, `web_search` / `figures` booleans; `null` resets a key). Changing prefs
  counts as a change for [sync](#sync) and [events](#live-events); a PUT that changes nothing does not.

Settings that belong to one device (vibration, the watch's tile) stay on that device.

## Dialogs and messages

The server is the source of truth; each device keeps a local copy and catches up with [sync](#sync).

`Dialog`:

```json
{"id": "uuid", "title": "Погода на завтра", "created_at": ms, "updated_at": ms, "deleted": false, "seq": 41}
```

`Message`:

```json
{
  "id": "uuid", "dialog_id": "uuid", "role": "user" | "assistant",
  "content": "text (assistant: Markdown with $LaTeX$, code and ```figure blocks)",
  "thinking": "reasoning summary, empty if none",
  "sources": [{"url": "https://...", "title": "..."}],
  "attachments": [{"id": "a_...", "mime": "image/jpeg", "width": 1568, "height": 1176}],
  "model": "claude-haiku-5-5", "status": "done" | "streaming" | "error", "error": "",
  "created_at": ms, "updated_at": ms, "deleted": false, "seq": 42
}
```

* `status`: `streaming` while the model is still answering (the content grows; see [chat](#chat));
  `error` when the answer failed: `content` keeps what arrived, `error` holds the code (empty string
  otherwise). User messages are always `done`; `model` is empty for them.
* A deleted dialog or message stays as a tombstone (`deleted: true`; title, content, thinking, sources,
  attachments and error emptied) so other devices learn about it.
* IDs are 1–64 characters of `A-Z a-z 0-9 - _` (`400 invalid_request` otherwise). An id another user already
  uses is `409 conflict`; a dialog or message of another user is `404 not_found`, as is an unknown one.

Changes:

* `POST /v1/dialogs` `{"id": "uuid", "title": "", "created_at": ms}` → `201 Dialog` (`200` with the existing
  dialog, tombstone included, if this user already has that id: creating is idempotent). `title` at most 200
  characters; `created_at` optional (default: now).
* `PATCH /v1/dialogs/{id}` `{"title": "..."}` → `Dialog` (`404` for a deleted dialog).
* `DELETE /v1/dialogs/{id}` → `204` (the dialog and its messages become tombstones; deleting a tombstone
  again is `204` too).
* `DELETE /v1/messages/{id}` → `204` (same rules).
* Deleting a message (or its dialog) while its answer is still streaming stops that answer.
* An image no message uses any more after a deletion is deleted with it.
* Dialogs are also created by the first chat message (below); a dialog's title, when empty, becomes the
  first 60 characters of its first user message that has text (whitespace collapsed). Every chat message
  also moves the dialog's `updated_at` (a change with its own `seq`), so the dialog list can be sorted by
  activity.

## Sync

Every change of a user's data (dialog, message, prefs) gets the next number of that user's **sequence**.
A device remembers the highest `seq` it has seen and asks for what came after:

`GET /v1/sync?since=<seq>&limit=500` →

```json
{"dialogs": [Dialog, ...], "messages": [Message, ...], "prefs": {...} | null, "seq": 57, "more": false}
```

* Objects whose `seq` is greater than `since`, in `seq` order, at most `limit` (1–1000, default 500; else
  `400 invalid_request`) of them in all, the prefs counting as one object; `seq` is the highest one returned
  (or `since` when there was nothing), `more: true` means ask again right away. `prefs` is present when
  they changed after `since`.
* Each object appears once, with its latest state and `seq`; numbers of older states are gone, so the
  sequence has gaps. Because an object moves to a new `seq` whenever it changes, a message can arrive in an
  earlier page than its dialog: keep it until the dialog arrives.
* `since=0` downloads everything (tombstones included, so a fresh device can skip them).
* A device syncs when it opens, after each answer, and whenever a [live event](#live-events) says so.

## Live events

`GET /v1/events` with `Accept: text/event-stream` (auth): a long-lived stream an open app keeps while it
is in the foreground.

* `event: changed` `data: {"seq": 57}`: the user's data changed (from any device); sync if `seq` is higher
  than yours. The first event of every stream is a `changed` with the user's current `seq`, so a device
  that reconnects learns at once whether it missed something. Bursts are coalesced into one event with the
  newest `seq`. While an answer streams, its message is saved and announced at most every 1.5 seconds, so a
  second device sees it grow.
* `event: ping` `data: {}` after 25 seconds without another event keeps proxies from closing the connection.
* `event: revoked` `data: {}`: this device was unlinked, or its token was replaced (it registered or was
  linked again); the stream ends.

## Attachments

Images a user attaches to a message (from the gallery or the camera).

* `POST /v1/attachments` with the raw image as the body and `Content-Type: image/jpeg`, `image/png`,
  `image/webp` or `image/heic` (also `image/heif`; parameters such as `; charset=` are ignored) → `201 {"id":
  "a_...", "mime": "image/jpeg", "width": w, "height": h, "size": bytes}` (`width`/`height`/`size` of the
  stored JPEG). The server applies the EXIF orientation, converts to sRGB, strips metadata (EXIF, GPS, ICC),
  flattens transparency onto white, scales the image down to at most 1568 px on the long side and stores it
  as JPEG (quality 85), plus a thumbnail of at most 320 px.
* `413 payload_too_large` above 10 MB (checked while reading). `415 unsupported_media` for another content
  type or anything it cannot decode, including images above 50 megapixels; HEIC/HEIF needs the server's
  optional `pillow-heif` package, without it those uploads are `415` too. Limit: 60 uploads per hour per user
  (`429 rate_limited`).
* `GET /v1/attachments/{id}` → the JPEG; `?size=thumb` → the thumbnail (`?size=full` or no `size`: the
  image; any other value is `400`). Only devices of the owner may read it (`404 not_found` otherwise).
  `Cache-Control: private, max-age=31536000, immutable`.
* An attachment can be used by messages of its owner; at most 4 per message, each once. A message lists
  them as `{"id", "mime", "width", "height"}`. The server sends the images of the newest 2 user messages
  that have any to the model (base64, in each provider's own format), so pick a model that can see; older
  images are replaced by the text `[image]` in the history. An upload no message uses stays until it is
  used; once a used image's last message is deleted, the image is deleted too.

## Chat

`POST /v1/chat` with `Accept: text/event-stream`:

```json
{
  "dialog_id": "uuid",
  "message": {"id": "uuid", "content": "Что на фото?", "attachments": ["a_..."], "created_at": ms},
  "answer_id": "uuid",
  "locale": "ru-RU",
  "model": "claude-haiku-5-5",
  "effort": "high",
  "web_search": true,
  "figures": true
}
```

* The server saves the user message (idempotent by id; the dialog is created if it does not exist), creates
  the assistant message `answer_id` with `status: "streaming"`, and answers from the dialog's history as
  stored on the server: the last 24 messages / 12k characters (an image counts as 1000 characters) that are
  not deleted and are `done` or `error` with text or images, images of the recent turns included (see
  [attachments](#attachments)). The answer's `created_at` is never before its question's.
* `message.created_at` is optional (default: now). `answer_id` must differ from `message.id`; `attachments`
  are ids of the caller's uploads (`400 invalid_request` for unknown or repeated ones, or more than 4). A chat
  into a deleted dialog is `404 not_found`; ids of another user are `409 conflict`. Nothing is saved when a
  request is refused.
* Repeating a request with the same `answer_id` (a retry after a lost connection) asks the model nothing: the
  stream joins the running answer from its start, or, when it already ended, sends just its `done` (with
  zero `usage`) or `error`.
* `model`, `effort`, `web_search`, `figures` are optional: absent or `null` means the user's
  [preferences](#preferences), then the server defaults. `web_search` and `figures` follow the server's
  master switches (`WEB_SEARCH`, `FIGURES`): a request can turn them off, never on. `effort` is one of
  `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`; a level the model lacks is moved to the
  nearest supported one. A requested model that is unknown: `400 model_not_allowed`; provider down:
  `503 provider_unavailable`; none working: `503 no_provider`. A *preferred* model that is not available
  right now falls back to the server default instead. `content` may be empty when there are attachments;
  otherwise empty (or blank) text is `400 invalid_request`.
* The answer is generated on the server and **does not depend on this connection**: if the client goes
  away, the answer still finishes, is saved and reaches every device through sync. The stream is a view of it.
  If the server stops while an answer streams, the answer is saved with `status: "error"` (`internal_error`)
  and the text so far.
* Stop: `POST /v1/messages/{answer_id}/stop` → `204` once the answer is saved; the answer ends with what it
  has (status `done`, and the stream ends with `done`). Stopping an answer that already ended changes
  nothing (`204`); an unknown message is `404 not_found`.
* If the answer is deleted while it streams (its message or dialog), it stops and the stream ends with a
  `done` whose `message` is the tombstone.
* Response events:
  * `event: thinking` `data: {"text": "partial piece"}` (many): the model's reasoning summary, before or
    between `delta`s; absent when the model sent none or the server has `SHOW_THINKING=false`.
  * `event: delta` `data: {"text": "partial piece"}` (many). A [figure](#figures) arrives as one delta.
  * `event: done` `data: {"text": "<full answer>", "model": "...", "usage": {"prompt_tokens": n,
    "completion_tokens": n}, "thinking": "...", "sources": [...], "message": Message, "seq": 58}`.
    `sources`: the web pages the answer drew on (empty list when it did not search): at most 8,
    deduplicated, in order of first use: the pages it cites, or, if it cites none, the pages the search
    consulted; `url` is http(s), `title` may be empty. Claude, OpenRouter and OpenAI report sources; Kimi's
    `$web_search` does not.
  * `event: error` `data: {"error": "<code>", "message": "...", "retry_after": 90}` (`retry_after` seconds,
    when known). Codes: `upstream_rate_limited`, `upstream_quota`, `upstream_auth`, `upstream_error`,
    `internal_error`. The assistant message is saved with `status: "error"` and whatever text arrived.
* Limits per **user** (all their devices together, legacy chat included): 20/min (`429 rate_limited`),
  500/day (`429 daily_limit`), with `Retry-After`. Request body at most 64 KiB (`413 payload_too_large`;
  images go through attachments).
* The server prepends its system prompt (short answers in the user's language; Markdown, LaTeX, code and
  figures only when they help; the device the question came from: a watch gets 1–3 sentences, a phone or
  other device somewhat longer but still concise answers).

### Legacy chat

Watch builds from before v2 send `{"dialog_id", "locale", "model", "effort", "web_search", "figures",
"messages": [{"role", "content"}, ...]}` with the whole history and a token issued by the old `POST
/v1/pair`. The server still answers those statelessly (nothing is saved; preferences are not applied; a body
with a `messages` key is always read this way, with any token). The events are the v1 ones: `done` carries
no `message` or `seq`. On the first start of a v2 server, the tokens in the old `DATA_DIR/state.json` become
watch devices of one user named "Owner" (no user is created when it has no tokens), and its passcode stays
the server passcode; the file is then renamed to `state.json.migrated` and never imported again. `POST
/v1/pair` itself is gone (`410 gone`): a new watch links with a code.

## Model info

`POST /v1/register`, a `linked` link status and `GET /v1/me` describe the models on offer:

* `model` default model, `models` all models of the working providers (flat).
* `effort` default effort of the default model (empty string if it has none).
* `model_efforts`: `{"<model>": {"efforts": ["low", "high"], "default": "high"}}`. `efforts` are the levels
  the model accepts, lowest first, from `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`; an empty
  list means effort is not adjustable.
* `providers`: `{"<id>": {"ok": true, "reason": "", "models": ["..."], "usage": {"remaining": 0.4, "label":
  "$4.20"} | null}}` for every configured provider (`anthropic`, `openrouter`, `kimi`, `openai`), in priority
  order. Only working providers list models. `usage.remaining` is the share of budget left (0..1, may be
  null) and `label` a display amount.
* `web_search`, `figures`: the server's master switches.

`GET /healthz` → `200 {"ok": true, "users": n}` (no auth).

## Figures

The model can draw with a `draw` tool (server: `fullskat_server/figures.py`, its JSON schema is the
contract). The server checks and compacts each call, answers the tool and lets the model go on; the figure
reaches the clients inside the answer text, as one `delta` holding a fenced block on its own lines:

````
```figure
{"title":"y = sin x","x_range":[-6.28319,6.28319],"items":[{"type":"function","expr":"sin(x)"}]}
```
````

The JSON is a single line (backticks in strings are escaped as ```, so no line can close the fence)
and part of the message content, so it is stored and sent back in history like any other text. Both apps
draw it natively and animate it in item order.

Top level: `title`, `x_range` / `y_range` (`[min, max]`, fitted when absent), `x_label`, `y_label`, `axes`
(default true), `grid` (default = axes), `equal` (same unit on both axes; default = not axes), `items`
(1–32, drawn in order). Coordinates are math-style, y up.

| `type` | fields |
| --- | --- |
| `function` | `expr` in `x`; optional `domain` [a, b], `area` [a, b] (shaded to the x axis) |
| `parametric` | `x`, `y` in `t`; `t` [t0, t1] (default [0, 2π]) |
| `polar` | `expr` = r(`t`), `t` range (default [0, 2π]) |
| `line`, `scatter`, `polygon` | `points` [[x, y], …] (at most 500) |
| `bars` | `values`, optional `labels`; several `bars` items are grouped series |
| `pie` | `values` (≥ 0), `labels`; drawn alone |
| `point`, `text` | `at` [x, y]; `text` for text |
| `segment`, `arrow`, `rect` | `from`, `to` (a rect's opposite corners) |
| `vline`, `hline` | `value` |
| `circle`, `arc` | `center`, `r`; an arc's `start`, `end` in degrees, counterclockwise |
| `angle` | `at` (vertex), `from`, `to` (points on the rays); 90° gets a square mark |
| `path` | `d`: SVG path data in figure coordinates |

Every item may have `label` (a legend entry for curves and series, a label in place for shapes; LaTeX in
`$...$`), `color` (`amber`, `blue`, `magenta`, `violet`, `aqua`, `red`, `gray`, `white` or `#RRGGBB`),
`dash`, `fill`. Expressions: numbers, the variable, `pi`, `e`, `+ - * / % ^`, implicit multiplication,
`|x|`, comparisons, `if(c, a, b)` and `sin cos tan cot sec csc asin acos atan sinh cosh tanh exp ln log log10
log2 sqrt cbrt abs sign floor ceil round min max pow mod hypot atan2` (`log` is natural).
