# ANGI — Current System State

**Current App Version**: `0.2.6` (build `18`)
**Current HEAD**: `main`  
**Last Updated**: 2026-10-07

---

## Verified Working Items

1. **Model Import & Load Path Isolation (`LocalModelRepository` & `GenieXInferenceEngine`)**:
   - `importModelFromUri()` copies, validates, and registers models into Room with `AVAILABLE` state without activating them.
   - Removed `setActiveModel()` from import flow and preset initialization.
   - Default imported GGUF preferred compute to `CPU` for initial verification and stability.
   - User-selectable compute units: `CPU`, `GPU`, `NPU`, `HYBRID` exposed in `ModelCard` via interactive chips prior to loading.
   - Completely removed `determineTotalModelLayers()` and all layer count heuristics.
   - Minimal `ModelConfig` passed to GenieX:
     - CPU: `nGpuLayers = 0`, `compute_unit = "cpu"`, `nCtx = model.contextLength`
     - GPU: `nGpuLayers = -1`, `compute_unit = "gpu"`, `nCtx = model.contextLength`
     - HYBRID: `nGpuLayers = -1`, `compute_unit = "hybrid"`, `nCtx = model.contextLength`
     - NPU: `nGpuLayers = -1`, `compute_unit = "npu"`, `nCtx = model.contextLength`
   - Explicit lifecycle state progression: `AVAILABLE` -> `LOADING` -> `LOADED` or `FAILED`.
   - Native load success (`LlmWrapper.build()`) is the only trigger that updates `activeModel` and marks state `LOADED`.
   - On load failure: state becomes `FAILED`, active model remains unchanged, model file is preserved on disk, and comprehensive error details are displayed.
   - Diagnostics logged before `LlmWrapper.build()`: model path, existence, byte size, GGUF magic, runtime ID, compute unit, nCtx, nGpuLayers, SoC / device ABI, and GenieX SDK initialization state.
   - [VERIFIED]

2. **Physical S23 Device Model Verification**:
   - Qwen 0.6B GGUF imported, loaded on CPU, and generated streaming tokens natively on physical Samsung Galaxy S23 Ultra (SM-S918B).
   - [VERIFIED]

3. **Debian Rootfs Extraction & Symlink Security (`SecureArchiveExtractor`)**:
   - Guest-absolute TAR symlinks (e.g. `/etc/alternatives/pager`) treated as paths inside rootfs and rewritten to relative links (`../../etc/alternatives/pager`) preventing Android host `/` leakage.
   - Lexical normalized containment checks without following already-created guest links (eliminated `canonicalFile` pitfalls).
   - Preserves TAR executable mode bits parsed from header mode field offset 100.
   - Supports USTAR prefix field offset 345 and GNU long pathname/linkname entries.
   - Safe hardlinks with relative fallbacks that never contain host absolute paths.
   - Debian `/etc/resolv.conf` replaced using NOFOLLOW deletion semantics (`DebianDistroSpec.writeResolvConf`) before writing regular DNS resolver file.
   - All 8 test scenarios verified in unit test suite.
   - [VERIFIED]

4. **Unified Linux Ownership & Single Storage Tree**:
   - `LinuxSandboxManager` is the single PRoot/Debian environment manager.
   - Single storage tree strictly at `filesDir/linux-sandbox/` (`rootfs/`, `workspace/`, `tmp/`).
   - Removed runtime usage and fallback routing of `DefaultLinuxEnvironmentManager` in `AngiApp`, `DiagnosticsViewModel`, and tool pipelines.
   - [VERIFIED]

5. **Filesystem Convergence**:
   - `linux_exec("echo hello > /workspace/test.txt")` and `linux_read_file("/workspace/test.txt")` address the identical physical file (`paths.workspaceDir`).
   - Tested and verified via regression tests.
   - [VERIFIED]

6. **Per-Conversation Shell State**:
   - Removed `"default_conversation"`.
   - Real `conversationId` propagated via `ToolExecutionContext` from `ConversationService` through `ToolExecutor` into `LinuxExecTool` and `sandboxManager.shellFor(conversationId)`.
   - Context is not exposed as a model-callable parameter.
   - [VERIFIED]

7. **Install Metadata & Checksum Integrity**:
   - SHA-256 computed over actual downloaded archive bytes via `MessageDigest` during streaming download.
   - Real checksum stored in metadata; `expectedSha256` explicitly set to `"NOT VERIFIED"` for rolling LXC images unless statically pinned.
   - Fake checksum strings eliminated.
   - [VERIFIED]

8. **Diagnostics Integration & Progress Verification**:
   - `DiagnosticsViewModel` bound directly to `LinuxSandboxManager` for lifecycle (download, install, delete, R/W self-test).
   - `DiagnosticsScreen` updated to render reactive install progress state, granular step badges, and status color mapping.
   - `LinuxInstallProgressTest` comprehensive Robolectric test suite (5 tests covering step sequence, progress event streaming, failure isolation, and ViewModel state transitions) all verified passing.
   - [VERIFIED]

9. **Versioned Builds & Strict CI Release Automation**:
   - Gradle `app/build.gradle.kts` single source of truth: `versionName = "0.2.6"`, `versionCode = 18`. No hard-coded fallbacks in CI.
   - Dynamic version display from `BuildConfig` in `SettingsScreen` and `DiagnosticsScreen` under App Version & Build.
   - Subtle monospace footer (`ANGI v0.2.6 (build 18)`) rendered across Settings and Diagnostics screens.
   - CI workflow (`.github/workflows/build-apk.yml`) extracts Gradle `versionName`/`versionCode` dynamically, creates `angi-v0.2.6-build18-debug.apk`, force-updates `dev-latest` tag to current `${GITHUB_SHA}`, and updates `ANGI Development Build` release asset on `dev-latest`.
   - [VERIFIED]

10. **Model Unload Control & State Consistency**:
    - `InferenceEngine.unloadModel()` closes native `LlmWrapper`, clears active LLM and model references, resets runtime state to READY, and preserves imported files on disk. Guarded against unload during active generation across runtime engines.
    - Replacement model load clears persisted active model ID prior to build attempt, ensuring failed loads leave active model state cleanly reset to `null` rather than referencing stale un-loaded models.
    - `ChatScreen` top bar displays explicit `Unload` button near compute badge when model is active, showing `No model loaded` and disabling text input/send after unload.
    - `ModelManagerScreen` displays `Unload Model` button on active model card (replacing `Load Model`).
    - Covered by `ModelUnloadLifecycleTest` Robolectric suite (8 unit tests verifying native cleanup, replacement state clearing, generation unload guard, YAML workflow rules, disk persistence, and chat input disablement).
    - [VERIFIED]

11. **OpenAI-Compatible Local HTTP API (`OpenAiApiService` & `OpenAiHttpServer`)**:
    - Embedded Android foreground service (`OpenAiApiService`) running `OpenAiHttpServer`.
    - Routes all requests directly through existing `InferenceEngine` (no separate model loading or second runtime).
    - Default bind: `127.0.0.1:8080`. Optional LAN mode binds `0.0.0.0` internally and requires Bearer token authentication.
    - **LAN Endpoint Display Fixes**:
      - Client endpoint never shows or copies `0.0.0.0`.
      - Detects local Wi-Fi/LAN IPv4 via `NetworkUtils.getLocalIpv4Address()` and renders `http://<PHONE_LAN_IP>:<PORT>/v1`.
      - If no LAN address is available, displays `"LAN address unavailable"` and disables copy.
      - Device-only mode displays `http://127.0.0.1:<PORT>/v1`.
    - **State Reconciliation Fixes**:
      - Persisted `isApiServerEnabled` starts service on app/process initialization (`AngiApp.onCreate()`).
      - Start failures truthfully update runtime state to stopped and reconcile persisted setting to `false`.
      - Switch in `SettingsScreen` represents actual running state.
    - **Disconnect Cancellation & Concurrency**:
      - Full disconnect cancellation implemented for both streaming (SSE) and non-streaming requests.
      - Parallel socket disconnect watcher detects TCP FIN/RST or connection close, cancels request job, invokes `inferenceEngine.cancel()`, and releases `inferenceLock`.
      - Inference lock reliably released; subsequent requests are never blocked with 429 after client disconnect.
    - **Exact Byte Content-Length Body Parsing**:
      - Reads exact `Content-Length` byte count from stream before UTF-8 decoding, cleanly supporting non-ASCII / multi-byte characters.
    - **Model Field Behavior**:
      - Accepts active model ID and stable alias `angi-loaded-model`.
      - Rejects invalid/unloaded model IDs with HTTP 400 (`invalid_request_error`).
      - `/v1/models` exposes the active model ID and `angi-loaded-model` alias (or empty array if no model is loaded).
    - **Automated Test Suite (`OpenAiApiServerTest`)**:
      - 14 tests covering `/health`, `/v1/models`, dynamic model change without restart, LAN 0.0.0.0 internal bind, client endpoint never exposing 0.0.0.0, LAN detected IP vs unavailable, non-streaming & streaming chat, Bearer auth, 503 no-model, 429 busy rate limit, streaming disconnect cancel, non-streaming disconnect cancel, inference lock release, UTF-8 byte Content-Length parsing, active model ID & alias acceptance, wrong model 400 rejection, and service restart reconciliation.
    - [VERIFIED IN UNIT TESTS]

12. **Local Test Suite & Build Compilation**:
    - All unit tests passing (`:app:testDebugUnitTest`), including `OpenAiApiServerTest` and `ModelUnloadLifecycleTest`.
    - Gradle compilation successful (`compile_applet`).
    - Lint check passed (`:app:lintDebug`).
    - [VERIFIED]

13. **Outbound Phone Bridge (v0.2.2 / build 14)**:
    - APK bundles the standard-library Python worker from `connectors/phone_bridge/bridge.py` through the Android generated-assets API.
    - Settings → Remote Phone Bridge stores relay HTTPS URL and a private worker token, with explicit shell/Codex opt-ins and Connect/Disconnect/Forget controls.
    - User-started foreground service runs the worker through the existing `LinuxSandboxManager`/PRoot Debian installation. No inbound phone port or second rootfs; no silent reboot/app auto-start.
    - Authenticated relay, CLI and stdio MCP tools queue status/API/shell/Codex jobs and retrieve results. Separate worker/controller tokens; redirect rejection, bounded output/timeouts, durable result delivery and no automatic replay of uncertain executions.
    - API forwarding supports health, model listing and non-streaming chat. Codex starts independent `codex exec` tasks using local authentication and its workspace-write sandbox; it must already be installed in ANGI's Debian environment.
    - Worker preferences and result state are excluded from Android backup/device transfer. Relay deployment, S23 pairing, Android service lifecycle and real on-device Codex execution remain NOT VERIFIED.
    - Python integration tests: 21 passed. Android unit tests, lint and debug APK build passed in the cloud. Bundled worker bytes match the tested source; physical device acceptance remains pending.
    - Setup: `connectors/phone_bridge/README.md`. HTTPS relay hosting is provider-independent; Cloudflare Tunnel/ngrok are optional alternatives.

14. **External OpenAI-Compatible Model Provider (v0.2.2 / build 14)**:
    - Settings adds Local / External inference source selection while preserving the loaded local GenieX runtime.
    - External provider accepts an OpenAI-compatible base URL and API key, normalizes optional `/v1`, fetches `GET /v1/models`, and persists selected model ID.
    - External chat uses `POST /v1/chat/completions` with SSE streaming and request cancellation.
    - Standard OpenAI function/tool calls are translated into the existing ANGI `ToolRegistry` / `ToolExecutor` loop.
    - Existing Debian/PRoot tools are reused; `linux_write_file` writes into the same `/workspace` storage tree used by local models and the phone bridge.
    - Tool result messages preserve the provider's `tool_call_id` across follow-up turns.
    - External API key is stored in a dedicated private preferences file excluded from cloud backup and device transfer.
    - URL normalization and `/v1/models` parsing tests added. Physical endpoint/tool-call acceptance remains pending.

15. **Stable Development APK Signing (v0.2.3 / build 15)**:
    - CI no longer creates a fresh random debug keystore on each ephemeral runner.
    - Development APKs use the fixed AOSP Android 14 test signing identity pinned to `android-14.0.0_r1`.
    - Source key/certificate downloads are SHA-256 pinned before conversion into the temporary CI keystore.
    - CI verifies the keystore certificate fingerprint before compilation and verifies the final APK signer after compilation.
    - Builds 15+ can update one another in place without uninstalling ANGI or deleting its app data.
    - One unavoidable transition remains: builds 14 and earlier used random CI signatures, so the first move to build 15 requires one final uninstall/reinstall.
    - The AOSP test key is development-only and must never be used for a production/Play release.

16. **In-App Debian Command Console (v0.2.4 / build 16)**:
    - New top-level Console tab uses the existing `LinuxSandboxManager` / persistent PRoot shell directly; no model, relay or VPS dependency.
    - Default console cwd is `/workspace`, shared with ANGI Linux tools and the phone bridge.
    - `PersistentSandboxShell.run()` now supports a live output observer for stdout/stderr while preserving existing buffered tool results.
    - Console renders streaming stdout/stderr, persistent cwd, completion/exit status and bounded scrollback.
    - While a process is running, the same input field sends stdin directly to that foreground process for interactive/device-auth workflows.
    - Stop sends the existing foreground cancellation path; Reset Shell destroys the console shell and returns the next session to `/workspace`.
    - Console layout uses IME padding so the command field remains accessible above the Android keyboard.
    - Interactive input is not mirrored into ANGI console scrollback.
    - Physical S23 interactive/login acceptance remains pending.

17. **Codex Local-Bin PATH Integration (v0.2.5 / build 17)**:
    - Debian/PRoot default PATH now includes `/root/.local/bin`.
    - Official Codex installs under `$HOME/.local/bin`, so new Console shells can invoke `codex` directly without a manual `export PATH=...`.
    - Phone bridge workers launched through the same PRoot environment inherit the same PATH, allowing `shutil.which("codex")` and Codex task execution to detect the installed CLI after reconnect.
    - Existing running shells/workers must be reset/restarted once to inherit the new environment.

18. **Pseudo-Terminal Console Mode (v0.2.6 / build 18)**:
    - Console defaults to TTY mode and launches normal commands through Debian `script -qefc ... /dev/null`, giving interactive CLIs a pseudo-terminal.
    - Fixes Codex `exec` waiting indefinitely on `Reading additional input from stdin...` when the Console's persistent shell is backed by Android pipes.
    - Stateful shell builtins such as `cd`, `export`, `unset`, aliases, `source`, and `umask` bypass the TTY wrapper so persistent shell state remains intact.
    - UI exposes a TTY mode ON/OFF control for commands that explicitly need pipe semantics.
    - New Debian installs include `util-linux` so the `script` PTY helper is available by default; existing installs receive a targeted install hint if it is missing.
    - Physical S23 Codex exec acceptance remains pending.

---

## Unresolved Defects

- None.

---

## CI & Build State

- **App Version**: `v0.2.6` (Build `18`)
- **Cloud Debug APK (`assembleDebug`)**: pending final v0.2.2 / build 14 workflow
- **Cloud Unit Tests (`testDebugUnitTest`)**: pending final v0.2.2 / build 14 workflow
- **Lint Check (`lintDebug`)**: pending final v0.2.2 / build 14 workflow
- **Remote CI State**: Workflow configured (`.github/workflows/build-apk.yml`); remote run outcome not independently verified.

---

## Physical S23 Acceptance State

- PRoot native binaries (`libproot.so`, `libtalloc.so.2`) aligned for ARM64-v8a.
- Qwen 0.6B CPU execution VERIFIED on Samsung Galaxy S23 Ultra (SM-S918B).
- Model Load Acceptance Progression for Snapdragon 8 Gen 2:
  1. CPU: VERIFIED (Qwen 0.6B loaded and generated tokens).
  2. GPU: Pending next device execution run.
  3. HYBRID: Pending next device execution run.
  4. NPU: Pending Qualcomm QNN / GenieX NPU execution run.
- OpenAI-Compatible API Physical S23 Acceptance (Marked NOT VERIFIED until manual S23 test):
  1. Real loaded GenieX model exposed through `/v1/models`: NOT VERIFIED
  2. Real `/v1/chat/completions`: NOT VERIFIED
  3. Real SSE token streaming: NOT VERIFIED
  4. Connection from another Android app using OpenAI `base_url`: NOT VERIFIED
  5. LAN connection from another device: NOT VERIFIED
  6. Cancellation of real GenieX generation on disconnect: NOT VERIFIED


