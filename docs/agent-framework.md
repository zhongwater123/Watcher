# Agent Framework

## Scope

Watcher contains a substantial standalone agent runtime under `app/src/main/java/com/example/watcher/agentframework`.

It is not just a future-facing design stub. The framework is already integrated into the app through:

- `WatcherApplication`
- `AgentFrameworkContainer`
- app-specific brain factory registration
- LiteRT-backed brain registration
- gateway endpoints for agent listing and runtime control

This document describes the framework as it exists now.

## Design Goals

The framework is built to support durable, autonomous, tool-using agents inside an Android app environment.

Current goals include:

- long-lived agent identities
- autonomous multi-step execution
- persistent memory and knowledge
- pluggable brain backends
- service-level invocation APIs
- structured runtime state and event capture
- optional multi-agent coordination

## Package Layout

The framework is split into the following packages:

- `core/`
  Agent definitions, events, and shared models
- `runtime/`
  Session-level execution primitives and agent brains
- `autonomy/`
  Closed-loop autonomous runtime and lifecycle machinery
- `tools/`
  Tool interfaces and default context tools
- `memory/`
  Session memory storage abstractions
- `knowledge/`
  Long-term knowledge storage abstractions
- `multiagent/`
  Team coordination and collaboration models
- `service/`
  External-facing facade for registration, invocation, persistence, and runtime tracking
- `integration/`
  App-specific integration helpers and default brain factory wiring

## Main Concepts

### Agent definition

An agent has a durable identity and behavioral configuration:

- `agentId`
- `name`
- `systemInstruction`
- `goal`
- run configuration and metadata

### Brain

A brain is the decision engine behind an agent.

Examples in the current codebase:

- app-default remote LLM brain factory
- LiteRT-backed local brain factory
- JSON-protocol brains for structured autonomous decisions

### Tools

Tools are registered through `AgentToolRegistry` and may be used during autonomous execution.

The framework includes default context tools so agents can interact with memory and knowledge stores without custom business glue for every run.

### Memory

The framework distinguishes between runtime/session memory and longer-lived structured memory:

- `AgentMemoryStore`
- structured memory store and manager abstractions in `autonomy/`

### Knowledge

Long-term knowledge is stored separately through `AgentKnowledgeStore`.

This allows an agent profile to evolve across runs instead of discarding context after each invocation.

### Service facade

`AgentFrameworkService` is the app-facing API surface for:

- registering agents
- registering brain factories
- invoking agents
- starting autonomous runtimes
- submitting signals
- reading and writing memory
- reading and writing knowledge
- querying runtime records
- lifecycle reconciliation for persistent executions

## Runtime Layers

### Low-level runtime

The low-level runtime provides the basic session machinery:

- `AgentKernel`
- `ManagedAgent`
- `AgentSessionController`
- `AgentBrain`
- `JsonProtocolAgentBrain`

This layer handles:

- turn execution
- tool call handling
- session lifecycle
- stop conditions
- session history

### Autonomous runtime

The `autonomy/` package builds a closed-loop runtime on top of the session-level pieces.

Key pieces include:

- `AutonomousAgentRuntime`
- lifecycle state tracking
- signal ingestion
- communication hub integration
- structured memory management
- bounded runtime policies

This layer is responsible for allowing an agent to continue operating across multiple cycles without business code driving every turn.

### Multi-agent support

The `multiagent/` package introduces team-level collaboration primitives.

Key pieces include:

- `TeamModels`
- `SharedCollaboration`
- `TeamStrategies`
- `MultiAgentCoordinator`

This layer is designed to keep team orchestration outside a single agent brain so that:

- shared state can be explicit
- roles can remain distinct
- coordination logic stays inspectable

### Service and persistence

The `service/` package is the stable boundary for external callers.

Key responsibilities:

- profile persistence
- invocation records
- runtime records
- evolution strategies
- tool registry ownership
- persistent-runtime reconciliation

`AgentFrameworkService.createPersistent(...)` and the builder path are the main entry points for setting up durable storage-backed runtime services.

## How Watcher Integrates It

Watcher wires the framework in `WatcherApplication` through `AgentFrameworkContainer`.

Current integration steps:

1. Create or load the Room-backed and file-backed dependencies needed by the app.
2. Build `LlmWalletRepository`.
3. Create the default remote brain factory.
4. Create the LiteRT-backed brain factory.
5. Register both into a `StaticAgentBrainCatalog`.
6. Build an `AgentFrameworkService` with persistent storage under the app files directory.
7. Expose this service to UI and gateway entry points.

Practical consequences:

- the app can manage agent profiles and brains
- the app can expose agent runtime control through the gateway
- remote and local model backends can both participate in the same service layer

## Gateway Integration

`GatewayServer` exposes a subset of the framework over LAN APIs:

- list agents
- get an agent profile
- list runtimes for an agent
- start a runtime
- inspect runtime state
- inspect runtime events
- submit runtime signals
- stop runtimes

This makes the framework usable by external local tools without coupling them to the Android UI.

## Memory and Knowledge Access

The framework supports two coordinated access paths:

- service APIs for the host application or external adapters
- runtime tools available to the agent itself

Through `AgentFrameworkService`, callers can:

- preload memory before invocation
- preload knowledge before invocation
- read invocation memory
- write invocation memory
- clear invocation memory
- read, query, write, and delete long-term knowledge
- inspect structured memory

This enables a full loop where:

1. the caller injects context
2. the agent decides what to use
3. the agent persists useful output
4. later runs can evolve from that persisted state

## Brain Factories and Connection Testing

App-level brain wiring is intentionally abstracted behind factories and testers.

Important app integration types include:

- `AppDefaultAgentBrainFactory`
- `LiteRtAgentBrainFactory`
- `AppAgentBrainConnectionTester`
- `CompositeAgentBrainConnectionTester`

This allows the app to validate whether a configured brain backend is usable before relying on it at runtime.

## JSON Protocol

The structured JSON protocol used by `JsonProtocolAgentBrain` is designed so an LLM can express:

- a short reasoning summary
- a user-facing reply
- optional memory writes
- a next action

Representative action types include:

- `continue`
- `tool_calls`
- `wait`
- `finish`

The important constraint is that the brain output must remain machine-interpretable so the runtime can enforce execution rules.

## Boundaries and Non-Goals

The framework currently does not try to be:

- a public SDK with stable semver guarantees
- a cloud-native orchestration platform
- a replacement for the app's domain-specific repositories

It is a reusable runtime subsystem inside Watcher, with clear enough boundaries that it could later be extracted or adapted if needed.

## Recommended Reading Order

1. `agentframework/service/AgentFrameworkService.kt`
2. `agentframework/runtime/AgentSessionController.kt`
3. `agentframework/runtime/JsonProtocolAgentBrain.kt`
4. `agentframework/autonomy/AutonomousAgentRuntime.kt`
5. `agentframework/multiagent/MultiAgentCoordinator.kt`
6. `agentframework/integration/AppDefaultAgentBrainFactory.kt`
7. `data/local/litert/LiteRtAgentBrainFactory.kt`
8. `WatcherApplication.kt`

## Related Docs

- [../README.md](../README.md)
- [architecture-overview.md](architecture-overview.md)
