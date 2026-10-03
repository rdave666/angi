# ANGI outbound phone bridge

The S23 initiates an authenticated HTTPS connection to a relay. A controller submits jobs and reads their results using the CLI or the included stdio MCP server. The phone needs no public IP, incoming port, ngrok process, or ADB connection.

```
Codex/controller --HTTPS--> relay <--HTTPS polling-- ANGI on S23
                                                    |-- local model API
                                                    |-- Debian shell (opt in)
                                                    `-- codex exec (opt in)
```

ANGI 0.2.1/build 13 bundles `bridge.py` in its APK and adds **Settings → Remote Phone Bridge**. It uses the existing PRoot Debian installation, not another Linux environment. Python 3 and CA certificates are already included in ANGI's Debian package list. The Linux/macOS relay and controller need Python 3.10+; no pip packages are needed.

## Choose the relay location

For regular use, run the relay on a persistent server with HTTPS and a persistent state directory. Caddy or another reverse proxy can terminate TLS and forward to `127.0.0.1:8765`. Keep the relay's own HTTP listener private.

If you already have an always-on computer, a **named Cloudflare Tunnel** to its local relay is another option. Cloudflare Tunnel runs beside the relay, not on the phone. A quick tunnel or `ngrok http 8765` is suitable for a temporary experiment; changing its URL requires updating the phone settings. No tunneling provider is required by the protocol. Do not add a browser-only login challenge in front of the machine API unless you also implement its service authentication.

This repository does not deploy a public relay or register a connector in this chat automatically. A reachable relay address and pairing are still required.

## Start the relay

On the relay computer, create two different random tokens using `python3 -c 'import secrets; print(secrets.token_urlsafe(32))'`. Keep them private. Set them in that computer's environment or a private service configuration:

- `ANGI_BRIDGE_CONTROLLER_TOKEN`: for Codex/controller access.
- `ANGI_BRIDGE_WORKER_TOKEN`: for the S23 worker only.

Tokens must each contain at least 32 characters. Never put actual tokens in committed files, public URLs, screenshots or chat. Persist the state directory across restarts; it contains queued instructions and returned output. Run:

```sh
python3 connectors/phone_bridge/bridge.py relay --state /private/path/angi-relay
```

The default listener is `127.0.0.1:8765`. Publish that listener through HTTPS using the chosen reverse proxy/tunnel. TLS verification remains enabled, and the bridge refuses redirects to avoid forwarding authentication to a different destination. One relay represents one phone; run separate relay instances/token pairs for additional devices.

## Connect ANGI

1. Install the APK and install Debian through Diagnostics if needed.
2. In Settings → Remote Phone Bridge, enter the relay's **HTTPS base URL** and **worker** token. Do not enter the controller token on the phone.
3. Leave remote commands disabled for status and local API access only. Enable **Allow remote Debian commands** when you want the controller to execute shell commands.
4. If Codex CLI is separately installed and authenticated inside ANGI's Debian environment, enable **Allow Codex tasks**. The bridge does not install Codex, transfer your login, or control a separate Termux Codex process.
5. Tap Connect. The screen distinguishes starting, connected, executing and reconnecting states. A foreground notification provides Disconnect. Tap Forget while disconnected to remove the stored pairing configuration.

For `api` jobs, also enable ANGI's existing OpenAI API server. Device-only mode is sufficient. The bridge forwards requests to that server over phone loopback, using the current API port/key captured when the bridge starts. Reconnect the bridge after changing the API port/key.

The phone's worker token is stored in private app preferences and excluded from cloud backups and device transfer. Connection is user-started and not silently restored after app termination/reboot. Android may suspend or kill the service; keep ANGI's notification enabled and account for your device's background/battery policy. Reconnecting resumes result delivery, not interrupted commands.

The default working directory is guest `/workspace`. A requested `cwd` must resolve inside that workspace, including symlink checks. **This check is not a shell security sandbox.** Enabled shell commands can access all files/processes available to ANGI's Debian installation and can modify its workspace/rootfs. They run through ANGI's existing PRoot/Android boundaries; the bridge is not arbitrary Android UI control or unrestricted device ADB. Use a trusted controller and revoke/rotate tokens if necessary.

## Control the phone from this session or another computer

Set `ANGI_BRIDGE_URL` to the public HTTPS relay base URL and set `ANGI_BRIDGE_CONTROLLER_TOKEN` securely on the controller. Run:

```sh
python3 connectors/phone_bridge/bridge.py client status
```

Write a JSON job to a local file, then submit it:

```json
{"action":"shell","payload":{"command":"uname -m; python3 --version","cwd":"."},"timeout":30}
```

```sh
python3 connectors/phone_bridge/bridge.py client submit --job /path/to/job.json
python3 connectors/phone_bridge/bridge.py client result --id JOB_ID
```

`submit` returns an ID immediately, even when the phone is offline. Poll `result` until its state is `completed`, `expired` or `unknown`. A completed job can contain a nonzero exit code, `timed_out: true`, or an `error`; completion is not a claim of successful execution.

Supported jobs:

| Action | Payload | Meaning |
|---|---|---|
| `status` | `{}` | Installed worker's platform/workspace/enabled capabilities |
| `api` | `{"method":"GET","path":"/health"}` | ANGI API health |
| `api` | `{"method":"GET","path":"/v1/models"}` | Current loaded model listing |
| `api` | `{"method":"POST","path":"/v1/chat/completions","body":{"model":"angi-loaded-model","messages":[{"role":"user","content":"Say hello"}],"stream":false}}` | Non-streaming phone inference |
| `shell` | `{"command":"...","cwd":"."}` | Noninteractive Debian command, explicitly enabled |
| `codex` | `{"prompt":"Inspect this project and report its test results","cwd":"my-project"}` | New `codex exec` task using the phone's local authentication |

Timeouts are 1–600 seconds. Shell/Codex text is limited to 16,000 characters; stdout/stderr are each retained up to 64 KiB. Codex runs with its `workspace-write` sandbox, without bypass flags. Its Android/PRoot compatibility and authentication must work independently; failures are reported rather than bypassing its sandbox. It normally expects `cwd` to be a Git checkout. This does not take over an already-running interactive Codex chat. The bridge currently buffers API JSON responses; SSE streaming, arbitrary API routes, screenshots, model load/unload controls and remote job cancellation are not implemented.

## Use as an MCP connector

On a Codex machine with this repository and Python available, set the two controller environment variables above before starting Codex, then register:

```sh
codex mcp add angi-phone -- python3 /absolute/path/to/angi/connectors/phone_bridge/bridge.py mcp
```

Alternatively configure the equivalent stdio command in another MCP client. Restart/reconnect that client so its new tools load. The server exposes `phone_status`, `phone_submit` and `phone_result`. This live chat can use the CLI when relay connectivity is supplied; adding a repository file alone cannot inject tools into an existing hosted ChatGPT/Codex session.

## Reliability and stopping

The phone polls every three seconds while idle and uses up to 30 seconds of reconnect backoff after failures. Relay jobs and phone result journals are durable. Queued jobs expire after ten minutes. Claimed jobs are never automatically requeued: if delivery or execution is interrupted, the result can be `unknown`. Check actual phone state before deliberately submitting a replacement command. Pending completed results are retried without executing the command again. Use one worker per phone/state directory.

Disconnect stops the worker service. Stopping a worker/app or losing the phone connection is not a guarantee that all externally spawned effects have been undone. Job subprocess groups are killed on normal timeout/worker termination; device-specific PRoot/service termination still needs S23 verification. A permanent server administrator must manage relay storage retention, HTTPS service supervision and token rotation. Disabling/removing the relay's worker token prevents future polling.

## Tests

```sh
python3 -m unittest discover -s connectors/phone_bridge -v
bash gradlew testDebugUnitTest lintDebug assembleDebug
```

Python integration tests run a real local relay and worker against temporary workspaces and a fake local inference API. Codex dispatch uses a fake CLI so tests do not spend API credits or claim real phone inference. Android tests cover configuration validation, private preference persistence/revocation and the bundled worker asset. Physical S23 pairing and Codex execution remain separate acceptance checks.
