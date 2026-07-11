# Search and Namespace Index Orchestration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Guarantee latest-request-wins search behavior and predictable, deduplicated full namespace refreshes while preserving old complete data on failure.

**Architecture:** A project-level `NamespaceIndexCoordinator` is the sole owner of full-index work, keyed by access identity and namespace. Search requests carry generations and a 15-second front-end budget. UI consumes state but visible status and settings changes remain blocked until full mockups are approved.

**Tech Stack:** Kotlin coroutines, StateFlow, IntelliJ project services, JUnit 5 coroutine tests.

---

## File Map

- Create `services/NamespaceIndexCoordinator.kt`: single-flight, cooldown, foreground/preheat triggers.
- Modify `services/NacosSearchService.kt`: generation checks and structured dataset state.
- Modify `NacosSearchPlugin.kt`: delegate startup/namespace work; no periodic refresh.
- Modify `ui/NacosSearchWindow.kt`: call coordinator on namespace changes without visible layout changes.
- Modify `actions/RefreshCacheAction.kt`: explicit full-index refresh for selected namespace.
- Test `services/NamespaceIndexCoordinatorTest.kt`, `NacosSearchServiceCancelTest.kt`, and `NacosSearchServiceTest.kt`.
- UI approval artifacts only: `docs/ui/stability-status-change-map.md`, `docs/images/stability-tool-window-complete.png`, `docs/images/stability-settings-complete.png`.

### Task 1: Build the single-flight namespace coordinator

- [ ] **Step 1: Write failing coordinator tests**

Test same identity/namespace joins one job; different keys run independently; namespace switch cancels obsolete foreground work; PSI attempts are blocked for five minutes after failure; auth failure pauses PSI until settings change, manual refresh, or diagnostic success.

- [ ] **Step 2: Add trigger and key types**

```kotlin
enum class IndexTrigger { NAMESPACE_SWITCH, SEARCH, MANUAL_REFRESH, PSI }
data class NamespaceIndexKey(val identity: AccessIdentity, val namespaceId: String)
sealed interface IndexOutcome {
    data class Complete(val count: Int, val state: DatasetState) : IndexOutcome
    data class Partial(val loaded: Int, val expected: Int, val state: DatasetState) : IndexOutcome
    data class Stale(val count: Int, val state: DatasetState) : IndexOutcome
    data class Failed(val error: NacosRequestError) : IndexOutcome
}
```

- [ ] **Step 3: Implement coordinator rules**

Use a `Mutex`-guarded map of `Deferred<IndexOutcome>` by key. SEARCH and MANUAL_REFRESH use `INTERACTIVE` plus a 15-second cutoff; NAMESPACE_SWITCH and PSI use `PREHEAT`. PSI observes five-minute monotonic cooldown. Only `COMPLETE` writes namespace index; `PARTIAL` writes successful details only.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew test --tests "com.nanyin.nacos.search.services.NamespaceIndexCoordinatorTest"
git add src/main/kotlin/com/nanyin/nacos/search/services/NamespaceIndexCoordinator.kt src/test/kotlin/com/nanyin/nacos/search/services/NamespaceIndexCoordinatorTest.kt
git commit -m "feat: coordinate namespace index refreshes"
```

### Task 2: Enforce latest-request-wins search

- [ ] **Step 1: Add failing race tests**

Cover debounce followed by immediate search, slow page 1 followed by fast page 2, server switch during request, namespace switch during request, and canceled request completing transport late. Assert only latest generation publishes state.

- [ ] **Step 2: Add generation state**

```kotlin
private val requestGeneration = AtomicLong(0)

private suspend fun publishIfCurrent(generation: Long, request: SearchRequest, state: SearchState) {
    if (generation == requestGeneration.get() && request.serverId == settings.activeServerId) {
        _searchState.value = state
    }
}
```

Capture namespace and access identity in `SearchRequest`; do not read mutable global settings after execution starts.

- [ ] **Step 3: Replace `SearchSource`**

Change `SearchState.Success` to carry `DatasetState`. Preserve the existing configurations and pagination fields. On refresh failure retain the previous success payload, change freshness to `STALE`, and attach a non-destructive error field instead of publishing `SearchState.Error` with an empty list.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew test --tests "com.nanyin.nacos.search.services.NacosSearchServiceTest" --tests "com.nanyin.nacos.search.services.NacosSearchServiceCancelTest"
git add src/main/kotlin/com/nanyin/nacos/search/services/NacosSearchService.kt src/test/kotlin/com/nanyin/nacos/search/services/NacosSearchServiceTest.kt src/test/kotlin/com/nanyin/nacos/search/services/NacosSearchServiceCancelTest.kt
git commit -m "fix: prevent stale searches from overwriting current state"
```

### Task 3: Route every full-index trigger through the coordinator

- [ ] **Step 1: Add failing integration tests**

Assert namespace switch preheats once when index is expired, ordinary Data ID/group/page searches never full-load, content/regex/index wildcard searches do, manual refresh forces full-load, and no timer initiates refresh.

- [ ] **Step 2: Replace direct calls**

Remove direct `getAllConfigurations` calls from `NacosSearchPlugin`, `NacosSearchWindow`, and `NacosSearchService`. Route startup/namespace switch to `NAMESPACE_SWITCH`, local-index search to `SEARCH`, RefreshCacheAction to `MANUAL_REFRESH`, and PSI preheat requests to `PSI`.

- [ ] **Step 3: Preserve settings compatibility without behavior**

Keep legacy auto-refresh fields readable in `NacosSettings` and `NacosServerConfig` but do not schedule work from them. Add a migration test proving `autoRefreshEnabled = true` does not create a periodic job.

- [ ] **Step 4: Run integration tests and commit**

```bash
./gradlew test --tests "com.nanyin.nacos.search.services.NamespaceIndexCoordinatorTest" --tests "com.nanyin.nacos.search.NacosSearchPluginTest" --tests "com.nanyin.nacos.search.services.NacosSearchServiceTest"
git add src/main/kotlin/com/nanyin/nacos/search src/test/kotlin/com/nanyin/nacos/search
git commit -m "refactor: centralize full namespace refresh triggers"
```

### Task 4: Produce the mandatory UI approval package

This task creates design artifacts only. Do not modify production UI files.

- [ ] **Step 1: Write the exact change map**

Create `docs/ui/stability-status-change-map.md` covering:

- `NacosConfigurable.kt:64, 141, 237-240, 566-569, 686, 739`: remove the auto-refresh checkbox and dirty-state comparisons after approval.
- `NacosSearchWindow.kt`: add a compact source/freshness/completeness status presenter beside the existing list state; retain current list on refresh failure.
- `ConfigListPanel.kt`: render only the approved compact status component; it must not own network state.
- `NacosSearchService.kt`: supply `DatasetState` and non-destructive refresh error.
- English and Chinese message bundles: labels for remote/cache, fresh/stale/unknown, complete/partial, last successful update, and retry.

Document normal, loading, empty, partial 488/500, stale, unknown, auth failure, timeout, overflow, keyboard focus, retry, environment switch, and namespace switch states.

- [ ] **Step 2: Create complete high-fidelity mockups**

Use the approved visual companion/image workflow to create:

- `docs/images/stability-tool-window-complete.png`: full IntelliJ tool window at realistic width, showing search, namespace, list, detail, pagination, and the new status location. Include a side-by-side state board for normal, partial, stale, and error-preserved-data states.
- `docs/images/stability-settings-complete.png`: full settings page showing the advanced section after auto-refresh removal, not a cropped checkbox mockup.

- [ ] **Step 3: Obtain explicit user approval**

Present the change map and both images. Record approval text and date in `docs/ui/stability-status-change-map.md`. Expected gate state: `Approved`; any requested change requires regenerated artifacts.

- [ ] **Step 4: Stop at the gate**

Do not add UI implementation steps to this plan until the approval record exists. After approval, create a separate `YYYY-MM-DD-stability-ui.md` implementation plan with screenshot-based UI tests and exact component code.

### Task 5: Final non-UI verification

- [ ] **Step 1: Run focused stability suite**

```bash
./gradlew test --tests "com.nanyin.nacos.search.services.*" --tests "com.nanyin.nacos.search.psi.*" --tests "com.nanyin.nacos.search.NacosSearchPluginTest"
```

Expected: PASS.

- [ ] **Step 2: Compile and inspect forbidden patterns**

```bash
./gradlew compileKotlin compileTestKotlin
rg "Thread\.sleep|runBlocking|autoRefreshJob|setupAutoRefresh" src/main/kotlin/com/nanyin/nacos/search
```

Expected: compilation succeeds; no forbidden pattern remains in the affected production paths.

- [ ] **Step 3: Commit verification-only adjustments**

If verification required no source change, do not create an empty commit. If a focused test fixture changed, stage only that fixture and commit with `test: cover stability release integration`.

