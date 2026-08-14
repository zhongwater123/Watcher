# Contributing to Watcher

## Scope

Watcher is still moving quickly. Contributions are welcome, but changes should be pragmatic, well-scoped, and grounded in the actual code rather than older design notes.

## Before You Start

- Read [README.md](README.md) for the current product and setup overview.
- Read [docs/architecture-overview.md](docs/architecture-overview.md) before making cross-cutting changes.
- Treat dated product iteration documents under `docs/` as historical context, not as the source of truth.

## Development Setup

Requirements:

- Android Studio
- JDK 11
- Android SDK 35
- Android device or emulator running Android 10+

Optional `local.properties` entries:

```properties
API_KEY=your_remote_model_api_key
VOLCENGINE_ASR_APP_KEY=your_volcengine_asr_app_key
VOLCENGINE_ASR_ACCESS_KEY=your_volcengine_asr_access_key
VOLCENGINE_ASR_RESOURCE_ID=volc.seedasr.sauc.duration
```

Build commands:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
.\gradlew.bat lintDebug
```

## Project Layout

High-level structure:

- `app/src/main/java/com/example/watcher/agentframework`
- `app/src/main/java/com/example/watcher/data/council/agent`
- `app/src/main/java/com/example/watcher/data/fitness/agent`
- `app/src/main/java/com/example/watcher/data/training/fitness`
- `app/src/main/java/com/example/watcher/data/gateway`
- `app/src/main/java/com/example/watcher/data/local`
- `app/src/main/java/com/example/watcher/data/model`
- `app/src/main/java/com/example/watcher/data/remote`
- `app/src/main/java/com/example/watcher/data/repository`
- `app/src/main/java/com/example/watcher/ui/components`
- `app/src/main/java/com/example/watcher/ui/screens`
- `app/src/main/java/com/example/watcher/ui/viewmodel`
- `mcp` for the zero-build desktop MCP server and gateway tool adapter

Tests:

- `app/src/test` for JVM unit tests
- `app/src/androidTest` for instrumentation and Compose UI tests

## Coding Expectations

- Follow Kotlin official style with 4-space indentation.
- Keep package names lowercase.
- Use `PascalCase` for types and composables.
- Use `camelCase` for functions and properties.
- Use `UPPER_SNAKE_CASE` for constants.
- Keep business logic out of `MainActivity.kt`.
- Prefer extending existing workflow controllers and repositories rather than adding new ad hoc coordinators.

## Architectural Expectations

When adding or changing features:

- Keep persistence concerns in `data/local`.
- Keep network and provider adapters in `data/remote`.
- Keep orchestration and domain behavior in `data/repository`.
- Keep screen state composition in `ui/viewmodel`.
- Keep reusable UI in `ui/components`.
- Update documentation when behavior or architecture changes materially.

For larger changes, identify which of these flows you are touching:

- monitor workflow
- video analysis workflow
- live interaction workflow
- council workflow
- digital life card and behavior modeling
- gateway API
- agent framework
- LiteRT local inference

## Testing Expectations

Run the smallest useful test set for your change, and state what you ran.

Minimum guidance:

- Parsing, repository, and pure state logic: add or update JVM tests in `app/src/test`
- Room integration, camera/speech interactions, or Compose UI behavior: add or update `androidTest`
- Changes to monitor or intent-analysis flows should cover both success and failure paths
- Changes to video execution should cover cancellation and failure behavior where practical

## Pull Request Guidance

Use focused pull requests with short imperative titles, for example:

- `Add gateway health diagnostics`
- `Fix monitor baseline refresh flow`
- `Document provider wallet behavior`

Include:

- what changed
- why it changed
- how you verified it
- any config or migration impact
- screenshots for UI changes when they help review

## Security and Secrets

- Never commit `local.properties`.
- Never commit API keys, gateway tokens, or device credentials.
- Be careful with screenshots, recordings, and exported history because they may contain local network addresses or personal scene data.
- Review any changes to `network_security_config.xml` carefully.

## Documentation Rules

If your change alters behavior that users or contributors need to understand, update one or more of:

- `README.md`
- `docs/architecture-overview.md`
- `docs/agent-framework.md`
- inline code comments for non-obvious logic

Do not leave outdated docs behind when the code has already moved on.
