# Codex LB on this machine

Codex LB 1.24.0 runs as `codex-lb.service`, using its own Python 3.13 environment in
`/opt/codex-lb/venv`. It listens only on `127.0.0.1:2455`. The account imported from this machine's
Codex login, encrypted credentials, database, and model catalog live in `/var/lib/codex-lb`, owned by
the dedicated `codex-lb` service user. Back up that entire directory to retain the encryption key.

Both `codex-lb.service` and `fullskat.service` are enabled at boot. The FullSkat systemd drop-in at
`/etc/systemd/system/fullskat.service.d/codex-lb.conf` starts the gateway first and waits for its
health check. FullSkat continues using `/home/fullskat/server/data/state.json`: its passcode and
existing watch tokens are preserved.

## FullSkat

The private `server/.env` sets `OPENAI_BASE_URL=http://127.0.0.1:2455/v1` and a dedicated FullSkat
gateway API key. `OPENAI_ALLOWED_MODELS` lists the verified current family:

* `gpt-6.1-terra`
* `gpt-6-luna`
* `gpt-6.1-sol`
* `gpt-6-astra`

The existing Claude, OpenRouter and Kimi configuration and provider priority are preserved. Open
**Settings → Model** on the watch to refresh the server-provided list and select the OpenAI group
(the installed watch displays its name as “Openai”). Reopening the watch app also refreshes it.
No APK installation, data clearing, or re-pairing is needed. The wire format remains `/v1/me` and
`/v1/chat` with `delta`, `thinking`, `done` and `error` SSE events.

After editing the server environment:

```sh
systemctl restart fullskat
curl --retry 10 --retry-all-errors --retry-delay 1 -fsS https://skat.arbuz.lol/healthz
```

To restart both services:

```sh
systemctl restart codex-lb fullskat
systemctl is-active codex-lb fullskat
```

Do not delete the pairing state or Codex LB's data directory during an upgrade.

## Codex client

`/root/.codex/config.toml` selects provider `codex-lb`, retains `gpt-6.1-sol` and its `xhigh`
reasoning setting, and uses `http://127.0.0.1:2455/backend-api/codex`. The launcher at
`/root/.local/bin/codex` loads the dedicated CLI key from `/root/.config/codex-lb/client.env`
and executes the original standalone Codex binary. That key file is readable only by root.
The original ChatGPT login is retained, and the provider has `requires_openai_auth = true`.

New Codex processes read this configuration. An already running session retains the model connection
it opened at startup; exit and relaunch Codex to change that session's connection. Verify a fresh run:

```sh
codex exec --strict-config --sandbox read-only --ephemeral --skip-git-repo-check \
  -C /tmp 'Reply with exactly OK. Do not call any tools.'
```

The startup banner should report `provider: codex-lb`. Service logs should show the corresponding
gateway response request.

## Dashboard and credentials

The dashboard has a generated password stored privately at
`/root/.config/codex-lb/dashboard-password`. Proxy API-key authentication is enabled, using separate
keys for FullSkat and Codex CLI. Anonymous telemetry is disabled. No dashboard or gateway port is
published through Caddy.

For remote management, forward the local port over SSH:

```sh
ssh -L 2455:127.0.0.1:2455 root@<this-host>
```

Then visit `http://127.0.0.1:2455` locally and use the password stored on the server. Add or renew an
account through the dashboard if its upstream login is revoked. Automatic token refresh cannot keep
a revoked subscription or login working indefinitely.

The pre-change Codex configuration, launcher symlink target, server environment and pairing state are
backed up in `/root/fullskat-gateway-backup/`. This directory and the runtime credential files contain
secrets and must stay outside git.

## Verification and references

Run the server tests from their project directory:

```sh
cd /home/fullskat/server
.venv/bin/python -m pytest -q
```

`tests/test_openai.py` covers Responses streams, summaries and search switches, usage, HTTP and
stream errors, truncated streams, metadata-driven efforts, provider recovery, all four models, and
reusing an existing pairing token across a server restart without changing the stored state.

Installation verification on 2026-11-05: all 84 server tests passed; all four models returned live
answers through the complete app API with the deployed configuration and service user, using isolated
temporary pairing state. A web-search-enabled request also completed. A fresh Codex run reported
`provider: codex-lb` and answered successfully after the gateway restart. The public health check
returned `{"ok":true,"paired":true}` and the original pairing state remained byte-for-byte unchanged.

* [Codex LB source and installation](https://github.com/Soju06/codex-lb)
* [Codex LB client configuration](https://soju06.github.io/codex-lb/client-setup/)
* [Official OpenAI gateway configuration](https://learn.chatgpt.com/docs/enterprise/connect-to-a-gateway)
* [Official OpenAI Responses streaming](https://developers.openai.com/api/docs/guides/streaming-responses)
* [Official OpenAI model catalog](https://developers.openai.com/api/docs/models)
