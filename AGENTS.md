# ANGI Coding Agent Instructions

## Session Lifecycle Directives

On every fresh session:
1. read PROJECT.md
2. read CURRENT_STATE.md
3. inspect current HEAD/code
4. code
5. update CURRENT_STATE.md before finishing

Source code overrides stale docs.
Do not append a history novel.

---

## Hardware & Runtime Constraints

- **Hardware Target**: Strictly Qualcomm Snapdragon 8 Gen 2 (SM8550) on Samsung Galaxy S23 Ultra (ARM64-v8a).
- **Inference Engine**: Use `libQnnHtp.so` and `GenieXInferenceEngine` for local hardware-accelerated LLM execution.
- **Linux Userspace Sandbox (PRoot)**:
  - Always respect `--link2symlink` and `-L` flags required to bypass Android's `protected_hardlinks` restrictions.
  - Do not attempt interactive TTY programs; use non-interactive variants (e.g. `top -bn1`).
  - Guest Linux filesystem must remain strictly self-contained in `filesDir/linux-sandbox/rootfs` and `workspace` at `filesDir/linux-sandbox/workspace`.
