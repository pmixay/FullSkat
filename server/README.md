# FullSkat server

The server half of FullSkat, a Wear OS voice assistant. The watch recognizes speech
on-device, so the server never receives audio; it holds the API keys and relays chat
completions to the Claude API, OpenRouter, the Kimi Code platform and OpenAI Responses gateways such as
Codex LB (any of them, at the same time). It stores no dialogs, only a passcode hash and the tokens it has issued.
The wire contract is in [`docs/PROTOCOL.md`](../docs/PROTOCOL.md).

## Quick start with Docker

```sh
cd server
cp .env.example .env          # set ANTHROPIC_API_KEY, OPENROUTER_API_KEY and/or KIMI_API_KEY
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

There is no provider switch. Every provider whose API key is set is a candidate; the server probes
each one (a cheap authenticated request: Claude `models.list`, OpenRouter `/key`, Kimi `/models`) and
offers its models to the watch only while the probe passes. A failed probe is retried after 30 s, a
passed one is trusted for 5 min, and a rejected key during a chat disables the provider at once. If a
key is revoked or a provider goes down its models disappear from the watch's picker by themselves, and
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
  dedicated gateway key. The default allowlist contains `gpt-6.1-terra`, `gpt-6-luna`, `gpt-6.1-sol`
  and `gpt-6-astra`. An authenticated `/models` probe controls availability; Codex LB's model metadata
  supplies supported reasoning levels, or set `OPENAI_MODEL_EFFORTS` for a gateway without metadata.
  Responses text, refusal text, reasoning summaries and token usage are translated to the existing
  watch SSE events. Failed, incomplete or disconnected responses produce an error, never a successful
  truncated answer. `WEB_SEARCH` controls the built-in `web_search` tool; this backend does not accept
  `max_tool_calls`, so `WEB_SEARCH_MAX_USES` does not cap OpenAI searches. `SHOW_THINKING=false` omits
  summaries and suppresses them even if a gateway sends one. The usage ring uses the tightest
  unfiltered quota in Codex LB's `/v1/usage`; gateways without it have no ring.

The server tells the watch the models of the working providers, grouped by provider (`providers`),
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

An unknown model, such as a preview, is therefore not adjustable until you list it. The watch shows
only the levels the selected model supports; a requested level the model lacks is moved to the nearest
supported one, and a model that is not adjustable ignores it. The chosen level is sent as
`reasoning_effort`; Kimi Code does not document the parameter name, so none is sent unless the watch
picks a level. Answers are cached for an hour.

### Usage ring

Each provider reports the share of its budget left, shown as a ring beside its name in the watch's
model picker: OpenRouter credits (the key's spending limit, else account credits), Claude's token
rate limit (from response headers; Anthropic has no balance endpoint), Kimi Code's membership quota.
Unknown means no ring.

### Errors

Failures reach the watch as `error` codes: `upstream_rate_limited` (provider pause, with
`retry_after`), `upstream_quota` (credits or quota spent), `upstream_auth` (the provider rejected the
server's key), `upstream_error`, plus `rate_limited`, `daily_limit` (`CHAT_PER_DAY`, HTTP 429),
`no_provider` / `provider_unavailable` (HTTP 503) and `model_not_allowed` (HTTP 400). The watch shows
the usage-limit ones (`rate_limited`, `daily_limit`, `upstream_quota`, `upstream_rate_limited`) as a
separate block with an icon and the wait time.

History sent upstream is trimmed to the last `MAX_HISTORY_MESSAGES` /
`MAX_HISTORY_CHARS`.

### Thinking

`SHOW_THINKING=true` (default) streams the model's reasoning summary to the watch as
`thinking` events (see `docs/PROTOCOL.md`); the watch shows it collapsed under the answer. With
the Claude API this sends `thinking: {"type": "adaptive", "display": "summarized"}`; with
OpenRouter and Kimi the reasoning deltas are forwarded when the model returns them. Set
`SHOW_THINKING=false` to omit the `thinking` request field and send no summaries.

### Web search

`WEB_SEARCH=true` (default) lets the model search the web when a question needs fresh facts
(news, weather, prices, scores). With the Claude API this is the `web_search` server tool, at
most `WEB_SEARCH_MAX_USES` searches per answer (default 3); it has to be enabled for your
organization in the Claude Console, and each search is billed on top of tokens. With
OpenRouter the `web` plugin is used; with Kimi its `$web_search` tool. Set `WEB_SEARCH=false` to turn it off.

`WEB_SEARCH` is a master switch: the watch can send `"web_search": false` to turn search off for
a request, but can never turn it on when the operator disabled it. Likewise the watch may send
`"effort"` per request to override the model's default (see Efforts above).
The defaults are reported to the watch as `effort` and `web_search` in the pairing and `/v1/me`
responses.

## Cost notes

* Every chat request sends the system prompt plus up to 12k characters of history;
  thinking models with a high effort can spend several times more output tokens than
  the visible answer. The default is the cheap Haiku with `medium` effort; web searches
  add a per-search fee and the retrieved pages count as input tokens.
* The per-token daily limit (`CHAT_PER_DAY`) is the safety net for a
  lost or stolen watch; set it to what you are willing to pay for in a day.

## Environment variables

See [`.env.example`](.env.example) for the full list with defaults and comments.

## Local Codex LB deployment

See [the deployment notes](../docs/CODEX_GATEWAY.md) for service locations, verification and restart commands.
The installed watch already loads providers, models and efforts from `/v1/me` when the app or its Model
settings opens. Adding this provider needs only a server restart: keep `DATA_DIR/state.json` and the
watch's token intact. No watch reinstall or re-pairing is needed.
