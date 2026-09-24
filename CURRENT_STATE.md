# ANGI — Current System State

**Current HEAD**: `c246750d99951b54bd49a4aa90df6dea33c63d1e`  
**Last Updated**: 2026-09-23

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

2. **Debian Rootfs Extraction & Symlink Security (`SecureArchiveExtractor`)**:
   - Guest-absolute TAR symlinks (e.g. `/etc/alternatives/pager`) treated as paths inside rootfs and rewritten to relative links (`../../etc/alternatives/pager`) preventing Android host `/` leakage.
   - Lexical normalized containment checks without following already-created guest links (eliminated `canonicalFile` pitfalls).
   - Preserves TAR executable mode bits parsed from header mode field offset 100.
   - Supports USTAR prefix field offset 345 and GNU long pathname/linkname entries.
   - Safe hardlinks with relative fallbacks that never contain host absolute paths.
   - Debian `/etc/resolv.conf` replaced using NOFOLLOW deletion semantics (`DebianDistroSpec.writeResolvConf`) before writing regular DNS resolver file.
   - All 8 test scenarios verified in unit test suite.
   - [VERIFIED]

3. **Unified Linux Ownership & Single Storage Tree**:
   - `LinuxSandboxManager` is the single PRoot/Debian environment manager.
   - Single storage tree strictly at `filesDir/linux-sandbox/` (`rootfs/`, `workspace/`, `tmp/`).
   - Removed runtime usage and fallback routing of `DefaultLinuxEnvironmentManager` in `AngiApp`, `DiagnosticsViewModel`, and tool pipelines.
   - [VERIFIED]

4. **Filesystem Convergence**:
   - `linux_exec("echo hello > /workspace/test.txt")` and `linux_read_file("/workspace/test.txt")` address the identical physical file (`paths.workspaceDir`).
   - Tested and verified via regression tests.
   - [VERIFIED]

5. **Per-Conversation Shell State**:
   - Removed `"default_conversation"`.
   - Real `conversationId` propagated via `ToolExecutionContext` from `ConversationService` through `ToolExecutor` into `LinuxExecTool` and `sandboxManager.shellFor(conversationId)`.
   - Context is not exposed as a model-callable parameter.
   - [VERIFIED]

6. **Install Metadata & Checksum Integrity**:
   - SHA-256 computed over actual downloaded archive bytes via `MessageDigest` during streaming download.
   - Real checksum stored in metadata; `expectedSha256` explicitly set to `"NOT VERIFIED"` for rolling LXC images unless statically pinned.
   - Fake checksum strings eliminated.
   - [VERIFIED]

7. **Diagnostics Integration & Progress Verification**:
   - `DiagnosticsViewModel` bound directly to `LinuxSandboxManager` for lifecycle (download, install, delete, R/W self-test).
   - `DiagnosticsScreen` updated to render reactive install progress state, granular step badges, and status color mapping.
   - `LinuxInstallProgressTest` comprehensive Robolectric test suite (5 tests covering step sequence, progress event streaming, failure isolation, and ViewModel state transitions) all verified passing.
   - [VERIFIED]

8. **Local Test Suite & Build Compilation**:
   - All unit tests passing (`:app:testDebugUnitTest`), including `ModelImportAndLoadLifecycleTest`.
   - Gradle compilation successful (`compile_applet`).
   - Lint check passed (`:app:lintDebug`).
   - Debug assembly succeeded (`:app:assembleDebug`).
   - [VERIFIED]

---

## Unresolved Defects

- None.

---

## CI & Build State

- **Local Build (`compile_applet`)**: VERIFIED (Clean build)
- **Local Unit Tests (`testDebugUnitTest`)**: VERIFIED (All tests passing)
- **Lint Check (`lintDebug`)**: VERIFIED (Passed with 0 errors)
- **Debug Assembly (`assembleDebug`)**: VERIFIED (Passed with 0 errors)
- **Remote CI State**: VERIFIED READY

---

## Physical S23 State

- PRoot native binaries (`libproot.so`, `libtalloc.so.2`) aligned for ARM64-v8a.
- Model Load Acceptance Progression for Snapdragon 8 Gen 2:
  1. CPU: Must load and verify first.
  2. GPU: Test after CPU success.
  3. HYBRID: Test split layer acceleration.
  4. NPU: Test Qualcomm QNN / GenieX NPU path last.
- Status: Awaiting device physical execution run.

---

## Exact Next Task

Physical S23 Model Acceptance Run:
1. Import small GGUF (e.g. Qwen 0.6B) -> verifies import to AVAILABLE on CPU without auto-load.
2. Select CPU compute -> tap [Load Model] -> verify successful CPU load and state -> LOADED.
3. Select GPU compute -> tap [Load Model] -> verify GPU acceleration.
4. Select HYBRID compute -> tap [Load Model] -> verify hybrid runtime.
5. Select NPU compute -> tap [Load Model] -> verify Qualcomm NPU execution.
