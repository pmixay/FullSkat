# FullSkat server

The server half of FullSkat, a Wear OS voice assistant. The watch recognizes speech
on-device, so the server never receives audio; it holds the API keys and relays chat
completions to the Claude API (default) or OpenRouter. It stores no dialogs, only a passcode hash and the tokens it has issued.
The wire contract is in [`docs/PROTOCOL.md`](../docs/PROTOCOL.md).

## Quick start with Docker

```sh
cd server
cp .env.example .env          # set ANTHROPIC_API_KEY (OPENROUTER_API_KEY only for LLM_PROVIDER=openrouter)
docker compose up -d --build
curl http://localhost:8080/healthz   # {"ok": true, "paired": false}
```

State lives in the `fullskat-data` volume (`/data/state.json` inside the container).

## Quick start with a virtualenv

```sh
cd server
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
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
certificate automatically and streams SSE without extra configuration:

```
assistant.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

When a proxy sits in front, set `TRUST_PROXY_HEADERS=true` so pairing limits use the
real client IP from `X-Forwarded-For`. Leave it `false` when the server is reached
directly, otherwise clients could spoof their IP.

With nginx, add `proxy_http_version 1.1;` and `proxy_buffering off;` (for SSE) to the
location block.

## Rate limits

| What | Key | Default |
| --- | --- | --- |
| Pairing | client IP | `PAIR_PER_MIN=5`, lock after `PAIR_LOCK_FAILURES=10` for `PAIR_LOCK_MINUTES=15` |
| Chat | token | `CHAT_PER_MIN=20`, `CHAT_PER_DAY=500` |

Rejections are `429` with a `Retry-After` header. Counters are in memory and reset on restart.

## Chat models

`LLM_PROVIDER` picks the chat backend:

* `anthropic` (default): the Claude API through the official SDK. `ANTHROPIC_MODEL`
  (default `claude-haiku-5-5`) is the default model and the watch may request any model in
  `ANTHROPIC_ALLOWED_MODELS` (Haiku 5.5, Sonnet 5.5). `ANTHROPIC_EFFORT`
  (default `medium`) is sent as `output_config.effort`; set it empty to omit it.
  `ANTHROPIC_MAX_TOKENS` caps one answer including thinking tokens.
* `openrouter`: `OPENROUTER_MODEL` is the default; the watch may request any model listed in
  `OPENROUTER_ALLOWED_MODELS`. `OPENROUTER_REASONING_EFFORT` (default `high`) is sent as
  `{"reasoning": {"effort": ...}}`; set it empty to omit it for models that do not support
  reasoning.

The server tells the watch which models are allowed (`models` in the pairing and `/v1/me`
responses), so the model picker always matches the active provider.

History sent upstream is trimmed to the last `MAX_HISTORY_MESSAGES` /
`MAX_HISTORY_CHARS`.

### Web search

`WEB_SEARCH=true` (default) lets the model search the web when a question needs fresh facts
(news, weather, prices, scores). With the Claude API this is the `web_search` server tool, at
most `WEB_SEARCH_MAX_USES` searches per answer (default 3); it has to be enabled for your
organization in the Claude Console, and each search is billed on top of tokens. With
OpenRouter the `web` plugin is used. Set `WEB_SEARCH=false` to turn it off.

## Cost notes

* Every chat request sends the system prompt plus up to 12k characters of history;
  thinking models with a high effort can spend several times more output tokens than
  the visible answer. The default is the cheap Haiku with `medium` effort; web searches
  add a per-search fee and the retrieved pages count as input tokens.
* The per-token daily limit (`CHAT_PER_DAY`) is the safety net for a
  lost or stolen watch; set it to what you are willing to pay for in a day.

## Environment variables

See [`.env.example`](.env.example) for the full list with defaults and comments.
