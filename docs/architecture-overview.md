# Architecture Overview

## Purpose

This document describes the current code-driven architecture of Watcher.

It is intentionally based on the repository as it exists now, not on older product notes or aspirational design documents.

## Top-Level Shape

Watcher is a single Android app module, but the codebase behaves like a multi-workbench system with several runtime subsystems:

- real-time monitoring
- segmented video analysis
- live commentary and audience interaction
- council-style multi-expert analysis
- long-term observation and behavior modeling
- embedded gateway APIs
- integrated agent runtime services
- optional local model execution through LiteRT

## Main Runtime Assembly

The most useful entry points for understanding the app are:

1. `MainActivity`
2. `MainScreen`
3. `IntentViewModel`
4. `WatcherApplication`

### `MainActivity`

`MainActivity` is intentionally thin. It sets the Compose content and requests notification permission.

### `MainScreen`

`MainScreen` is the main workbench UI. It hosts the current top-level app surfaces:

- `Monitor`
- `Hub`
- `Analysis`
- `History`
- `Templates`

It also handles:

- landscape entry into `Live` mode
- landscape entry into `Council` mode
- stream settings and device dialogs
- navigation into `API Wallet`, `Agent Config`, `Digital Life Card`, and `LiteRt`

### `IntentViewModel`

`IntentViewModel` is the main application assembly point. It wires together:

- Room database access
- provider wallet resolution
- monitor workflow
- video workflow
- history workflow
- live interaction workflow
- device and stream management
- template and council expert management
- embedded gateway control

If you need to understand the real system boundaries, start here.

### `WatcherApplication`

`WatcherApplication` initializes app-level services that outlive a single screen:

- `AgentFrameworkContainer`
- LiteRT model asset installation
- LiteRT config loading and model path resolution
- optional LiteRT engine auto-initialization at startup

This is where the app crosses from ordinary UI code into longer-lived runtime infrastructure.

## Core Workflows

### 1. Monitor Workflow

Primary classes:

- `IntentRepository`
- `MonitorWorkflowController`
- `MonitorManager`
- `HistoryRepository`

Flow:

1. The user enters a monitoring request.
2. `IntentRepository` converts the request and optional baseline frame into a structured `IntentResult`.
3. `MonitorWorkflowController` manages save/load/update behavior and baseline refresh.
4. `MonitorManager` runs the actual monitoring loop.
5. Frames are compared or sent to the model backend.
6. Alerts, logs, event frames, and session video are persisted through `HistoryRepository`.

Important characteristics:

- Supports both scene-baseline and reference-target modes
- Can skip model calls when frame change is below threshold
- Persists monitor runs, events, media, and status snapshots
- Can drive local alerting and compatible LED feedback

### 2. Video Analysis Workflow

Primary classes:

- `VideoWorkflowController`
- `VideoProcessRepository`
- `VideoTaskPlanner`
- `VideoExecutionOrchestrator`
- `VideoSegmentProcessor`

Flow:

1. The user describes a longer analysis task.
2. `VideoTaskPlanner` generates a structured plan.
3. `VideoWorkflowController` keeps the editable draft and execution state.
4. `VideoExecutionOrchestrator` creates the run and coordinates segment execution.
5. `VideoSegmentProcessor` records and analyzes segments.
6. Results, events, and media are persisted for history review.

Important characteristics:

- Supports AI-generated task planning
- Segments longer observation into smaller recorded units
- Supports streamed status updates during execution
- Persists both per-segment and final-run outputs

### 3. Live Interaction Workflow

Primary classes:

- `LiveInteractionController`
- `LiveCommentaryRepository`
- `AiAudienceManager`
- `data/repository/agent/AgentAudienceManager`
- `LiveSpeechRecognitionManager`

Flow:

1. The user enters landscape immersive mode.
2. Commentary starts against the current frame provider.
3. Speech recognition can feed transcript context into the active interaction mode.
4. Classic and agent-style audiences react to shared live context.
5. Danmaku and audience state are surfaced back into the UI.

This flow is separate from ordinary monitoring and is closer to a stream-driven interaction engine.

### 4. Council Workflow

Primary classes:

- `LiveInteractionController`
- `CouncilManager`
- `CouncilEntryConfigGenerator`
- `CouncilExpertRepository`
- `TemplateRepository`

Flow:

1. The user loads or generates a council entry configuration.
2. Live commentary and speech can be used as shared context.
3. Multiple expert roles are activated against the same stream context.
4. A synthesized result is produced from the shared analysis flow.

This is one of the clearest examples of the app moving beyond simple single-model invocation.

### 5. Digital Life Card and Behavior Modeling

Primary code areas:

- `DigitalLifeCardActivity`
- `DigitalLifeCardViewModel`
- behavior and portrait repositories in `data/repository`
- Room entities for blackboard, portraits, claims, goals, logs, and scene profiles

This subsystem is responsible for turning repeated observations into longer-lived records:

- blackboard entries
- portrait dimensions
- behavior claims
- reasoning logs
- observation goals
- scene profiles

It is not just a history view. It is a separate long-term modeling layer built on top of prior runs and observations.

## Shared Infrastructure

### Database

`AppDatabase` is the main local persistence surface.

It currently stores data for:

- monitor tasks and runs
- monitor events and media
- stream settings
- video tasks, runs, and segments
- timeline events
- templates
- providers
- audience state and messages
- council experts and knowledge
- blackboard and portrait modeling
- behavior claims and reasoning logs
- scene profiles

The migration history is substantial and should be treated as a sign that schema changes need care.

### History

`HistoryRepository` provides a unified view over:

- live monitor runs
- video analysis runs
- their media assets
- their event timelines

This repository is where separate workflows are normalized into one review surface.

### Provider Wallet

`LlmWalletRepository` manages runtime model providers.

Responsibilities:

- provider CRUD over Room
- encrypted secret resolution
- default-provider selection
- compatibility fallback to Ark-style configuration
- provider connectivity status caching

The codebase no longer assumes one static remote provider configuration path.

### Device and Stream Support

Key classes:

- `LanStreamScanner`
- `StreamDeviceCoordinator`
- `DeviceProvisionCoordinator`
- `Esp32CameraController`
- `LedController`

These support local-device workflows around the stream itself, not just model execution.

### Gateway API

The embedded gateway lives under `data/gateway`.

Key classes:

- `GatewayServer`
- `GatewayRoutes`
- `GatewayTaskManager`

Capabilities include:

- health and capability endpoints
- device identity and pairing for LAN discovery
- device identity and optional automation-oriented gateway extensions
- stream snapshots
- task creation and status inspection
- agent runtime inspection and control
- commentary state access
- stream handoff and reclaim signaling

This subsystem makes the app usable as a local network service, not only as a handheld UI.

The repository root also contains `mcp/`, a zero-build desktop MCP companion that discovers Watcher devices over LAN and exposes the gateway as generic tools for external agents.

### Agent Runtimes

Watcher has three intentionally independent Agent code paths:

- `agentframework`: the reusable formal runtime used by gateway and framework services.
- `data/council/agent`: the Council-owned expert runtime, tools, session memory, and orchestration.
- `data/fitness/agent`: fitness planning Agents and the isolated legacy visual-feedback analyzer.

The two business Agent domains do not inherit from, run through, or import `agentframework`. They may
receive the same application-level `LlmWalletRepository` or provider implementations through explicit
constructor injection, but they do not reference each other. The former ambiguous `data/agent` root no
longer exists.

Key layers:

- `core`
- `runtime`
- `autonomy`
- `multiagent`
- `service`
- `memory`
- `knowledge`
- `tools`

The framework is not a stub. It is already wired into the app through:

- `WatcherApplication`
- `AgentFrameworkContainer`
- gateway agent endpoints
- app-specific brain factories and connection testing

See [agent-framework.md](agent-framework.md) for more detail.

### LiteRT Local Inference

LiteRT support is currently organized under `data/local/litert`.

Important components:

- asset installation
- config storage
- model path resolution
- download support
- engine lifecycle
- app-facing provider adapter
- app-facing brain factory

This is the local-model path that complements, rather than replaces, remote providers.

## Project Package Map

```text
com.example.watcher
├─ agentframework
│  ├─ autonomy
│  ├─ core
│  ├─ integration
│  ├─ knowledge
│  ├─ memory
│  ├─ multiagent
│  ├─ runtime
│  ├─ service
│  └─ tools
├─ data
│  ├─ council
│  │  └─ agent
│  ├─ fitness
│  │  └─ agent
│  ├─ gateway
│  ├─ local
│  ├─ model
│  ├─ remote
│  ├─ repository
│  └─ training
└─ ui
   ├─ components
   ├─ screens
   ├─ theme
   └─ viewmodel
```

## Recommended Reading Order

If you are new to the repository, read in this order:

1. `ui/screens/MainScreen.kt`
2. `ui/viewmodel/IntentViewModel.kt`
3. `ui/viewmodel/MonitorWorkflowController.kt`
4. `data/repository/MonitorManager.kt`
5. `ui/viewmodel/VideoWorkflowController.kt`
6. `data/repository/VideoProcessRepository.kt`
7. `ui/viewmodel/LiveInteractionController.kt`
8. `data/repository/HistoryRepository.kt`
9. `WatcherApplication.kt`
10. `data/gateway/GatewayServer.kt`
11. `agentframework/service/AgentFrameworkService.kt`

## What This Document Does Not Cover

This document does not try to explain every entity, prompt, or UI state in detail.

For deeper subsystem details:

- database and entities: `docs/2026-04-07-database-field-summary.md`
- agent runtime internals: `docs/agent-framework.md`
- historical product reasoning: dated docs under `docs/`
