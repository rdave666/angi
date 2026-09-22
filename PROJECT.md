# ANGI — Project Architecture & Durable Directives

## Mission & Architecture Overview

ANGI is an autonomous, on-device AI engineering agent running natively on Android, designed for hardware-accelerated local LLM inference and a fully isolated userland Linux environment.

### Target Hardware Platform
- **Target Device**: Samsung Galaxy S23 Ultra (SM-S918B/DS).
- **SoC**: Qualcomm Snapdragon 8 Gen 2 for Galaxy (SM8550-AC).
- **CPU Architecture**: 64-bit ARMv8.5-A / ARMv9 (ARM64-v8a).
- **Accelerators**: Qualcomm Hexagon NPU via QNN / QAIRT (`libQnnHtp.so`), Adreno 740 GPU.

---

## Core Subsystems

### 1. GenieX Local Inference Engine
- **Hardware Acceleration**: Executes LLM token generation directly on Qualcomm Hexagon NPU (`libQnnHtp.so`) with fallback to GPU/CPU via llama.cpp.
- **Truthful Model Lifecycle**: No fake fallback tokens or simulated generations. Inference engine strictly fails truthfully if no model is loaded.
- **Context & Generation**: Model-agnostic prompt templates (ChatML/Qwen, Llama 3, Phi-3.5) with streaming tokens and structured tool calling.

### 2. PRoot Debian ARM64 User Space (Kai Reference)
- **Engine**: PRoot binary (`libproot.so`) dynamically loaded from Android `nativeLibraryDir` with bundled `libtalloc.so.2`.
- **Rootfs**: Debian 12 (Bookworm) ARM64 minimal container distribution.
- **Filesystem Isolation & Storage Layout**:
  Single canonical storage tree under `filesDir/linux-sandbox/`:
  - `rootfs/`: Guest Debian root filesystem (read-only enforcement for system stability outside apt).
  - `workspace/`: Guest `/workspace` bound directly to host `filesDir/linux-sandbox/workspace`.
  - `tmp/`: Guest `/tmp` bound to isolated sandbox temp storage.
- **Android Kernel Restrictions**:
  Mandatory PRoot flags: `--link2symlink` and `-L` to bypass Android kernel `protected_hardlinks` and Unix domain socket limitations.
- **dpkg/apt Tuning**: `force-unsafe-io` enabled in `etc/dpkg/dpkg.cfg.d/` to prevent fsync disk thrashing on mobile flash storage.

### 3. Unified Linux Ownership & Tools
- **Single Manager**: `LinuxSandboxManager` is the single source of truth for PRoot/Debian environment lifecycle (download, install, delete, self-test, path resolution, process tracking).
- **Filesystem Convergence**: Host tools (`linux_read_file`, `linux_write_file`, `linux_list_directory`) and guest shell execution (`linux_exec`) resolve identically against `paths.workspaceDir`.
  Writing to `/workspace/file.txt` in `linux_exec` and reading `/workspace/file.txt` with `linux_read_file` access the exact same physical inode.
- **Per-Conversation Shell State**:
  Persistent interactive bash shells are keyed by real `conversationId` via `ToolExecutionContext`. State (current directory, env vars, functions) persists across conversation turns without polluting other sessions. `fresh=true` spawns isolated one-shot executions.
- **Non-Interactive Execution**:
  Android cannot allocate a true pseudo-terminal (PTY) without root/auxiliary daemons. All executions run non-interactively (`top -bn1`, `ps aux`, batch tools).

### 4. Storage Access Framework (SAF) Boundary
- **Scoped Android Storage**: Direct Android host filesystem paths outside the sandbox (`/data`, `/sdcard`, `/storage`) are strictly blocked.
- **User-Granted Boundaries**: User-selected files and directories from external storage are accessed exclusively via Android SAF URIs registered in `AndroidSharedResourceRegistry`.

---

## Permanent Architectural Decisions

1. **No Mixed/Fallback Linux Runtimes**: Only one Linux sandbox exists (`LinuxSandboxManager` on `filesDir/linux-sandbox`). No legacy minirootfs fallbacks or dual directory trees.
2. **Real Conversation ID Propagation**: `conversationId` flows from `ConversationService` → `ToolExecutor` → `ToolExecutionContext` → `LinuxExecTool` → `sandboxManager.shellFor(conversationId)`. It is never exposed as an LLM argument.
3. **Truthful Verification**: SHA-256 is computed against real downloaded archive bytes. No placeholder or fake verification strings (`""` or `"verified"`). `INSTALLED` status requires successful disk extraction and valid marker file.
