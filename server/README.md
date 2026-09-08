# FullSkat server

The server half of FullSkat, a Wear OS voice assistant. The watch sends audio and chat
requests; the server holds the OpenRouter key, recognizes speech and relays chat
completions. It stores no dialogs, only a passcode hash and the tokens it has issued.
The wire contract is in [`docs/PROTOCOL.md`](../docs/PROTOCOL.md).

## Quick start with Docker

```sh
cd server
cp .env.example .env          # set OPENROUTER_API_KEY at minimum
docker compose up -d --build
curl http://localhost:8080/healthz   # {"ok": true, "paired": false}
```

State lives in the `fullskat-data` volume (`/data/state.json` inside the container).
For local speech recognition build with `docker compose build --build-arg EXTRAS=whisper`
and set `STT_BACKEND=faster_whisper`.

## Quick start with a virtualenv

```sh
cd server
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"              # add ",whisper" for faster-whisper
cp .env.example .env                 # edit; .env is read from the working directory
python -m fullskat_server            # HOST/PORT default to 0.0.0.0:8080
pytest -q                            # run the test suite
```

## How pairing works

The server starts without a passcode. The **first watch that calls `POST /v1/pair`
sets the passcode** (4 to 8 digits) and receives a token (`201`, `first_setup: true`).
Every later watch must send the same passcode (`200`) and gets its own token.
Re-pairing the same `device_id` replaces its previous token.

Tokens are 256-bit random strings; the server keeps only their SHA-256 hashes, and the
passcode as a PBKDF2-HMAC-SHA256 hash (200k iterations, random salt), all in
`DATA_DIR/state.json`.

To **reset the passcode** (and revoke every token) stop the server, delete
`state.json` in `DATA_DIR` (`docker compose down && docker volume rm server_fullskat-data`
for the compose volume, or `docker compose exec fullskat rm /data/state.json`), and
start it again. The next pairing call sets a new passcode; watches must pair again.

Pairing is throttled per client IP: 5 attempts per minute, and 10 consecutive wrong
passcodes lock the IP for 15 minutes.

## Putting it behind HTTPS

The watch should talk to the server over HTTPS. Any reverse proxy works; Caddy gets a
certificate automatically and proxies WebSockets without extra configuration:

```
assistant.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

When a proxy sits in front, set `TRUST_PROXY_HEADERS=true` so pairing limits use the
real client IP from `X-Forwarded-For`. Leave it `false` when the server is reached
directly, otherwise clients could spoof their IP.

With nginx, add `proxy_http_version 1.1;`, `proxy_set_header Upgrade $http_upgrade;`,
`proxy_set_header Connection "upgrade";` and `proxy_buffering off;` (for SSE) to the
location block.

## Choosing a speech backend (`STT_BACKEND`)

| Backend | Setup | Notes |
| --- | --- | --- |
| `openrouter` (default) | none beyond `OPENROUTER_API_KEY` | Sends WAV to an audio-capable chat model (`STT_OPENROUTER_MODEL`, default `google/gemini-3.5-flash-lite`). Good Russian and English, no local resources. |
| `openai` | `STT_OPENAI_BASE_URL`, `STT_OPENAI_API_KEY`, `STT_OPENAI_MODEL` | Any OpenAI-compatible `/audio/transcriptions` endpoint: OpenAI `gpt-4o-transcribe`, Groq `whisper-large-v3-turbo`, a self-hosted server, ... |
| `faster_whisper` | `pip install "fullskat-server[whisper]"` | Local `faster-whisper` (`WHISPER_MODEL`, `WHISPER_DEVICE`, `WHISPER_COMPUTE_TYPE`). Lowest latency on a GPU box; the model downloads on first use. |

Partial transcripts are produced about every `PARTIAL_INTERVAL_MS` while speech is
detected; the utterance ends on a client `stop`, after `SILENCE_MS` of silence, or at
`MAX_UTTERANCE_S`.

## Rate limits

| What | Key | Default |
| --- | --- | --- |
| Pairing | client IP | `PAIR_PER_MIN=5`, lock after `PAIR_LOCK_FAILURES=10` for `PAIR_LOCK_MINUTES=15` |
| Chat | token | `CHAT_PER_MIN=20`, `CHAT_PER_DAY=500` |
| Listen sessions | token | `LISTEN_PER_MIN=40`, `LISTEN_PER_DAY=1000` |

Rejections are `429` with a `Retry-After` header (or an `error` frame with
`rate_limited` on the WebSocket). Counters are in memory and reset on restart.

## Chat models

`OPENROUTER_MODEL` is the default; the watch may request any model listed in
`OPENROUTER_ALLOWED_MODELS`. `OPENROUTER_REASONING_EFFORT` (default `high`) is sent as
`{"reasoning": {"effort": ...}}`; set it empty to omit it for models that do not support
reasoning. History sent upstream is trimmed to the last `MAX_HISTORY_MESSAGES` /
`MAX_HISTORY_CHARS`, and answers are capped at `MAX_COMPLETION_TOKENS`.

## Cost notes

* Every chat request sends the system prompt plus up to 12k characters of history;
  reasoning models with `effort=high` can spend several times more output tokens than
  the visible answer. Use `google/gemini-3.5-flash` or a lower effort for cheaper chats.
* The `openrouter` speech backend sends the whole utterance for every partial (about
  once per second while you speak) plus once for the final: a 10 s utterance costs
  roughly ten short audio requests. Raise `PARTIAL_INTERVAL_MS` to reduce this, or use
  `faster_whisper` locally where partials are free.
* Per-token daily limits (`CHAT_PER_DAY`, `LISTEN_PER_DAY`) are the safety net for a
  lost or stolen watch; set them to what you are willing to pay for in a day.

## Environment variables

See [`.env.example`](.env.example) for the full list with defaults and comments.
