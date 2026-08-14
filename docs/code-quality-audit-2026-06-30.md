# Watcher Code Quality Risk Audit - 2026-06-30

## Executive Summary

Overall rating: **High risk until the current worktree is stabilized and Gradle baselines are confirmed**.

This audit reviewed the current dirty state of `D:\watcher`, not a clean commit. The repository currently has **84 modified tracked files** and **292 untracked files**, including large new classroom, intent-router, gateway relay, proto, MCP, zip, and documentation artifacts. That makes the findings useful for triage, but not a release decision by itself.

Current scale:

- Android main Kotlin: **423 files**
- JVM tests: **51 files**
- Instrumentation tests: **1 file**
- MCP package files, excluding `node_modules`: **17 files**
- Largest hotspots include `AppDatabase.kt` (1364 lines), `HistoryWorkbenchPage.kt` (1233), `ClassroomRecordingOrchestrator.kt` (1190), `MainScreen.kt` (1160), `VideoExecutionOrchestrator.kt` (1126), and `VideoWorkflowController.kt` (1122).

The strongest risks are not basic Kotlin style. They are boundary risks: LAN gateway exposure, relay secret handling, mutable task/event state under concurrency, Room schema governance, and very large orchestration/UI files that make regression review hard.

## Verification Baseline

| Command | Result | Notes |
|---|---:|---|
| `git status --short` | Ran | Confirmed broad dirty worktree with modified Android, MCP, Gradle, docs, version files, plus many untracked source files and zip artifacts. |
| `.\gradlew.bat testDebugUnitTest` | Not completed here | Initial sandbox run failed on `D:\.gradle` lock access, then plugin resolution required network. User asked to run Gradle manually, so no further Gradle commands were run in this session. |
| `.\gradlew.bat lintDebug` | Not run here | User asked to handle Gradle manually. Treat lint/build status as unknown until manually confirmed. |
| `npm --prefix mcp run check` | Passed | `node --check` passed for `server.js`, `lib/gateway-client.js`, and `lib/discovery.js`. |
| `npm --prefix mcp test` | Passed | MCP integration suite reported **42 passed, 0 failed**. |

## P0 Findings

No P0 issue was proven from static review alone. The project should not be released, however, until the Gradle unit-test and lint baselines are run against this exact dirty worktree.

## P1 Findings

### P1-1: Relay defaults include a hardcoded bearer token over HTTP

Impact: **release-impact, privacy/security**.

Evidence:

- `app/src/main/java/com/example/watcher/data/gateway/GatewayModels.kt:166-170` defines `NtfyRelayConfig` with `serverUrl = "http://ntfy.shokz-watcher.cn"` and a default `authToken`.
- `mcp/lib/ntfy-client.js:52-54` defines the same HTTP server and default token.
- `mcp/server.js:651-655` falls back to `DEFAULT_NTFY_AUTH_TOKEN` when no runtime token is supplied.

Why it matters:

The relay path can carry cross-device handoff messages. A default token in source makes credential rotation and environment separation fragile, and HTTP transport exposes bearer credentials and message content to network observers.

Recommended fix:

Remove default relay credentials from source. Require explicit per-install relay configuration, enforce HTTPS for relay URLs, and fail closed when the server URL or token is missing. Add Android and MCP tests that prove the defaults are absent and HTTP relay URLs are rejected unless a debug-only override is active.

### P1-2: LAN gateway has broad browser-facing exposure

Impact: **LAN-exposure, release-impact**.

Evidence:

- `app/src/main/res/xml/network_security_config.xml:4` sets global `cleartextTrafficPermitted="true"` via `base-config`.
- `app/src/main/java/com/example/watcher/data/gateway/GatewayServer.kt:612-615` sets `Access-Control-Allow-Origin: *` and allows auth headers.
- `app/src/main/java/com/example/watcher/data/gateway/GatewayServer.kt:619-623` exempts `/api/health`, `/api/device/identity`, and `/api/device/pair-requests` from auth.
- `app/src/main/java/com/example/watcher/data/gateway/GatewayRoutes.kt:27-40` documents LAN APIs for visual monitoring, video analysis, agent runtime control, stream handoff, and commentary polling.

Why it matters:

The gateway is intentionally LAN-facing, but global cleartext plus wildcard CORS means any browser origin on a user device can attempt authenticated or unauthenticated LAN calls. The API controls sensitive workflows such as snapshots, tasks, agent runtime, and stream ownership. Normal gateway API key generation exists, but the browser/LAN attack surface remains too broad for release confidence.

Recommended fix:

Move cleartext policy behind debug or a narrow domain-config only. Replace wildcard CORS with an allowlist or disable CORS unless explicitly enabled. Add a gateway security test matrix covering unauthenticated endpoints, authenticated endpoints, browser-origin requests, and binding-token-only requests.

### P1-3: Gateway task/event state mixes concurrent maps with mutable task internals

Impact: **correctness/concurrency, gateway reliability**.

Evidence:

- `GatewayTaskManager.kt:37-42` uses `ConcurrentHashMap` for tasks/jobs/events.
- `GatewayModels.kt:12-21` stores each `GatewayTask.events` as a mutable list and `updatedAt` as mutable state.
- `GatewayTaskManager.kt:71-76` mutates `taskSnapshot.events` and `updatedAt` directly from executor callbacks.
- `GatewayTaskManager.kt:107-113` filters the same event list for polling without locking or snapshot copying.
- `GatewayTaskManager.kt:140-143` clears task state without cancelling running jobs.

Why it matters:

Concurrent maps do not make the mutable objects inside them safe. Gateway clients can poll events while executors append events, causing lost updates, inconsistent event ordering, or `ConcurrentModificationException`. `release()` clearing state without cancelling jobs also leaves background work able to continue after gateway shutdown.

Recommended fix:

Make `GatewayTask` immutable from the manager boundary: keep events in a per-task synchronized structure or update them through `compute`. Snapshot event lists before returning them. Make `release()` cancel all running jobs and clear callbacks. Add stress tests for create/poll/cancel/release concurrency.

### P1-4: Room schema governance is too fragile for version 64

Impact: **data loss/migration risk**.

Evidence:

- `AppDatabase.kt:77-78` declares `version = 64` and `exportSchema = false`.
- `AppDatabase.kt:1410-1423` manually registers migrations from `MIGRATION_11_12` through `MIGRATION_63_64`.
- `AppDatabaseBuilder.kt:12-19` builds the production database with registered migrations and no destructive fallback.
- Only one instrumentation test exists under `app/src/androidTest`, and none of the observed test list indicates Room migration coverage.

Why it matters:

The database has a long migration chain and many entities, but schema JSON export is disabled. That removes the normal Room audit trail and makes migration testing harder. A single missing or incorrect migration can brick upgrade paths for existing users.

Recommended fix:

Enable schema export into a checked-in schema directory, add migration tests for at least the latest 3-5 production versions and any version touched in this worktree, and add a small script/check that verifies every version step is registered.

## P2 Findings

### P2-1: Backup policy is partially hardened but still needs a sensitive-state inventory

Impact: **privacy, release-impact if exclusions are incomplete**.

Evidence:

- `AndroidManifest.xml:30-32` sets `allowBackup="true"` with backup and extraction rules.
- `backup_rules.xml:8-17` excludes the main Room database, gateway prefs, runtime secrets, LLM wallet prefs/secrets, and `agentframework/`.
- `data_extraction_rules.xml:7-25` mirrors those exclusions for cloud backup and device transfer.
- New or changed sensitive stores exist outside that list, including LiteRT config, ASR/AST config, app update state, and UI preference stores discovered by `getSharedPreferences(...)`.

Why it matters:

The current exclusions show good intent, but the project has grown new state surfaces. Backup should be treated as an allowlist or regularly audited inventory, especially for credentials, relay metadata, local model paths, generated topics, and agent memory.

Recommended fix:

Inventory every SharedPreferences/file/database store and classify it as backup-safe or excluded. Prefer `allowBackup=false` for release unless backup is a product requirement. If backup remains enabled, add a unit/static test that checks known sensitive preference file names are present in both XML exclusion files.

### P2-2: Gateway automation persistence silently discards corrupt JSON and rewrites whole collections

Impact: **data integrity, diagnosability, performance**.

Evidence:

- `GatewayAutomationManager.kt:489-504`, `531-552`, and `579-594` parse JSON from SharedPreferences using `runCatching { ... }.getOrElse { emptyList() }`.
- `GatewayAutomationManager.kt:496-508`, `538-556`, and `586-598` rewrite full JSON arrays back to SharedPreferences.
- `GatewayAutomationManager.kt:566-569` increments message IDs through load-modify-save in SharedPreferences.

Why it matters:

Corrupt persisted automation/relay state is silently interpreted as empty state, which can make user data disappear without an error path. Full-list rewrites and ID counters in SharedPreferences also create avoidable contention and make future multi-device relay behavior harder to reason about.

Recommended fix:

Log and surface parse failures, preserve the raw corrupt payload for recovery, and move relay conversations/messages/events to Room or a small append-only store with transactional IDs.

### P2-3: GatewayDelegate owns too many responsibilities and has lifecycle leaks

Impact: **maintainability, correctness**.

Evidence:

- `GatewayDelegate.kt:69-75` owns task manager, mDNS announcer, automation manager, server, prefs, secret store, and automation ownership state.
- `GatewayDelegate.kt:92-116` wires Ntfy state, monitor collection, automation reconciliation, config restore, and executor registration in one class.
- `GatewayDelegate.kt:260-282` persists relay config, configures the client, publishes presence, and starts foreground service behavior.
- `GatewayDelegate.kt:470-499` directly mutates stream ownership state from gateway callbacks using volatile variables.

Why it matters:

This class is an assembly point, server lifecycle manager, relay controller, stream handoff state machine, and automation coordinator. The mixed ownership makes it difficult to prove shutdown, cancellation, and reconnection behavior.

Recommended fix:

Split into focused collaborators: gateway server lifecycle, relay client facade, stream ownership state machine, automation monitor bridge, and executor registry. Start by extracting the stream ownership state machine with unit tests because it has small state and clear transitions.

### P2-4: Background subscription callbacks can crash or leak through caller scope

Impact: **reliability**.

Evidence:

- `NtfyRelayClient.kt:135-159` starts a long-running subscription job on an injected scope and starts heartbeat separately.
- `NtfyRelayClient.kt:395-416` mutates `_messages` under `messageLock`, then invokes persistence callback after the lock.
- `NtfyRelayClient.kt:418-420` invokes `onHandoffReceived` without local exception isolation.
- `GatewayDelegate.kt:271-282` assigns `onHandoffReceived`, restores local state, configures relay, publishes presence, and starts foreground service during config load.

Why it matters:

If the handoff callback throws, it can bubble into the subscription loop. Since the relay client uses an injected scope, lifecycle correctness depends on the parent object rather than the client exposing a strong close/release contract.

Recommended fix:

Wrap callbacks in `runCatching` with structured error reporting, give `NtfyRelayClient` an explicit `close()` that cancels subscription and heartbeat jobs, and test callback failure plus rapid enable/disable cycles.

### P2-5: Video and classroom orchestration hotspots are too large for safe feature work

Impact: **maintainability, regression risk**.

Evidence:

- `VideoExecutionOrchestrator.kt` is 1126 lines and coordinates recording, analysis workers, timeline aggregation, summarization, report refinement, and status emission.
- `VideoExecutionOrchestrator.kt:142-150` creates multiple cross-worker counters, channels, and copy-on-write lists.
- `ClassroomRecordingOrchestrator.kt:45-64` has a constructor with many collaborators and responsibilities.
- `ClassroomRecordingOrchestrator.kt` is 1190 lines and contains execution, cancellation, status shaping, visual evidence supplementation, audio slicing, note synthesis, and persistence.

Why it matters:

These files are central workflows with lots of side effects. They are testable in parts, but their size and mixed responsibilities make cancellation, partial failure, and resource cleanup regressions likely.

Recommended fix:

Split by workflow stage rather than by technical layer: capture/segment planning, segment analysis, evidence aggregation, summary/refinement, and persistence/finalization. Preserve current public repository APIs while extracting internal units with narrow tests.

### P2-6: Instrumentation coverage is far below the app's risk profile

Impact: **release confidence**.

Evidence:

- Only **1** instrumentation test file was found under `app/src/androidTest`.
- JVM tests are useful but skew toward parsers and pure logic. Observed grouping: classroom 15, intent-router 7, video 7, agentframework 5, gateway 1, UI 3, other 13.
- High-risk surfaces such as Room migrations, foreground service behavior, camera/audio permissions, Compose navigation, and actual gateway server behavior need Android/runtime coverage.

Recommended fix:

Add targeted instrumentation suites for Room migrations, foreground service start/stop reasons, camera/audio permission denial paths, and at least one Compose smoke test per main workflow page.

## P3 Findings

### P3-1: Large Compose screens and components need decomposition budgets

Evidence:

- `HistoryWorkbenchPage.kt` 1233 lines.
- `MainScreen.kt` 1160 lines.
- `MultiDeviceScreen.kt` 1013 lines.
- `ClassroomKnowledgeTreeCard.kt` 1003 lines.
- `WatcherScaffold.kt` 968 lines.
- `VideoStreamSettingsDialog.kt` 953 lines.

Recommended fix:

Set a working budget for new UI changes: no new workflow logic in these files without extracting a state mapper, event reducer, or focused subcomponent. Add screenshot or Compose tests for the extracted behavior.

### P3-2: MCP state files are plain JSON without atomic write or permission hardening

Evidence:

- `mcp/lib/paths.js:5-8` stores state under `%USERPROFILE%\.watcher-mcp` by default.
- `mcp/lib/state.js:8-23` reads JSON with silent fallback and writes with `fs.writeFileSync` directly.
- `mcp/server.js:658-671` persists relay topic, token, and server URL.

Recommended fix:

Write state atomically through temp-file-plus-rename, log parse failures, and set restrictive permissions where supported. Treat relay token storage as sensitive local state in MCP docs.

### P3-3: App-wide coroutine ownership is not explicit enough

Evidence:

- `WatcherApplication.kt:37-42` creates an application `CoroutineScope`.
- `WatcherApplication.kt:49-69` launches LiteRT initialization into that scope without an explicit timeout.
- `GatewayTaskManager.kt:37` creates its own `CoroutineScope(Dispatchers.Default + SupervisorJob())`.

Recommended fix:

Document scope ownership and shutdown semantics for application, gateway, relay, and workflow scopes. Add timeouts around optional startup initialization such as LiteRT model load.

## Positive Findings

- MCP syntax and integration baseline passed in this session: **42/42** tests.
- OpenAI-compatible provider endpoints now call `requireSecureEndpoint(...)`, and `SecureEndpointPolicyTest` verifies HTTP rejection.
- Gateway API key normal path generates and migrates a key through `AppRuntimeSecretStore`; the old "always empty API key" concern is not proven in the current normal path.
- Backup/data extraction rules already exclude the main database, gateway prefs, runtime secrets, LLM wallet prefs/secrets, and agent framework files.
- Room production builder registers the full visible migration array and does not use destructive fallback.

## Priority Fix Roadmap

1. **Security gate:** remove hardcoded relay token, require HTTPS relay, narrow or disable global cleartext, and replace wildcard CORS.
2. **Gateway correctness:** make task/event state immutable or synchronized, cancel running jobs on release, and add concurrency tests.
3. **Release baseline:** run `testDebugUnitTest` and `lintDebug` on this exact worktree; block release until failures are triaged.
4. **Database governance:** enable Room schema export and add migration tests for recent and touched versions.
5. **Lifecycle cleanup:** add explicit close/release contracts for relay, gateway task manager, and optional startup jobs.
6. **Decomposition track:** extract state machines and stage-level collaborators from GatewayDelegate, VideoExecutionOrchestrator, ClassroomRecordingOrchestrator, and the largest Compose screens.

## Follow-up Test Scenarios

- Gateway auth matrix: no auth, API key, binding token, bad token, browser Origin header, and pair endpoints.
- Relay config validation: no default token, HTTP rejected, HTTPS accepted, missing topic fails closed.
- Gateway task stress: create/poll/cancel/release while executors emit frequent events.
- Room migration: old schema to 64 for at least last 3-5 production versions and all versions touched by current changes.
- Foreground service: multiple start/stop reasons, concurrent stop while another reason remains active.
- Classroom/video cancellation: cancel during capture, during segment analysis, during summary/refinement, and after partial persistence.

## Audit Caveats

- This report intentionally did not modify production code, tests, Gradle config, Room schema, manifest components, or MCP tool contracts.
- Gradle test/lint status is unknown in this session because the user chose to run Gradle manually.
- The existing `docs/code-review-optimization-report.md` was treated only as a hint source. Findings above were included only when current code evidence was observed.
- Terminal mojibake was not treated as source corruption.
