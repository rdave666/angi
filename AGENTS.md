# ANGI Coding Agent Instructions

## Persistent Rules & Directives

1. **Memory & Context Tracking**:
   - Always consult `/memory.md` before making architectural decisions or introducing changes to system runtime, Qualcomm NPU integration, or Linux userspace PRoot layers.
   - When completing significant architectural milestones, bug fixes, or handling edge-case errors, update `/memory.md` with decisions made and lessons learned.

2. **Qualcomm Hardware & NPU Alignment**:
   - Hardware target is strictly Qualcomm Snapdragon 8 Gen 2 (SM8550) on Samsung Galaxy S23 Ultra (ARM64-v8a).
   - Use `libQnnHtp.so` and `GenieXInferenceEngine` for local hardware-accelerated LLM execution.

3. **Linux Userspace Sandbox (PRoot)**:
   - Always respect the `--link2symlink` and `-L` flags required to bypass Android's `protected_hardlinks` restrictions.
   - Do not attempt interactive TTY programs; use non-interactive variants (e.g. `top -bn1`).
   - Guest Linux filesystem must remain self-contained in `filesDir/linux-sandbox/rootfs` and `workspace` at `filesDir/linux-sandbox/workspace`.
