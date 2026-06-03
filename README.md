# Watcher

Watcher is an Android app for long-running AI-assisted video observation.

It combines real-time monitoring, segmented video analysis, live commentary, audience interaction, multi-expert review, local model experiments, and long-term behavior modeling around the same video stream.

## Project Status

Watcher is an actively evolving product prototype, not a finished SDK or a polished end-user release.

What exists today:

- A single-module Android app built with Kotlin and Jetpack Compose
- Real-time monitoring workflows over MJPEG streams such as `ESP32-CAM`
- AI-planned video analysis with segmented recording and summarization
- Landscape live modes for commentary, audience interaction, and council-style expert analysis
- Local persistence for history, templates, providers, audience state, knowledge, and behavior modeling
- An embedded LAN gateway API for local automation and external control
- An integrated agent framework and a separate LiteRT local-model entry point

What to expect:

- Rapid iteration
- Incomplete UX polish
- Configuration surfaces that are still moving
- Internal research features that are usable but not yet stable

## What Watcher Does

Watcher treats a video stream as a long-lived working context instead of a one-shot inference input.

Core workflows:

- Real-time monitoring
  Convert a natural-language monitoring request into a structured task, poll the stream on an interval, detect changes or target presence, and persist alerts, screenshots, and session media.
- Video analysis
  Turn a longer request into a plan, record multiple segments, analyze each segment, and generate a final summary with timeline events.
- Live interaction
  In landscape mode, run commentary, speech-triggered interaction, and AI audience behavior on top of the same stream.
- Council mode
  Run multiple expert roles against the same shared context and collect a synthesized result.
- Digital life card
  Accumulate observations into blackboard-style records, scene profiles, behavior claims, reasoning logs, and portrait dimensions.
- Local model experiments
  Manage LiteRT-backed on-device model assets and initialize local inference backends separately from remote providers.

## Architecture Summary

The app is a single Gradle module, but the codebase is organized as a multi-workbench system.

Main runtime layers:

- `ui/screens` and `ui/viewmodel`
  Compose workbenches, immersive modes, and orchestration state
- `data/repository`
  Monitoring, video processing, commentary, audience, council, history, templates, and behavior workflows
- `data/local`
  Room database, persistent stores, migrations, and LiteRT configuration
- `data/remote`
  Retrofit services, streaming clients, provider adapters, and device-facing HTTP APIs
- `data/gateway`
  Embedded NanoHTTPD-based LAN API
- `data/agent` and `agentframework`
  Agent abstractions, tools, runtimes, persistence, and autonomous execution services

For a code-level walkthrough, see [docs/architecture-overview.md](docs/architecture-overview.md) and [docs/agent-framework.md](docs/agent-framework.md).

## Main App Areas

The primary app surface is the `MainScreen` workbench, which currently includes:

- `Monitor`
- `Hub`
- `Analysis`
- `History`
- `Templates`

Additional entry points:

- `API Wallet`
- `Agent Config`
- `Digital Life Card`
- `LiteRt`

Landscape-specific immersive modes:

- `Live`
- `Council`

## Requirements

- Android Studio with current Android SDK tooling
- JDK 11
- Android SDK 35
- Android device or emulator on Android 10+ (`minSdk = 29`)

Build targets from code:

- `compileSdk = 35`
- `targetSdk = 35`
- `minSdk = 29`

## Quick Start

### 1. Clone and open

Open the repository in Android Studio and let Gradle sync the project.

### 2. Prepare `local.properties`

`local.properties` is used for development-time configuration. At minimum, Android Studio will usually manage `sdk.dir` for you. Optional app credentials can also live here.

Example:

```properties
API_KEY=your_remote_model_api_key
VOLCENGINE_ASR_APP_KEY=your_volcengine_asr_app_key
VOLCENGINE_ASR_ACCESS_KEY=your_volcengine_asr_access_key
VOLCENGINE_ASR_RESOURCE_ID=volc.seedasr.sauc.duration
```

Optional release signing values:

```properties
RELEASE_STORE_FILE=keystore.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_alias
RELEASE_KEY_PASSWORD=your_key_password
```

Notes:

- `API_KEY` is only injected into the `debug` build through `BuildConfig`.
- Runtime model providers can also be configured inside the app through `API Wallet`.
- Do not commit `local.properties`.

### 3. Build and install

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

Useful verification commands:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat lintDebug
```

### 4. First-run path

Suggested first-run flow:

1. Open the app and configure a stream URL in the camera settings dialog.
2. Confirm that live frames are visible.
3. Try `Monitor` first to understand the task-to-alert loop.
4. Try `Analysis` next to see segmented recording and summarization.
5. Rotate to landscape and enter `Live` or `Council`.
6. Inspect `History` to verify media, events, and summaries are persisted.
7. Explore `API Wallet`, `Agent Config`, `Digital Life Card`, and `LiteRt` after the main video flow is working.

## Configuration Model

### Video stream and devices

Watcher is primarily designed around MJPEG-style device streams and currently includes:

- Stream URL settings
- LAN device scanning
- Device info refresh
- Provisioning helpers for supported devices
- LED-related controls for compatible cameras

### Model providers

Watcher now uses a runtime provider wallet instead of assuming one hardcoded backend.

Provider behavior:

- Providers are stored in Room with secrets moved into encrypted local storage
- A default provider can be selected in the app
- Some features can fall back to the Ark-compatible `API_KEY` path when no provider is selected
- Provider connectivity state is cached locally for UI feedback

### Local model support

LiteRT support is integrated as a separate entry point:

- bundled asset installation
- config persistence
- model path resolution
- optional startup auto-initialization
- local model downloads and engine reload flows

## Project Structure

```text
app/
  src/main/java/com/example/watcher/
    agentframework/         Standalone agent runtime and persistence services
    data/agent/             App-facing agent abstractions and helpers
    data/gateway/           Embedded gateway API
    data/local/             Room, app storage, LiteRT local stores
    data/model/             Entities and domain models
    data/remote/            Retrofit services and provider adapters
    data/repository/        Core workflows and orchestration logic
    ui/components/          Reusable Compose building blocks
    ui/screens/             Workbench pages and immersive surfaces
    ui/theme/               Compose theme
    ui/viewmodel/           View models and workflow delegates
  src/main/res/             Android resources
  src/test/                 JVM unit tests
  src/androidTest/          Instrumentation and Compose UI tests
docs/                       Architecture notes, iterations, and technical references
mcp/                        Zero-build Watcher MCP server for generic gateway tools
tools/                      Helper scripts
```

## Key Entry Points

Recommended reading order:

1. [app/src/main/java/com/example/watcher/ui/screens/MainScreen.kt](app/src/main/java/com/example/watcher/ui/screens/MainScreen.kt)
2. [app/src/main/java/com/example/watcher/ui/viewmodel/IntentViewModel.kt](app/src/main/java/com/example/watcher/ui/viewmodel/IntentViewModel.kt)
3. [app/src/main/java/com/example/watcher/data/repository/MonitorManager.kt](app/src/main/java/com/example/watcher/data/repository/MonitorManager.kt)
4. [app/src/main/java/com/example/watcher/data/repository/VideoProcessRepository.kt](app/src/main/java/com/example/watcher/data/repository/VideoProcessRepository.kt)
5. [app/src/main/java/com/example/watcher/ui/viewmodel/LiveInteractionController.kt](app/src/main/java/com/example/watcher/ui/viewmodel/LiveInteractionController.kt)
6. [app/src/main/java/com/example/watcher/WatcherApplication.kt](app/src/main/java/com/example/watcher/WatcherApplication.kt)
7. [app/src/main/java/com/example/watcher/data/gateway/GatewayServer.kt](app/src/main/java/com/example/watcher/data/gateway/GatewayServer.kt)
8. [docs/architecture-overview.md](docs/architecture-overview.md)
9. [docs/agent-framework.md](docs/agent-framework.md)

## Documentation Map

- [CONTRIBUTING.md](CONTRIBUTING.md)
- [AGENTS.md](AGENTS.md)
- [CLAUDE.md](CLAUDE.md)
- [docs/architecture-overview.md](docs/architecture-overview.md)
- [docs/agent-framework.md](docs/agent-framework.md)
- [docs/2026-04-07-database-field-summary.md](docs/2026-04-07-database-field-summary.md)
- [docs/2026-03-26-product-iteration.md](docs/2026-03-26-product-iteration.md)
- [docs/2026-03-27-product-iteration.md](docs/2026-03-27-product-iteration.md)
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

The dated docs under `docs/` are historical design and iteration records, not the canonical source of truth for current behavior.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for setup, testing expectations, coding conventions, and pull request guidance.

## Security Notes

- `local.properties` may contain development secrets and must stay uncommitted.
- `network_security_config.xml` allows cleartext traffic for local-device scenarios; review any new exceptions carefully.
- The embedded gateway is designed for trusted LAN use, not public internet exposure.
- Historical media, screenshots, and logs may contain sensitive local scene data. Treat exported artifacts accordingly.

## Third-Party Notices

Third-party components and license notices are tracked in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

LiteRT integration is based on Google AI Edge LiteRT-LM:

- Upstream: <https://github.com/google-ai-edge/LiteRT-LM>
- License: Apache License 2.0

## Repository License Status

This repository includes third-party notices, but it does not currently include a top-level project license file. Add an explicit project license before treating the repository as a published open-source distribution.
