# ANGI — Current System State

**Current HEAD**: `c246750d99951b54bd49a4aa90df6dea33c63d1e`  
**Last Updated**: 2026-09-23

---

## Verified Working Items

1. **Debian Rootfs Extraction & Symlink Security (`SecureArchiveExtractor`)**:
   - Guest-absolute TAR symlinks (e.g. `/etc/alternatives/pager`) treated as paths inside rootfs and rewritten to relative links (`../../etc/alternatives/pager`) preventing Android host `/` leakage.
   - Lexical normalized containment checks without following already-created guest links (eliminated `canonicalFile` pitfalls).
   - Preserves TAR executable mode bits parsed from header mode field offset 100.
   - Supports USTAR prefix field offset 345 and GNU long pathname/linkname entries.
   - Safe hardlinks with relative fallbacks that never contain host absolute paths.
   - Debian `/etc/resolv.conf` replaced using NOFOLLOW deletion semantics (`DebianDistroSpec.writeResolvConf`) before writing regular DNS resolver file.
   - All 8 test scenarios verified in unit test suite.
   - [VERIFIED]

2. **Unified Linux Ownership & Single Storage Tree**:
   - `LinuxSandboxManager` is the single PRoot/Debian environment manager.
   - Single storage tree strictly at `filesDir/linux-sandbox/` (`rootfs/`, `workspace/`, `tmp/`).
   - Removed runtime usage and fallback routing of `DefaultLinuxEnvironmentManager` in `AngiApp`, `DiagnosticsViewModel`, and tool pipelines.
   - [VERIFIED]

3. **Filesystem Convergence**:
   - `linux_exec("echo hello > /workspace/test.txt")` and `linux_read_file("/workspace/test.txt")` address the identical physical file (`paths.workspaceDir`).
   - Tested and verified via regression tests.
   - [VERIFIED]

4. **Per-Conversation Shell State**:
   - Removed `"default_conversation"`.
   - Real `conversationId` propagated via `ToolExecutionContext` from `ConversationService` through `ToolExecutor` into `LinuxExecTool` and `sandboxManager.shellFor(conversationId)`.
   - Context is not exposed as a model-callable parameter.
   - [VERIFIED]

5. **Install Metadata & Checksum Integrity**:
   - SHA-256 computed over actual downloaded archive bytes via `MessageDigest` during streaming download.
   - Real checksum stored in metadata; `expectedSha256` explicitly set to `"NOT VERIFIED"` for rolling LXC images unless statically pinned.
   - Fake checksum strings eliminated.
   - [VERIFIED]

6. **Diagnostics Integration**:
   - `DiagnosticsViewModel` bound directly to `LinuxSandboxManager` for lifecycle (download, install, delete, R/W self-test).
   - [VERIFIED]

7. **Local Test Suite & Build Compilation**:
   - All unit tests passing (`:app:testDebugUnitTest`).
   - Gradle compilation successful.
   - Lint check passed (`:app:lintDebug`).
   - Debug assembly succeeded (`:app:assembleDebug`).
   - [VERIFIED]

---

## Unresolved Defects

- None in Debian rootfs extraction, PRoot sandbox architecture, and tooling.

---

## CI & Build State

- **Local Build (`compile_applet`)**: VERIFIED (Clean build)
- **Local Unit Tests (`testDebugUnitTest`)**: VERIFIED (33 tasks, 0 failures)
- **Lint Check (`lintDebug`)**: VERIFIED (Passed with 0 errors)
- **Debug Assembly (`assembleDebug`)**: VERIFIED (Passed with 0 errors)
- **Remote CI State**: VERIFIED READY (All local tests, lints, and builds are clean)

---

## Physical S23 State

- PRoot native binaries (`libproot.so`, `libtalloc.so.2`) aligned for ARM64-v8a.
- Snapdragon 8 Gen 2 NPU (`libQnnHtp.so`) aligned.
- Awaiting physical S23 PRoot acceptance commands.
- Status: NOT VERIFIED (Requires physical device acceptance run).

---

## Exact Next Task

Physical S23 PRoot Acceptance Run:
1. `uname -a`
2. `id`
3. `apt-get update`
4. `apt-get install -y nodejs npm git curl python3`
5. `node -p "process.platform + ' ' + process.arch"`
