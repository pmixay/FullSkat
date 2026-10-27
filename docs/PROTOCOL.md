# FullSkat client–server protocol

The watch app (client) talks to a small server that holds the provider keys
(Claude API or OpenRouter) and forwards chat requests. The server stores nothing about
dialogs; dialogs live on the watch. Speech recognition runs on the watch; no audio is
ever sent to the server.

Base URL: the app is built for a single fixed server, `https://skat.arbuz.lol`; the user
cannot change it. (Self-hosting means changing `ServerEndpoint.URL` in the app.)

All endpoints except `POST /v1/pair` and `effort` (in the pairing responses and `/v1/me`) is the server's default reasoning effort for
the active provider (`ANTHROPIC_EFFORT` or `OPENROUTER_REASONING_EFFORT`; empty string if none).
`web_search` is the server's `WEB_SEARCH` master switch.

`GET /healthz` require
`Authorization: Bearer <token>`.

Errors are JSON: `{"error": "<code>", "message": "<human text>"}` with an HTTP
status. Rate-limit rejections use status `429` and a `Retry-After` header
(seconds).

## Pairing

`POST /v1/pair`

```json
{"passcode": "1234", "device_id": "uuid-v4", "device_name": "Galaxy Watch7"}
```

* Passcode: 4–8 ASCII digits.
* If the server has no passcode yet, this call **sets** it and returns
  `201 {"token": "...", "first_setup": true, "server_name": "...", "model": "...", "models": ["..."], "effort": "...", "web_search": true}`.
* Otherwise it verifies the passcode and returns
  `200 {"token": "...", "first_setup": false, "server_name": "...", "model": "...", "models": ["..."], "effort": "...", "web_search": true}`
  or `401 {"error": "invalid_passcode"}`.
* Per-IP limit: 5 attempts per minute; 10 consecutive failures lock the IP
  for 15 minutes (`429`, `error: "locked"`).
* Tokens are random (256-bit, URL-safe). The server keeps a hash of the
  passcode (PBKDF2-HMAC-SHA256, 200k iterations, random salt) and a list of
  issued tokens with device id/name and creation time in `DATA_DIR/state.json`.
* Re-pairing the same `device_id` replaces its previous token.

`GET /v1/me` → `200 {"device_id": "...", "device_name": "...", "model": "...",
"models": ["..."], "effort": "medium", "web_search": true,
"limits": {"chat_per_min": 20, "chat_per_day": 500}}`. Returns `401 {"error": "invalid_token"}` when the
token was revoked (state file reset).

`GET /healthz` → `200 {"ok": true, "paired": true|false}` (no auth).

## Chat (server-sent events)

`POST /v1/chat` with `Accept: text/event-stream`

```json
{
  "dialog_id": "uuid",
  "locale": "ru-RU",
  "model": "claude-haiku-5-5",
  "effort": "high",
  "web_search": false,
  "messages": [
    {"role": "user", "content": "Какая завтра погода?"},
    {"role": "assistant", "content": "..."},
    {"role": "user", "content": "А послезавтра?"}
  ]
}
```

* `model` is optional; default comes from server config (`LLM_PROVIDER`:
  `anthropic` by default with `ANTHROPIC_MODEL=claude-haiku-5-5`, or `openrouter`
  with `OPENROUTER_MODEL`). `model` and `models` in the pairing / `/v1/me` responses
  are the default and the allowlist of the active provider; only allowlisted models
  are accepted (`400 model_not_allowed`).
* `effort` is optional: `"low"`, `"medium"`, `"high"`, `"xhigh"` or `"max"`; absent or
  `null` uses the server default (see `/v1/me`). Any other value is `400 invalid_request`.
  (OpenRouter has no `max` level; the server sends `xhigh` there.)
* `web_search` is optional boolean; absent or `null` uses the server default. `false`
  disables web search for this request. `true` only enables it if the server setting
  `WEB_SEARCH` is on: that setting is a master switch, so the client can turn search off but
  never on when the operator disabled it.
* The model may search the web (`WEB_SEARCH`, default on); the search itself is
  transparent to the client.
* The server prepends its own system prompt (plain text only, no LaTeX, no
  Markdown, short answers, reply in the language of the user, watch context).
* The server keeps the last 24 messages / 12k characters of history.
* Response: `text/event-stream`. Events:
  * `event: thinking`  `data: {"text": "partial piece"}` (many) - the model's reasoning
    summary, streamed before or between `delta` events; absent when the model sent none or the
    server has `SHOW_THINKING=false`
  * `event: delta`  `data: {"text": "partial piece"}` (many)
  * `event: done`   `data: {"text": "<full answer>", "model": "...",
    "usage": {"prompt_tokens": n, "completion_tokens": n},
    "thinking": "<full thinking text, empty string if none>"}`
  * `event: error`  `data: {"error": "<code>", "message": "..."}`
* Limits per token: 20/min, 500/day (`429`). Request body max 64 KiB.
