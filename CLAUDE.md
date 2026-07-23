# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is **Nacos Search**, an IntelliJ IDEA platform plugin that lets developers query Nacos configuration center data directly from the IDE. It is written in Kotlin and built with Gradle using the IntelliJ Platform Gradle Plugin.

- **Plugin ID**: `com.nanyin.nacos.search`
- **Version**: sourced from `build.gradle.kts` (currently `1.3.3`) — bump it there and mirror the `changeNotes`/`<change-notes>` blocks in both `build.gradle.kts` and `META-INF/plugin.xml` when releasing.
- **Target Platform**: IntelliJ IDEA Community Edition (`sinceBuild = 243`, `untilBuild = 261.*`)
- **JDK**: Java 17
- **Gradle**: 9.0.0
- **Kotlin**: 2.0.21
- **IntelliJ Platform Gradle Plugin**: 2.16.0

The plugin declares a right-side tool window (`Nacos Search`), a settings page under `Tools > Nacos Search`, two menu actions under `Tools` for refreshing/clearing the cache, and a Java PSI integration that provides `@NacosValue` gutter navigation and reverse Find Usages.

## Common Commands

Use the Gradle wrapper for all build operations:

```bash
# Build the installable plugin distribution
./gradlew buildPlugin

# Compile only
./gradlew compileKotlin compileTestKotlin

# Run a development instance of IntelliJ IDEA with the plugin loaded
./gradlew runIde

# Run plugin verification against configured IDE versions (2024.3.5, 2025.1, 2026.1 EAP)
./gradlew verifyPlugin

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.nanyin.nacos.search.services.NacosApiServiceTest"

# Run a single test method
./gradlew test --tests "com.nanyin.nacos.search.services.NacosApiServiceTest.test nacos service initialization"
```

The `build.gradle.kts` resolves the IntelliJ Platform from JetBrains repositories (`create("IC", "2024.3.5")`) rather than a local IDE installation. Make sure you have a network connection and enough disk space for the downloaded IDE artifacts. `IDEA_LOCAL_PATH` can optionally point `runIde` at a local IDE install.

All builds require **Java 17**. Set `JAVA_HOME` if your default JVM is older:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
```

Plugin signing is configured via the `intellijPlatform.signing` block and reads from `PRIVATE_KEY`, `CERTIFICATE_CHAIN`, and `PRIVATE_KEY_PASSWORD` environment variables. The `signPlugin` task is skipped automatically when these are not present. Publishing uses `PUBLISH_TOKEN`.

## High-Level Architecture

### Plugin Lifecycle

`NacosSearchPlugin` is an application-level `@Service`, a `ProjectActivity` (registered via the `com.intellij.postStartupActivity` extension point), and a `Disposable`. On project startup it:

1. Validates `NacosSettings`.
2. Kicks off background cache loading via `CacheService` (does not block the EDT).
3. Tests the Nacos connection asynchronously.
4. Loads initial configuration metadata if the cache is empty or disabled, then warms the `NacosKeyResolver` key index and preheats the full namespace index in the background so the first search and gutter markers are instant.
5. Schedules an automatic cache refresh job when enabled.

`RefreshCacheAction` / `ClearCacheAction` delegate to `NacosSearchPlugin.refreshCache()` / `clearCache()`. The `Disposable` implementation cancels the plugin's `CoroutineScope` and auto-refresh job on shutdown.

### Service Layer

Services are IntelliJ application- or project-level components registered with `@Service`. Important ones:

- `NacosApiService` — HTTP client for the Nacos Open API (`/nacos/v1/cs/configs`, `/nacos/v1/cs/config`, `/nacos/v1/console/namespaces`). It also maintains a short-lived in-memory cache of configuration responses per namespace and handles auth headers. Requests retry transient IO failures.
- `NacosAuthService` — Manages Nacos login tokens. Caches `accessToken` per `serverUrl+username`, refreshes before expiry, evicts stale entries on credential switch, and supports logout/validation.
- `NacosSearchService` — Project-level search orchestrator. Exposes debounced search, pagination state via Kotlin `StateFlow`, and immediate `performSearch` (which cancels any pending debounced search so a late coroutine can't overwrite a fresh result). Translates wildcard queries like `*config` into Nacos `blur`/`accurate` search modes.
- `SearchService` — Local search over the `CacheService` store with regex, content preview, highlighting, and scoring.
- `CacheService` — Persistent local cache. See **Cache persistence** below.
- `NamespaceService` — Persists the selected namespace (`PersistentStateComponent`, stored in `nacos-namespace-service.xml`) and notifies `NamespaceChangeListener`s when the user switches namespaces.
- `LanguageService` — Runtime language switching support for the plugin UI.

### Cache persistence (two caches, two layers)

There are **two** caches — do not confuse them:

1. `NacosApiService` keeps an in-memory response cache per namespace (5-minute TTL) to avoid repeated network round-trips within a session.
2. `CacheService` persists configurations across IDE restarts. It holds three `ConcurrentHashMap`s in memory — config details, list pages, and namespace indexes — each entry wrapped with TTL metadata.

For the persistent layer, `CacheService` splits storage:

- **Lightweight key lists** (the set of cached entry keys) live in IntelliJ `PropertiesComponent` under the `nacos.cache.*` namespace.
- **Heavy payloads** (full config content) live in per-entry JSON files owned by `CacheFileStorage`, under `…/nacos-search-cache/{details,listpages}/` beneath the IDE config path. This replaced a single multi-hundred-MB state XML and fixed slow startup / orphan blobs. On first run after upgrade, legacy `PropertiesComponent` payloads are migrated to files once (bounded by size/count).

Cache loads run in the background; read methods await a `CompletableDeferred` load signal before serving results that depend on the full load, while single-key reads resolve from file immediately. Enforced limits: max 1,000 entries, 5-minute default TTL, lock-free reads with background expiry reclamation.

### `@NacosValue` Navigation & PSI Subsystem

The `psi/` package (registered in `plugin.xml` for `language="JAVA"`) is the plugin's code-intelligence layer. It reads only from `CacheService`, so gutter markers and navigation are only as accurate as the locally cached configs. Components:

- `PlaceholderParser` — parses `${...}` placeholders out of string literals in `@NacosValue` / `@Value` annotations.
- `NacosValueReferenceContributor` + `NacosValueReference` — contribute PSI references powering go-to-declaration.
- `NacosValueLineMarkerProvider` — renders a three-state gutter icon: fresh resolved (blue solid), stale resolved (amber solid with a clock), or unresolved (gray hollow). Shown only when the key is cached or a dataId context allows remote fallback; only a fresh, complete namespace index may prove that a dataId is absent. Clicking navigates, and multiple namespace matches open a chooser.
- `NacosConfigKeyReferenceSearcher` — reverse **Find Usages**: from a Nacos config key to the Java usages. Honors the search cancel signal.
- `NacosPlaceholderIndex` — a `FileBasedIndex` mapping placeholder keys → `.java` files, for fast reverse lookup without scanning the project.
- `NacosKeyResolver` — resolves a placeholder key to the cached `NacosConfiguration`(s) that define it. Pure w.r.t. PSI (reads only `CacheService`); builds an in-memory `KeyIndex` and orders hits by namespace relevance (active namespace > public > others). The index is rebuilt lazily when the cache's modification count changes.
- Supporting: `ConfigKeyExtractor` (key + `KeyLocation` extraction from config content), `NacosConfigNavigator` (navigate to the config detail panel), `NacosConfigKeyElement` (PSI element carrying the source element for lazy-load), `NacosPopupChoiceItems` / `NacosUsagePresentation` (popup + Find Usages presentation).

### Authentication Modes

`AuthMode` (in `settings/NacosSettings.kt`) controls how requests are signed:

- `TOKEN` — Login via `/nacos/v1/auth/login` and append `accessToken` as a query parameter.
- `BASIC` — Send an `Authorization: Basic ...` header with the configured username/password.
- `HYBRID` — Try token auth first; fall back to Basic auth if no token is available.

Login form credentials are URL-encoded. Token auth is cached in `NacosAuthService` and refreshed before expiration.

### Multi-Server Environments & Credentials

`NacosSettings` is a **master-detail, multi-server** model: a `servers: MutableList<NacosServerConfig>` plus an `activeServerId`. The flat legacy fields on `NacosSettings` (`serverUrl`, `username`, `namespace`, `authMode`, …) always mirror the active server for backward compatibility with services that still read them — keep the two in sync when changing the active server. `EnvironmentSwitcher` (tool-window header) switches the active environment without opening Settings.

Passwords are **not** stored in the settings XML. `NacosCredentialStore` keeps them in IntelliJ `PasswordSafe`, keyed by the stable server id (one entry per environment). Legacy plaintext passwords are migrated on load. When touching settings/credentials, write passwords through `NacosCredentialStore`, never onto the `NacosServerConfig`/`NacosSettings` state that gets serialized.

### Settings & Persistence

`NacosSettings` is a `PersistentStateComponent` stored in `nacos-search.xml`. It contains the server list, credentials (passwords excluded — see above), cache/refresh options, search preferences, connection timeouts/retry, and UI state. `NacosConfigurable` provides the Swing UI for `Settings/Preferences > Tools > Nacos Search` and fires `NacosSettingsListener` events so the tool window reacts to preference-only changes without a full cache reload.

### UI Layer

The tool window is registered in `META-INF/plugin.xml` and created by `NacosSearchToolWindowFactory`, which builds a `NacosSearchWindow`. Major panels:

- `EnvironmentSwitcher` — active-environment switcher in the header.
- `SearchPanel` — search input and options.
- `NamespacePanel` — namespace selector.
- `ConfigListPanel` — list of search results.
- `ConfigDetailPanel` — details of the selected configuration.
- `PaginationPanel` — page navigation.

Components implement `LanguageAwareComponent` so labels update when the plugin language is changed via `LanguageService`.

### Internationalization

Message bundles live in `src/main/resources/messages/`:

- `NacosSearchBundle.properties` (English)
- `NacosSearchBundle_zh_CN.properties` (Chinese)

`NacosSearchBundle` (under the `bundle` package) is the `AbstractBundle` accessor used throughout the UI. The Chinese bundle is UTF-8 encoded.

### Actions

Two actions are registered in `plugin.xml` and added to the `ToolsMenu`:

- `RefreshCacheAction` — triggers a cache refresh through `NacosSearchPlugin`.
- `ClearCacheAction` — clears both the persistent cache and the API in-memory cache.

## Important Implementation Details

- `NacosApiService.getConfigurationFromItem()` fetches full configuration content for each item returned by `listConfigurations`. `getAllConfigurations` now fetches these per-item contents concurrently (bounded at 8) rather than sequentially, but loading a large namespace still issues many HTTP calls.
- `NacosSearchService.SearchRequest.isFuzzySearch()` treats `*` and `?` as wildcards. A leading `*` is stripped before calling the Nacos API in `getProcessedDataId()`.
- Settings UI is built with **Kotlin UI DSL Version 2** (`com.intellij.ui.dsl.builder.panel`). If you modify `settings/NacosConfigurable.kt`, avoid the deprecated `com.intellij.ui.layout` DSL and `titledRow`.
- The plugin targets **2024.3.5** as the build platform with `sinceBuild = 243` and `untilBuild = 261.*` for 2026.1 compatibility. Keep API usage limited to what's available in build 243 if you want broad compatibility.
- `StartupActivity` has been migrated to **`ProjectActivity`** (`com.intellij.openapi.startup.projectActivity`) with a `postStartupActivity` extension in `plugin.xml`.
- Tool window icons are SVGs under `src/main/resources/icons/` (`nacosSearch.svg`, `nacosSearch_dark.svg`, `nacosSearch_20.svg`, `nacosSearch_20_dark.svg`). `plugin.xml` references `/icons/nacosSearch_20.svg`; IntelliJ automatically picks the `_dark` variant in dark themes.
- The `.claude/settings.local.json` already allows `./gradlew:*` commands, so Gradle tasks should run without permission prompts.
- Some UI tests in `NamespacePanelTest` have pre-existing timing/EDT issues and may fail under the newer test framework; they are not indicative of plugin runtime behavior.

## Agent skills

### Issue tracker

Issues and PRDs are tracked in GitHub Issues; external PRs are also a triage surface. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the default five-role GitHub label vocabulary. See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repository using root `CONTEXT.md` and `docs/adr/`. See `docs/agents/domain.md`.
