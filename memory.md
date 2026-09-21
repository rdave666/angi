# ANGI Project Memory & Architecture Log

## Project Identity & Target Platform
- **Name**: ANGI (Android Next-Gen Intelligence)
- **Primary Goal**: Local-first on-device AI system with offline/on-device LLM inference and a fully autonomous, persistent Linux userspace runtime on Android.
- **Hardware Target**: Samsung Galaxy S23 Ultra / Qualcomm Snapdragon 8 Gen 2 (SM8550), Adreno 740 GPU, Hexagon NPU, ARM64-v8a architecture.
- **Stack**: Kotlin, Jetpack Compose (Material 3), Room Database, Qualcomm AI Engine Direct (QNN / Genie-X), PRoot (C/Linux userspace), Coroutines/Flow.

---

## Architectural Decisions & Core Components

### 1. Dual Execution Model
- **Qualcomm Snapdragon NPU/HTP Engine**:
  - Direct local inference via Qualcomm Genie-X (`GenieXInferenceEngine`).
  - HTP/DSP hardware acceleration via bundled `libQnnHtp.so`, `libQnnGenie.so`, and model weights.
  - Fallback/Server-side Gemini support for hybrid cloud-local capability.
- **Persistent Linux Userspace Sandbox (PRoot)**:
  - ARM64 PRoot binary integration (`libproot.so`, `libtalloc.so`, `libproot-loader.so`) bundled in `jniLibs/arm64-v8a/`.
  - Full guest userspace based on Debian 12 (Bookworm) ARM64 via LXC container rootfs.
  - Decompression handled by `SecureArchiveExtractor` (.tar.gz and .tar.xz using `org.tukaani:xz`) with strict traversal and link escape protection.
  - Linux execution tools (`linux_exec`, `linux_process_status`, `linux_process_output`, `linux_process_kill`) empower the model to run shell commands, install packages (`apt`), run Python/Node/Bash scripts, and execute background processes without confirmation friction.
  - State persistence across turns within conversations using ASCII Record/Unit Separator sentinels (`\036`, `\037`) for output demarcation and exit status tracking.

### 2. Filesystem & Security Boundaries
- **Storage Isolation**:
  - Sandbox rootfs located in private app storage: `filesDir/linux-sandbox/rootfs`.
  - Workspace directory: `filesDir/linux-sandbox/workspace` mounted inside guest Linux at `/workspace`.
  - PRoot flags `--link2symlink` and `-L` enabled to support Debian package management under Android's restricted kernel permissions (e.g. `protected_hardlinks`).
  - `force-unsafe-io` injected for `dpkg` to prevent phone I/O lag and permission errors.
- **Android Host Separation**:
  - Android host storage strictly protected through Android's Storage Access Framework (`AndroidSharedResourceRegistry`).
  - Guest Linux cannot escape into Android private files or internal app directories.

### 3. Tool Calling Ecosystem
- Model has access to:
  - **Linux System Tools**: `linux_exec`, `linux_process_status`, `linux_process_output`, `linux_process_kill`.
  - **Linux Filesystem Tools**: `linux_read_file`, `linux_write_file`, `linux_list_directory`.
  - **Android Host Tools**: `android_read_file`, `android_write_file`, `android_list_directory` (SAF URIs).
  - **Network & Utilities**: `web_fetch`, `open_url`, `share_text`.

---

## Evolution History & Changelog

| Milestone / Checkpoint | Date / Local Time | Key Additions / Decisions |
|---|---|---|
| **Checkpoint A** | 2026-09 | Initial Compose UI, Navigation, Room DB, Qualcomm Genie-X integration scaffolding. |
| **Checkpoint B** | 2026-09 | Alpine minirootfs download, verification, SHA-256 validation, virtual path resolver, SAF registry. |
| **PRoot Subsystem** | 2026-09-19 | Native PRoot ARM64 libraries extracted and verified. `LinuxPaths`, `ProotLauncher`, `PersistentSandboxShell`, `LinuxProcessManager`, `LinuxInstaller`, and `SecureArchiveExtractor` implemented. |
| **Debian 12 Ecosystem** | 2026-09-19 | Upgraded to Debian Bookworm LXC ARM64, integrated `org.tukaani:xz`, configured `dpkg` `force-unsafe-io`, DNS resolvers, and base package set (`bash`, `git`, `python3`, `node`). |
| **Model Shell Tools** | 2026-09-19 | Registered `linux_exec` and process control tools in `AngiApp`. Validated with unit tests (`ProotLauncherTest`, `SecureArchiveExtractorTest`) and successful `compile_applet`. |
| **Capability Policy & Sandbox Sync** | 2026-09-21 | Wired `linux_exec` and process control into `SettingsRepository`, `CapabilityPolicy`, and `SettingsScreen`. Unified filesystem tool path resolver with installed PRoot sandbox. Added regression tests. |

---

## Past Errors, Gotchas & Lessons Learned

1. **Android Kernel Link Restrictions (`protected_hardlinks`)**:
   - *Problem*: Android's SELinux and kernel settings block arbitrary hardlinks and UNIX domain sockets in app data directories (`muxserver_listen Permission denied`, dpkg link failures).
   - *Solution*: Pass `--link2symlink` and `-L` to PRoot. Prohibit SSH `ControlMaster` multiplexing.
2. **Interactive TTY Limitations**:
   - *Problem*: Fullscreen ncurses TUIs (`top`, `htop`, `vim`, `nano`) fail because PRoot runs non-interactively without an allocated pseudo-terminal (PTY). SSH password prompts fail on `/dev/tty`.
   - *Solution*: Direct models to batch/non-interactive equivalents (`top -bn1`, `ps aux`, file output redirection). For password SSH, use `sshpass`.
3. **Dynamic Library Linking on Android**:
   - *Problem*: `libtalloc.so` must be found by `libproot.so` at runtime.
   - *Solution*: Place binaries in `jniLibs/arm64-v8a/` so Android unpacks them to `nativeLibraryDir`. Copy `libtalloc.so` to `libtalloc.so.2` in `filesDir/linux-sandbox` and export `LD_LIBRARY_PATH`.
4. **Tar.xz vs Tar.gz**:
   - *Problem*: Debian LXC container images are distributed as `.tar.xz`, while Alpine uses `.tar.gz`. Android standard library only has `GZIPInputStream`.
   - *Solution*: Added `org.tukaani:xz:1.10` dependency and created unified `SecureArchiveExtractor` handling both formats.
5. **Session Output Demarcation**:
   - *Problem*: Parsing stdout/stderr from persistent shell sessions without getting trapped waiting for EOF.
   - *Solution*: Injected unique sentinel strings (`\036<nonce>\037<exit_code>\037<pid>\037<cwd>\036`) through stderr at the end of each command invocation.
6. **Capability Policy Synchronization**:
   - *Problem*: Adding tools to `ToolRegistry` without updating `CapabilityPolicy` (`SettingsRepository`) causes `ToolExecutor` to silently block tools with `TOOL_DISABLED`.
   - *Solution*: Always register new tools in `AngiSettings`, `isToolEnabled`, `canExecuteWithoutPrompt`, and the settings UI, with comprehensive unit test coverage.
7. **Filesystem Resolver Convergence**:
   - *Problem*: Filesystem tools pointing to a legacy environment path resolver read/write disconnected files from where `linux_exec` runs in the PRoot sandbox.
   - *Solution*: `AngiApp` dynamically routes filesystem tool path resolvers to `linuxSandboxManager.paths.root` when the PRoot sandbox is installed.
