# FullSkat server

The server half of FullSkat: a phone app (the core: sign-up, text, voice and photos) and a Wear OS watch
app (voice, linked to a phone account with a code). Both apps recognize speech on the device, so the server
never receives audio. It holds the API keys and relays chat to the Claude API, OpenRouter, the Kimi Code
platform and OpenAI Responses gateways such as Codex LB (any of them, at the same time), and it keeps every
person's dialogs, images and preferences, so all their devices see the same conversations.
The wire contract is in [`docs/PROTOCOL.md`](../docs/PROTOCOL.md).

## Quick start with Docker

```sh
cd server
cp .env.example .env          # set ANTHROPIC_API_KEY, OPENROUTER_API_KEY, KIMI_API_KEY and/or OPENAI_API_KEY
docker compose up -d --build
curl http://localhost:8080/healthz   # {"ok": true, "users": 0}
```

Data lives in the `fullskat-data` volume (`/data` inside the container, see [Storage](#storage-and-backups)).
The image includes HEIC support (`pillow-heif`).

## Quick start with a virtualenv

```sh
cd server
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"              # or ".[heic]" for a server with HEIC uploads, "." without either
cp .env.example .env                 # edit; .env is read from the working directory
python -m fullskat_server            # HOST/PORT default to 0.0.0.0:8080
pytest -q                            # run the test suite
```

## Accounts and devices

A **user** is a person with their own dialogs, images and preferences; a **device** (phone, watch or other)
belongs to one user and has its own token.

* **Sign-up** happens on the phone (`POST /v1/register`). The server passcode (4 to 8 digits) is the
  invitation: whoever knows it can create an account. The server starts without one; the **very first
  sign-up sets it** (`first_setup: true`), every later one must match it. Registering a known `device_id`
  again returns that device's account with a fresh token.
* **Linking** a watch (or a second phone) uses a code: the new device calls `POST /v1/link/start` and shows a
  6-character code that changes every 30 seconds (`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, no 0/O or 1/I); the
  signed-in phone types it (`POST /v1/link/claim`) and the new device, long-polling `GET /v1/link/status`,
  receives its token at once. Codes are HMAC-SHA256 of a server secret (generated on first start, kept in
  the database), the link and the 30-second window; the previous window's code still works for the first 5
  seconds after a rotation, and a pending link expires after 10 minutes without a poll.
* **Devices** are listed with `GET /v1/devices` and unlinked with `DELETE /v1/devices/{id}`; the device's
  token stops working at once and its open event stream gets `revoked`.

Tokens are 256-bit random strings; the server keeps only their SHA-256 hashes, and the passcode as a
PBKDF2-HMAC-SHA256 hash (200k iterations, random salt).

To **change the passcode**, stop the server and delete its row (`sqlite3 DATA_DIR/fullskat.db "DELETE FROM
passcode"`); the next sign-up sets a new one. Accounts and devices are kept. To start over completely, stop
the server and delete `DATA_DIR` (or the Docker volume: `docker compose down && docker volume rm
server_fullskat-data`).

**Upgrading from a pre-v2 server**: on its first start the server imports the old `DATA_DIR/state.json`
once. Its passcode becomes the server passcode, and every token it issued becomes a watch device of a user
named "Owner", so watches with the old app keep working (they use the old, stateless chat shape, which is
still supported). The file is renamed to `state.json.migrated`. `POST /v1/pair` answers `410 gone`: new
watches link with a code.

## Sync, live events and chat

The server is the source of truth for dialogs and messages; each device keeps a copy. Every change of a
user's dialogs, messages or preferences takes the next number of that user's sequence, and a device asks
`GET /v1/sync?since=<seq>` for what came after (deleted objects stay as tombstones). An open app keeps
`GET /v1/events` (server-sent events) open and syncs when it says `changed`.

`POST /v1/chat` saves the question and an assistant message with status `streaming`, then generates the
answer in a task of its own: the HTTP response only watches it. If the client goes away the answer still
finishes and is saved; the streaming text is saved (and announced) at most every 1.5 seconds so another
device sees it grow, and `POST /v1/messages/{id}/stop` ends it early. The history sent to the model comes
from the database, not from the client. If the server stops mid-answer, the answer is saved as an error
with the text so far.

## Attachments

Photos are uploaded on their own (`POST /v1/attachments`, raw JPEG, PNG, WebP or HEIC, at most 10 MB) and
then referenced by chat messages (at most 4 each). The server turns them upright (EXIF orientation),
converts them to sRGB, strips all metadata (EXIF, GPS, ICC), scales them to at most 1568 px on the long side
and stores them as JPEG with a 320 px thumbnail. HEIC needs the optional `pillow-heif` package (installed in
the Docker image, `pip install ".[heic]"` otherwise); without it HEIC uploads get `415`. The images of the
newest `MAX_HISTORY_IMAGE_MESSAGES` (2) user messages that have any go to the model as base64, in each
provider's own format (Claude image blocks, `image_url` parts for OpenRouter and Kimi, `input_image` for
OpenAI Responses), so pick a model that can see; older ones become `[image]` in the history. An image is
deleted when the last message using it is deleted.

## Storage and backups

Everything lives in `DATA_DIR`:

* `fullskat.db`: SQLite (WAL mode, so also `fullskat.db-wal` and `fullskat.db-shm` while it runs) with the
  passcode hash, users, devices (token hashes), pending links, dialogs, messages, attachment records,
  preferences and the link secret. The schema version is kept in the database and migrated on start; a
  database from a newer server is refused.
* `attachments/<user_id>/<id>.jpg` and `<id>.thumb.jpg`: the images.

All database access runs on one thread, so a single server process is the supported setup (do not run
several workers on the same `DATA_DIR`). To **back up** a running server, copy the database with SQLite's
online backup, then the attachments:

```sh
sqlite3 DATA_DIR/fullskat.db ".backup 'backup/fullskat.db'"
cp -r DATA_DIR/attachments backup/
```

(or stop the server and copy `DATA_DIR`). After restoring an older backup, devices that synced later hold
sequence numbers the server no longer knows: sign them out and in again (or clear their data) so they sync
from zero.

## Putting it behind HTTPS

The apps should talk to the server over HTTPS. Any reverse proxy works; Caddy gets a certificate
automatically and streams SSE without extra configuration:

```
assistant.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

When a proxy sits in front, set `TRUST_PROXY_HEADERS=true` so the per-IP limits use the real client IP from
`X-Forwarded-For`. Leave it `false` when the server is reached directly, otherwise clients could spoof their
IP.

With nginx, add `proxy_http_version 1.1;`, `proxy_buffering off;` (for SSE), a `proxy_read_timeout` above
30 s (the link long-poll waits up to 25 s, event streams ping every 25 s) and `client_max_body_size 10m;`
(uploads) to the location block.

## Rate limits

| What | Key | Default |
| --- | --- | --- |
| Sign-up | client IP | `PAIR_PER_MIN=5`, lock after `PAIR_LOCK_FAILURES=10` wrong passcodes for `PAIR_LOCK_MINUTES=15` |
| Link start | client IP | `LINK_START_PER_MIN=20` |
| Link claim | user | `LINK_CLAIM_PER_MIN=10`, lock after `LINK_CLAIM_LOCK_FAILURES=5` wrong codes for `LINK_CLAIM_LOCK_MINUTES=5` |
| Chat | user (all devices) | `CHAT_PER_MIN=20`, `CHAT_PER_DAY=500` |
| Uploads | user | `UPLOADS_PER_HOUR=60` |

Rejections are `429` with a `Retry-After` header. Counters are in memory and reset on restart.

## Chat models

There is no provider switch. Every provider whose API key is set is a candidate; the server probes
each one (a cheap authenticated request: Claude `models.list`, OpenRouter `/key`, Kimi `/models`) and
offers its models to the apps only while the probe passes. A failed probe is retried after 30 s, a
passed one is trusted for 5 min, and a rejected key during a chat disables the provider at once. If a
key is revoked or a provider goes down its models disappear from the apps' pickers by themselves, and
come back when it works again. `/v1/me` reports the diagnosis (`providers`).

Priority is Claude, then OpenRouter, then Kimi, then OpenAI: the default model is the default model of the first
working provider. Models are routed to the provider that lists them.

* **Claude API**: `ANTHROPIC_MODEL` (default `claude-haiku-5-5`) and `ANTHROPIC_ALLOWED_MODELS`
  (Haiku 5.5, Sonnet 5.5, Opus 5.5, Fable 5.1). `ANTHROPIC_EFFORT` (default `medium`) is the default
  `output_config.effort`. `ANTHROPIC_MAX_TOKENS` caps one answer including thinking tokens.
* **OpenRouter**: `OPENROUTER_MODEL` and `OPENROUTER_ALLOWED_MODELS` (default GPT-6.1 Terra and
  GPT-6 Luna). `OPENROUTER_REASONING_EFFORT` (default `high`) is the default
  `{"reasoning": {"effort": ...}}`. `OPENROUTER_MAX_TOKENS` caps one answer including reasoning.
* **Kimi Code platform** (`KIMI_*`): the OpenAI-compatible API of a Kimi Code membership at
  `KIMI_BASE_URL` (overseas `https://api.kimi.ai/coding/v1`, mainland China
  `https://api.kimi.com/coding/v1`), with an API key from the
  [Kimi Code console](https://www.kimi.com/code/console). `KIMI_MODEL` (default `k3.1-pro`) and
  `KIMI_ALLOWED_MODELS` (`k3.1-pro,kimi-for-coding`; `kimi-for-coding` is what the
  [docs](https://www.kimi.com/code/docs/en/) call K2.8 Preview; `k3`, `k3-256k` and the `*-highspeed` variants
  can be added, depending on your plan). The server sends its own
  `User-Agent` (`FullSkat/1.0`), since Kimi Code asks clients to keep their real identifier. Web
  search offers Kimi's builtin `$web_search` tool: the server answers the model's search calls and asks
  again, at most `WEB_SEARCH_MAX_USES` times per answer. Kimi Code does not document that tool, so if the
  endpoint rejects it the server drops it, logs a warning and answers without web search from then on.
  The usage ring reads `GET <base>/usages`, which is not in the public docs; if it fails there is no ring.

* **OpenAI / Codex LB** (`OPENAI_*`): uses the Responses API at `OPENAI_BASE_URL` (direct OpenAI
  `https://api.openai.com/v1`, local Codex LB `http://127.0.0.1:2455/v1`). Set `OPENAI_API_KEY` to a
  dedicated gateway key. The default allowlist contains `gpt-6.1-terra`, `gpt-6-luna`, `gpt-6.2-sol`
  and `gpt-6.2-astra`. An authenticated `/models` probe controls availability; Codex LB's model metadata
  supplies supported reasoning levels, or set `OPENAI_MODEL_EFFORTS` for a gateway without metadata.
  Responses text, refusal text, reasoning summaries and token usage are translated to the existing
  chat SSE events. Failed, incomplete or disconnected responses produce an error, never a successful
  truncated answer. `WEB_SEARCH` controls the built-in `web_search` tool (sent with
  `include: ["web_search_call.action.sources"]`, which Codex LB allows); this backend does not accept
  `max_tool_calls`, so `WEB_SEARCH_MAX_USES` does not cap OpenAI searches. `SHOW_THINKING=false` omits
  summaries and suppresses them even if a gateway sends one. The usage ring uses the tightest
  unfiltered quota in Codex LB's `/v1/usage`; gateways without it have no ring.

The server tells the apps the models of the working providers, grouped by provider (`providers`),
and the efforts each model supports (`model_efforts`).

### Efforts

Providers disagree about reasoning effort levels (Claude: low to max; Kimi K3: low, high, max;
Gemini: minimal to high; some models none at all) and they differ per model, so the server asks where
it can: OpenRouter's public model list (`reasoning.supported_efforts`) and the Claude Models API
(`capabilities.effort`). Kimi Code has no such endpoint, so for each Kimi model, in order:

1. `KIMI_MODEL_EFFORTS`, a per-model list as JSON. The default is
   `{"k3.1-pro":"low,high,max","kimi-for-coding":"low,high,max"}`. The docs say K3.1 Pro always thinks
   (max by default, thinking off is rejected), so it has no `none` level; they do not list its levels, but
   its sibling K3 takes low/high/max and OpenRouter's catalog lists K3.1 Pro as max/high/low/none. An empty
   string means not adjustable;
2. only with `KIMI_EFFORTS_FROM_CATALOG=true`: OpenRouter's public entry `moonshotai/<model>`;
3. `KIMI_EFFORTS`, one list for every model that has neither (empty = not adjustable).

An unknown model, such as a preview, is therefore not adjustable until you list it. The apps show
only the levels the selected model supports; a requested level the model lacks is moved to the nearest
supported one, and a model that is not adjustable ignores it. The chosen level is sent as
`reasoning_effort`; Kimi Code does not document the parameter name, so none is sent unless the app
picks a level. Answers are cached for an hour.

### Usage ring

Each provider reports the share of its budget left, shown as a ring beside its name in the apps'
model picker: OpenRouter credits (the key's spending limit, else account credits), Claude's token
rate limit (from response headers; Anthropic has no balance endpoint), Kimi Code's membership quota.
Unknown means no ring.

### Errors

Failures reach the apps as `error` codes: `upstream_rate_limited` (provider pause, with
`retry_after`), `upstream_quota` (credits or quota spent), `upstream_auth` (the provider rejected the
server's key), `upstream_error`, plus `rate_limited`, `daily_limit` (`CHAT_PER_DAY`, HTTP 429),
`no_provider` / `provider_unavailable` (HTTP 503) and `model_not_allowed` (HTTP 400). The apps show
the usage-limit ones (`rate_limited`, `daily_limit`, `upstream_quota`, `upstream_rate_limited`) as a
separate block with an icon and the wait time.

History sent upstream comes from the dialog as stored on the server, trimmed to the last
`MAX_HISTORY_MESSAGES` / `MAX_HISTORY_CHARS` (an image counts as 1000 characters). An answer that failed is
saved with status `error`, its code and the text that arrived.

### Thinking

`SHOW_THINKING=true` (default) streams the model's reasoning summary to the apps as
`thinking` events (see `docs/PROTOCOL.md`); the apps show it collapsed under the answer. With
the Claude API this sends `thinking: {"type": "adaptive", "display": "summarized"}`; with
OpenRouter and Kimi the reasoning deltas are forwarded when the model returns them. Set
`SHOW_THINKING=false` to omit the `thinking` request field and send no summaries.

### Figures

`FIGURES=true` (default) gives the model a `draw` tool for function graphs, parametric and polar curves,
line/scatter/bar/pie charts and geometry or small diagrams. The server validates each call (known item
types, finite numbers, expressions with known names and balanced brackets, SVG path characters), drops
what cannot be drawn and tells the model, then streams the figure to the apps inside the answer as a
```` ```figure ```` JSON block (see `docs/PROTOCOL.md`). The apps evaluate the expressions and draw and
animates the figure itself, so a figure costs a few hundred output tokens, not an image.
`FIGURES_MAX_PER_ANSWER` (default 3) caps the calls per answer; after that the tool stays defined but
`tool_choice` is `none` (Claude, OpenRouter, OpenAI) or the tool is withheld (Kimi). Like `WEB_SEARCH`
this is a master switch: the apps' Drawings setting can only turn it off per request.

### Web search

`WEB_SEARCH=true` (default) lets the model search the web when a question needs fresh facts
(news, weather, prices, scores). With the Claude API this is the `web_search` server tool, at
most `WEB_SEARCH_MAX_USES` searches per answer (default 3); it has to be enabled for your
organization in the Claude Console, and each search is billed on top of tokens. With
OpenRouter it is the `openrouter:web_search` server tool (the model decides whether to search; the
older `web` plugin, which searched on every request, is deprecated), at most `WEB_SEARCH_MAX_USES`
searches where OpenRouter can enforce it (its own engines and Claude's native search; OpenAI's native
search ignores the cap). With the OpenAI Responses API it is the built-in `web_search` tool; with Kimi
its `$web_search` tool. Set `WEB_SEARCH=false` to turn it off.

The pages an answer drew on are sent to the apps as `sources` in the `done` event (at most 8, see
`docs/PROTOCOL.md`) and shown under the answer: Claude's citations (or its search results when it
cited none), OpenRouter's `url_citation` annotations, and OpenAI's `url_citation` annotations (or the
pages its search consulted, requested with `include: ["web_search_call.action.sources"]`). Kimi's
`$web_search` reports no pages. The extraction lives in `fullskat_server/sources.py`.

`WEB_SEARCH` is a master switch: a client can send `"web_search": false` to turn search off for
a request, but can never turn it on when the operator disabled it. Likewise a client may send
`"effort"` per request to override the model's default (see Efforts above).
Requests that leave these out use the user's preferences (`/v1/prefs`), then the server defaults, which
are reported as `effort` and `web_search` in the sign-up, link and `/v1/me` responses.

## Cost notes

* Every chat request sends the system prompt plus up to 12k characters of history;
  thinking models with a high effort can spend several times more output tokens than
  the visible answer. The default is the cheap Haiku with `medium` effort; web searches
  add a per-search fee and the retrieved pages count as input tokens.
* Photos cost input tokens too (roughly 1.5k tokens for a 1568 px image); only the newest two user
  messages with images send theirs.
* The per-user daily limit (`CHAT_PER_DAY`, all devices of a person together) is the safety net for a
  lost or stolen device; set it to what you are willing to pay for in a day per person. Unlink a lost
  device from the phone (`DELETE /v1/devices/{id}`).

## Environment variables

See [`.env.example`](.env.example) for the full list with defaults and comments.

## Local Codex LB deployment

See [the deployment notes](../docs/CODEX_GATEWAY.md) for service locations, verification and restart commands.
The installed apps already load providers, models and efforts from `/v1/me` when the app or its Model
settings opens. Adding this provider needs only a server restart: keep `DATA_DIR` (`fullskat.db`) intact,
so the devices' tokens keep working. No reinstall or re-linking is needed.
