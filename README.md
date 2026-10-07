[![Build Android APK](https://github.com/rdave666/angi/actions/workflows/build-apk.yml/badge.svg?event=push)](https://github.com/rdave666/angi/actions/workflows/build-apk.yml)

# ANGI

**Current version: 0.2.2 (build 14)**

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
