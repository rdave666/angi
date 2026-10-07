[![Build Android APK](https://github.com/rdave666/angi/actions/workflows/build-apk.yml/badge.svg?event=push)](https://github.com/rdave666/angi/actions/workflows/build-apk.yml)

# ANGI

**Current version: 0.2.6 (build 18)**

ANGI is an Android AI assistant built around local Qualcomm GenieX inference, an isolated Debian/PRoot workspace, tool execution, an OpenAI-compatible local API, and an outbound phone bridge.

## Local + external models

ANGI can now keep the local GenieX model available while optionally routing chat to an external OpenAI-compatible provider.

In **Settings → Model Source**:

- choose **Local** to use the loaded on-device GenieX model;
- choose **External** to use an OpenAI-compatible endpoint;
- enter the provider base URL (with or without `/v1`) and API key;
- tap **Load models** to fetch `GET /v1/models`;
- select a model from the returned list.

External chat uses `POST /v1/chat/completions` with streaming support. When the provider supports OpenAI function/tool calling, ANGI exposes its enabled tool registry to that model. This includes the existing Debian tools such as `linux_read_file`, `linux_write_file`, `linux_list_directory`, and `linux_exec`. File writes resolve into the same Debian/PRoot `/workspace` used by local models and the phone bridge.

The external API key is stored separately from general settings and excluded from Android backup/device transfer.

## Phone bridge

ANGI includes an outbound phone bridge for remote diagnostics, Debian commands and optional Codex tasks. Configure it in **Settings → Remote Phone Bridge**.

[Relay, pairing and MCP setup](connectors/phone_bridge/README.md).


## Development APK upgrades

Starting with **0.2.3 / build 15**, CI uses one fixed development signing identity instead of generating a new random debug key on every GitHub Actions runner. Builds 15+ therefore install as normal Android updates over one another and preserve ANGI app data, model metadata, settings and the Debian/PRoot installation.

**One-time transition:** builds 14 and earlier were signed with ephemeral CI keys. Android cannot update an installed APK to a differently signed APK, so moving from any pre-15 build to build 15 requires one final uninstall/reinstall. After build 15 is installed, later development APKs should update in place.

The fixed signing identity is the public AOSP test key and is for development builds only. It must never be used for a production/Play release.


## In-app Debian console

ANGI 0.2.4 / build 16 adds a **Console** tab that talks directly to the existing Debian/PRoot shell. It does not require a model, the phone bridge, or the VPS.

The console:
- starts in the shared Debian `/workspace`;
- runs normal shell commands through the existing persistent PRoot shell;
- streams stdout and stderr into the app while the command is running;
- keeps shell state such as current directory and exported environment variables between commands;
- supports interactive stdin while a process is running;
- provides Stop, Clear Output, and Reset Shell controls;
- keeps the command field above the Android keyboard with IME-aware layout.

This is intended for workflows such as CLI/device authentication where a browser login URL or code must be visible directly on the phone. Example: `codex login --device-auth` after Codex is installed inside ANGI's Debian environment.

Interactive input sent to a running process is not duplicated into ANGI's console output, reducing accidental on-screen exposure of passwords or tokens.


## Codex PATH integration

ANGI 0.2.5 / build 17 adds `/root/.local/bin` to the default Debian/PRoot `PATH`. The official Codex installer places the CLI there, so new ANGI console sessions and the phone bridge can find `codex` directly without manually exporting PATH each time.

After installing Codex, use **Reset Shell** in the Console (or restart the phone bridge) so the new process environment is picked up.


## Pseudo-terminal console mode

ANGI 0.2.6 / build 18 adds a real **TTY mode** to the in-app Debian Console. When enabled (the default), commands are launched through Debian's `script` pseudo-terminal helper so interactive CLIs see a terminal instead of an open pipe.

This fixes tools such as Codex hanging on messages like `Reading additional input from stdin...`. Shell-state commands such as `cd` and `export` still execute directly in the persistent shell so working directory and environment changes survive between commands.

Fresh Debian installs include `util-linux` (which provides `script`). Existing installations that do not already have it can run `apt update && apt install -y util-linux` once.
