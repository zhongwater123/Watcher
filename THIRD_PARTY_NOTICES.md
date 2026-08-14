# Third-Party Notices

Watcher includes integrations, references, or design inspiration from third-party open source projects.

This file is a human-readable attribution document. It is not a substitute for reviewing each upstream project's license terms before redistribution.

## Paidax01 / math-curve-loaders

- Project: `math-curve-loaders`
- Upstream repository: <https://github.com/Paidax01/math-curve-loaders>
- License: No top-level license file was identified in the upstream repository during this notice update.
- Watcher usage: Watcher includes a `RoseFourLoader` Compose loading animation inspired by mathematical curve loaders.
- Local reference:
  - `app/src/main/java/com/example/watcher/ui/components/RoseFourLoader.kt`

Because no upstream license file was identified, treat this attribution conservatively. Before redistributing derived animation code as part of a published open-source release, verify the upstream author's intended license or replace the implementation with an independently authored animation.

## binwiederhier / ntfy

- Project: `ntfy`
- Upstream repository: <https://github.com/binwiederhier/ntfy>
- License: Apache License 2.0
- Watcher usage: Watcher uses ntfy-compatible publish / subscribe semantics for cross-device conversation relay and phone-agent handoff experiments.
- Local references:
  - `app/src/main/java/com/example/watcher/data/gateway/NtfyRelayClient.kt`
  - `app/src/main/java/com/example/watcher/data/gateway/GatewayModels.kt`
  - `app/src/main/java/com/example/watcher/ui/viewmodel/GatewayDelegate.kt`
  - `mcp/lib/ntfy-client.js`
  - `mcp/server.js`

Watcher is independent and is not affiliated with or endorsed by the ntfy maintainers.

When redistributing this project or material derived from ntfy, preserve the original license and attribution notices required by Apache License 2.0.

## google / adk-kotlin

- Project: `adk-kotlin`
- Upstream repository: <https://github.com/google/adk-kotlin>
- License: Apache License 2.0
- Watcher usage: Watcher uses Google ADK Kotlin for the local agent proof-of-concept, including ADK agent definitions, model bridging, generated tool validation, and in-memory runner flows.
- Local references:
  - `app/src/main/java/com/example/watcher/localagent/LocalAgentFactory.kt`
  - `app/src/main/java/com/example/watcher/localagent/adk/LocalAgentAdkModel.kt`
  - `app/src/main/java/com/example/watcher/localagent/adkprobe/`
  - `app/src/main/java/com/example/watcher/localagent/runtime/LocalAgentRuntime.kt`
  - `app/build.gradle.kts`

Watcher is independent and is not affiliated with or endorsed by Google or the ADK Kotlin maintainers.

When redistributing this project or material derived from ADK Kotlin, preserve the original license and attribution notices required by Apache License 2.0.

## google-ai-edge / LiteRT-LM

- Project: `LiteRT-LM`
- Upstream repository: <https://github.com/google-ai-edge/LiteRT-LM>
- License: Apache License 2.0
- Watcher usage: Watcher uses and references LiteRT-LM for on-device local model capabilities, including local model loading, runtime integration, local chat, image attachment experiments, and local agent brain integration.
- Local references:
  - `app/src/main/java/com/example/watcher/data/local/litert/`
  - `app/src/main/java/com/example/watcher/ui/screens/LiteRtScreen.kt`
  - `app/src/main/java/com/example/watcher/ui/viewmodel/LiteRtViewModel.kt`
  - `app/src/main/java/com/example/watcher/localagent/litert/LiteRtLocalAgentBrain.kt`
  - `app/build.gradle.kts`

Watcher is independent and is not affiliated with or endorsed by Google, Google AI Edge, or the LiteRT-LM maintainers.

When redistributing this project or material derived from LiteRT-LM, preserve the original license and attribution notices required by Apache License 2.0.

## ollama / ollama

- Project: `ollama`
- Upstream repository: <https://github.com/ollama/ollama>
- License: MIT License
- Watcher usage: Ollama is acknowledged as part of the local model ecosystem and as a reference point for local runtime / OpenAI-compatible model workflows. Watcher does not vendor Ollama source code in this repository.
- Local relationship:
  - OpenAI-compatible provider configuration is handled by `app/src/main/java/com/example/watcher/data/remote/OpenAiCompatibleProvider.kt`.
  - Runtime provider selection is handled by `app/src/main/java/com/example/watcher/data/repository/LlmWalletRepository.kt`.

Watcher is independent and is not affiliated with or endorsed by Ollama or the Ollama maintainers.

If future code directly vendors or derives from Ollama, preserve the original MIT license notice and update this section with exact copied or modified files.
