# FullSkat client–server protocol

The watch app (client) talks to a small server that holds the provider keys
(Claude API or OpenRouter), recognizes speech, and forwards chat requests. The server stores nothing about
dialogs; dialogs live on the watch.

Base URL: the app is built for a single fixed server, `https://skat.abuz.lol`; the user
cannot change it. (Self-hosting means changing `ServerEndpoint.URL` in the app.)

All endpoints except `POST /v1/pair` and `GET /healthz` require
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
  `201 {"token": "...", "first_setup": true, "server_name": "...", "model": "...", "models": ["..."]}`.
* Otherwise it verifies the passcode and returns
  `200 {"token": "...", "first_setup": false, "server_name": "...", "model": "...", "models": ["..."]}`
  or `401 {"error": "invalid_passcode"}`.
* Per-IP limit: 5 attempts per minute; 10 consecutive failures lock the IP
  for 15 minutes (`429`, `error: "locked"`).
* Tokens are random (256-bit, URL-safe). The server keeps a hash of the
  passcode (PBKDF2-HMAC-SHA256, 200k iterations, random salt) and a list of
  issued tokens with device id/name and creation time in `DATA_DIR/state.json`.
* Re-pairing the same `device_id` replaces its previous token.

`GET /v1/me` → `200 {"device_id": "...", "device_name": "...", "model": "...",
"models": ["..."], "limits": {"chat_per_min": 20, "listen_per_min": 40, "chat_per_day": 500,
"listen_per_day": 1000}}`. Returns `401 {"error": "invalid_token"}` when the
token was revoked (state file reset).

`GET /healthz` → `200 {"ok": true, "paired": true|false}` (no auth).

## Chat (server-sent events)

`POST /v1/chat` with `Accept: text/event-stream`

```json
{
  "dialog_id": "uuid",
  "locale": "ru-RU",
  "model": "claude-haiku-5-5",
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
* The model may search the web (`WEB_SEARCH`, default on); this is transparent to
  the client.
* The server prepends its own system prompt (plain text only, no LaTeX, no
  Markdown, short answers, reply in the language of the user, watch context).
* The server keeps the last 24 messages / 12k characters of history.
* Response: `text/event-stream`. Events:
  * `event: delta`  `data: {"text": "partial piece"}` (many)
  * `event: done`   `data: {"text": "<full answer>", "model": "...",
    "usage": {"prompt_tokens": n, "completion_tokens": n}}`
  * `event: error`  `data: {"error": "<code>", "message": "..."}`
* Limits per token: 20/min, 500/day (`429`). Request body max 64 KiB.

## Listening (WebSocket, one utterance per connection)

`GET /v1/listen` (upgrade) with header `Authorization: Bearer <token>`
(a `?token=` query parameter is also accepted for clients that cannot set
headers).

Client → server:

1. First frame, text: `{"type": "start", "sample_rate": 16000, "encoding":
   "pcm_s16le", "channels": 1, "lang": "auto" | "ru" | "en"}`
2. Then binary frames: raw PCM 16-bit little-endian mono, 16 kHz, ~100 ms
   each (3200 bytes). The server accepts up to 60 s of audio (`MAX_UTTERANCE_S`).
3. Optional text frame `{"type": "stop"}` when the user taps or the client's
   VAD decides the utterance ended. The server then produces the final
   transcript.

Server → client (text frames, JSON):

* `{"type": "ready"}` right after `start` is accepted.
* `{"type": "partial", "text": "..."}` roughly every `PARTIAL_INTERVAL_MS`
  (default 1000) while speech continues. The text is the transcript of the
  whole utterance so far (not a diff).
* `{"type": "speech", "active": true|false}` when server-side VAD flips.
* `{"type": "final", "text": "...", "lang": "ru"}` once, then the server
  closes with code 1000. `text` may be empty if nothing was recognized.
* `{"type": "error", "error": "<code>", "message": "..."}` then close 1008/1011.

End of utterance is decided by whichever comes first: client `stop`, server
VAD silence for `SILENCE_MS` (default 5000 ms) after speech was detected,
`NO_SPEECH_TIMEOUT_S` (default 30) without any speech, or the 60 s cap. The watch itself gives up after 30 s if nothing was heard and no
text arrived, and sends `stop` 5 s after the last speech once text exists.

Limits per token: 40 sessions/min, 1000/day (`429` on the upgrade, or
`error` frame `rate_limited`).

## Speech backends (server config)

`STT_BACKEND`:

* `openrouter` (default): sends WAV to an audio-capable chat model on
  OpenRouter with the same key (`STT_OPENROUTER_MODEL`, default
  `google/gemini-3.5-flash-lite`) and asks for a verbatim transcript.
  No extra setup, good Russian and English.
* `faster_whisper`: local `faster-whisper` (`WHISPER_MODEL`, default
  `large-v3-turbo`; `WHISPER_DEVICE`, `WHISPER_COMPUTE_TYPE`). Best latency
  on a GPU box; optional dependency.
* `openai`: any OpenAI-compatible `/audio/transcriptions` endpoint
  (`STT_OPENAI_BASE_URL`, `STT_OPENAI_API_KEY`, `STT_OPENAI_MODEL`), e.g.
  Groq `whisper-large-v3-turbo` or OpenAI `gpt-4o-transcribe`.

All backends expose the same call: transcribe(pcm16 bytes, sample rate,
language hint, previous partial text) → (text, detected language).
