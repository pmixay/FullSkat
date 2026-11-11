# FullSkat client–server protocol

The watch app (client) talks to a small server that holds the provider keys
(Claude API, OpenRouter and/or Kimi Code) and forwards chat requests. The server stores nothing about
dialogs; dialogs live on the watch. Speech recognition runs on the watch; no audio is
ever sent to the server.

Base URL: the app is built for a single fixed server, `https://skat.arbuz.lol`; the user
cannot change it. (Self-hosting means changing `ServerEndpoint.URL` in the app.)

All endpoints except `POST /v1/pair` and `GET /healthz` require `Authorization: Bearer <token>`.

The pairing responses and `GET /v1/me` describe the models on offer:

* `model` default model, `models` all models of the working providers (flat, for old clients).
* `effort` default effort of the default model (empty string if it has none).
* `model_efforts`: `{"<model>": {"efforts": ["low", "high"], "default": "high"}}`. `efforts` are the
  levels the model accepts, lowest first, from `none`, `minimal`, `low`, `medium`, `high`, `xhigh`,
  `max`; an empty list means effort is not adjustable.
* `providers`: `{"<id>": {"ok": true, "reason": "", "models": ["..."], "usage": {"remaining": 0.4,
  "label": "$4.20"}|null}}` for every configured provider (`anthropic`, `openrouter`, `kimi`), in
  priority order. `ok` is the server's own diagnosis; only working providers list models. `usage.remaining`
  is the share of budget left (0..1, may be null) and `label` a display amount.
* `web_search` is the server's `WEB_SEARCH` master switch.

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

* `model` is optional; the default is the default model of the first working provider.
  Only models of working providers are accepted: `400 model_not_allowed` for an unknown model,
  `503 provider_unavailable` when its provider is down, `503 no_provider` when none works.
* `effort` is optional: `none`, `minimal`, `low`, `medium`, `high`, `xhigh` or `max`; absent or
  `null` uses the model's default (see `model_efforts`). Any other value is `400 invalid_request`.
  A level the model does not accept is replaced by the nearest supported one, and ignored for a model
  whose effort is not adjustable.
* `web_search` is optional boolean; absent or `null` uses the server default. `false`
  disables web search for this request. `true` only enables it if the server setting
  `WEB_SEARCH` is on: that setting is a master switch, so the client can turn search off but
  never on when the operator disabled it.
* The model may search the web (`WEB_SEARCH`, default on); the search itself is
  transparent to the client.
* The server prepends its own system prompt (short plain-text answers by default, in the
  language of the user; Markdown, LaTeX in `$...$` / `$$...$$` and fenced code blocks only when
  they really help, because the watch renders them; watch context).
* The server keeps the last 24 messages / 12k characters of history.
* Response: `text/event-stream`. Events:
  * `event: thinking`  `data: {"text": "partial piece"}` (many) - the model's reasoning
    summary, streamed before or between `delta` events; absent when the model sent none or the
    server has `SHOW_THINKING=false`
  * `event: delta`  `data: {"text": "partial piece"}` (many)
  * `event: done`   `data: {"text": "<full answer>", "model": "...",
    "usage": {"prompt_tokens": n, "completion_tokens": n},
    "thinking": "<full thinking text, empty string if none>"}`
  * `event: error`  `data: {"error": "<code>", "message": "...", "retry_after": 90}` (`retry_after`
    seconds, only when known). Codes: `upstream_rate_limited` (provider pause), `upstream_quota`
    (provider credits spent), `upstream_auth` (provider rejected the server's key), `upstream_error`,
    `internal_error`.
* Limits per token: 20/min (`429 rate_limited`), 500/day (`429 daily_limit`), both with
  `Retry-After`. Request body max 64 KiB.
