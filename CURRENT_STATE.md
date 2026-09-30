# ANGI — Current System State

**Current App Version**: `0.2.0` (build `12`)  
**Current HEAD**: `main`  
**Last Updated**: 2026-09-26

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
   - Gradle `app/build.gradle.kts` single source of truth: `versionName = "0.2.0"`, `versionCode = 12`. No hard-coded fallbacks in CI.
   - Dynamic version display from `BuildConfig` in `SettingsScreen` and `DiagnosticsScreen` under App Version & Build.
   - Subtle monospace footer (`ANGI v0.2.0 (build 12)`) rendered across Settings and Diagnostics screens.
   - CI workflow (`.github/workflows/build-apk.yml`) extracts Gradle `versionName`/`versionCode` dynamically, creates `angi-v0.2.0-build12-debug.apk`, force-updates `dev-latest` tag to current `${GITHUB_SHA}`, and updates `ANGI Development Build` release asset on `dev-latest`.
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
    - Default bind: `127.0.0.1:8080`. Optional LAN mode (`0.0.0.0`) requiring Bearer token authentication.
    - Endpoints:
      - `GET /health` (returns status 200 with model loaded state and compute unit)
      - `GET /v1/models` (returns active loaded model ID, name, backend, compute unit)
      - `POST /v1/chat/completions` (JSON `stream=false` and SSE `stream=true` with `data: [DONE]`)
      - `POST /v1/completions` (Legacy prompt completions JSON and SSE)
    - Runtime rules:
      - No model loaded -> HTTP 503
      - Generation active / busy -> HTTP 429
      - Client disconnect -> immediate cancellation of active inference via `inferenceEngine.cancel()`
      - Unloading / loading a new model updates API dynamically with no server restart required.
    - UI Settings section:
      - Controls for Enable Server, Mode (Device Only / Local Network), Port, Endpoint, API Key, Copy Endpoint, Copy API Key, Server status, Loaded model, Compute unit.
    - Test Suite (`OpenAiApiServerTest`):
      - 10 unit tests covering `/health`, `/v1/models` (loaded & empty), dynamic model change without restart, non-streaming chat, SSE streaming, Bearer token auth, 503 no-model, 429 busy rate limit, disconnect cancellation.
    - [VERIFIED IN UNIT TESTS]

12. **Local Test Suite & Build Compilation**:
    - All unit tests passing (`:app:testDebugUnitTest`), including `OpenAiApiServerTest` and `ModelUnloadLifecycleTest`.
    - Gradle compilation successful (`compile_applet`).
    - Lint check passed (`:app:lintDebug`).
    - [VERIFIED]

---

## Unresolved Defects

- None.

---

## CI & Build State

- **App Version**: `v0.2.0` (Build `12`)
- **Local Build (`compile_applet`)**: VERIFIED (Clean build)
- **Local Unit Tests (`testDebugUnitTest`)**: VERIFIED (All tests passing)
- **Lint Check (`lintDebug`)**: VERIFIED (Passed with 0 errors)
- **Remote CI State**: VERIFIED READY (`.github/workflows/build-apk.yml`)

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


