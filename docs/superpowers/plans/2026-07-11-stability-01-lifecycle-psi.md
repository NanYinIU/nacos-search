# Lifecycle and PSI Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every background task disposable, remove periodic refresh, and ensure PSI hot paths only read an in-memory snapshot.

**Architecture:** Application and project services own their coroutine scopes and cancel them through `Disposable`. PSI resolution reads an immutable snapshot carrying an explicit availability state; a separate preheater may request namespace data but PSI never waits for it.

**Tech Stack:** Kotlin coroutines, IntelliJ `Disposable`/`Disposer`, StateFlow, JUnit 5.

---

## File Map

- Create `src/main/kotlin/com/nanyin/nacos/search/psi/ConfigReferenceStatus.kt`: reference-state vocabulary.
- Modify `src/main/kotlin/com/nanyin/nacos/search/services/CacheService.kt`: implement `Disposable`, expose non-suspending snapshot.
- Modify `src/main/kotlin/com/nanyin/nacos/search/psi/NacosKeyResolver.kt`: remove `runBlocking` and pooled-thread ownership.
- Modify `src/main/kotlin/com/nanyin/nacos/search/NacosSearchPlugin.kt`: delete periodic refresh job and timer.
- Modify `src/main/kotlin/com/nanyin/nacos/search/actions/RefreshCacheAction.kt`: run work inside the IntelliJ progress task, not an orphan scope.
- Test `src/test/kotlin/com/nanyin/nacos/search/services/CacheServiceTest.kt`.
- Test `src/test/kotlin/com/nanyin/nacos/search/psi/NacosKeyResolverTest.kt`.
- Test `src/test/kotlin/com/nanyin/nacos/search/NacosSearchPluginTest.kt`.

### Task 1: Add explicit PSI reference states

- [ ] **Step 1: Write the failing state test**

Add to `NacosKeyResolverTest.kt`:

```kotlin
@Test
fun `missing snapshot is unavailable rather than unresolved`() {
    val result = NacosKeyResolver.resolveStatus("db.url", null)
    assertEquals(ConfigReferenceStatus.UNAVAILABLE, result.status)
    assertTrue(result.hits.isEmpty())
}
```

- [ ] **Step 2: Run the test and verify failure**

```bash
./gradlew test --tests "com.nanyin.nacos.search.psi.NacosKeyResolverTest.missing snapshot is unavailable rather than unresolved"
```

Expected: compilation fails because `ConfigReferenceStatus` and `resolveStatus` do not exist.

- [ ] **Step 3: Add the minimal domain types**

Create `ConfigReferenceStatus.kt`:

```kotlin
package com.nanyin.nacos.search.psi

enum class ConfigReferenceStatus { RESOLVED, UNRESOLVED, UNAVAILABLE }

data class ConfigResolution(
    val status: ConfigReferenceStatus,
    val hits: List<NacosKeyResolver.KeyHit>
)
```

Add to `NacosKeyResolver`:

```kotlin
internal fun resolveStatus(key: String, index: KeyIndex?): ConfigResolution {
    if (index == null) return ConfigResolution(ConfigReferenceStatus.UNAVAILABLE, emptyList())
    val hits = index.hitsByKey[key].orEmpty()
    return ConfigResolution(
        if (hits.isEmpty()) ConfigReferenceStatus.UNRESOLVED else ConfigReferenceStatus.RESOLVED,
        hits
    )
}
```

- [ ] **Step 4: Run the focused test**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/nanyin/nacos/search/psi/ConfigReferenceStatus.kt src/main/kotlin/com/nanyin/nacos/search/psi/NacosKeyResolver.kt src/test/kotlin/com/nanyin/nacos/search/psi/NacosKeyResolverTest.kt
git commit -m "refactor: model unavailable config references"
```

### Task 2: Replace suspending PSI reads with immutable snapshots

- [ ] **Step 1: Write failing cache snapshot tests**

Add tests proving `configurationSnapshot()` returns immediately, excludes expired details, and changes only after a completed write:

```kotlin
@Test
fun `configuration snapshot never waits for persistence load`() {
    val snapshot = cacheService.configurationSnapshot("http://localhost:8848")
    assertNotNull(snapshot)
}
```

- [ ] **Step 2: Run `CacheServiceTest`**

Expected: FAIL because `configurationSnapshot` is undefined.

- [ ] **Step 3: Add the snapshot contract**

In `CacheService.kt` maintain:

```kotlin
@Volatile
private var detailSnapshot: Map<String, CacheEntry<NacosConfiguration>> = emptyMap()

fun configurationSnapshot(serverUrl: String?): List<NacosConfiguration> =
    detailSnapshot.asSequence()
        .filter { (key, entry) -> matchesServer(key, serverUrl) && !entry.isExpired() }
        .map { it.value.data }
        .toList()
```

Publish a copied map only after successful in-memory mutations; background persistence loading publishes complete batches, never a partially built map.

- [ ] **Step 4: Refactor `NacosKeyResolver`**

Remove `runBlocking`, `executeOnPooledThread`, and `rebuildBlocking`. Build `KeyIndex` from `configurationSnapshot()` in the `CacheService` application-owned scope, and keep `currentIndex()` a pure volatile read. Plan 04 connects an unavailable result to the namespace preheat coordinator.

- [ ] **Step 5: Run PSI and cache tests**

```bash
./gradlew test --tests "com.nanyin.nacos.search.psi.*" --tests "com.nanyin.nacos.search.services.CacheServiceTest"
```

Expected: PASS; no production `runBlocking` remains in `NacosKeyResolver.kt`.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/nanyin/nacos/search/services/CacheService.kt src/main/kotlin/com/nanyin/nacos/search/psi/NacosKeyResolver.kt src/test/kotlin/com/nanyin/nacos/search/services/CacheServiceTest.kt src/test/kotlin/com/nanyin/nacos/search/psi/NacosKeyResolverTest.kt
git commit -m "perf: keep PSI resolution off blocking cache paths"
```

### Task 3: Make cache and refresh tasks disposable

- [ ] **Step 1: Add failing disposal tests**

Assert `CacheService.dispose()` completes its load signal, cancels its scope, and rejects no reads; assert `NacosSearchPlugin.dispose()` has no auto-refresh job.

- [ ] **Step 2: Run focused tests**

Expected: FAIL because `CacheService` is not `Disposable` and plugin still schedules auto refresh.

- [ ] **Step 3: Implement disposal and remove timer**

Make `CacheService : Disposable`, retain `SupervisorJob` separately, and call `serviceJob.cancel()` in `dispose()`. Delete `autoRefreshJob`, `setupAutoRefresh()`, `stopAutoRefresh()`, and the call from `execute()` in `NacosSearchPlugin.kt`.

- [ ] **Step 4: Remove orphan refresh scope**

In `RefreshCacheAction.run`, use `runBlockingCancellable { plugin.refreshCache() }` inside `Task.Backgroundable`, check `indicator.checkCanceled()`, then schedule only the dialog on EDT. Do not create `CoroutineScope(Dispatchers.IO + SupervisorJob())`.

- [ ] **Step 5: Run tests and static search**

```bash
./gradlew test --tests "com.nanyin.nacos.search.NacosSearchPluginTest" --tests "com.nanyin.nacos.search.services.CacheServiceTest"
rg "autoRefreshJob|setupAutoRefresh|CoroutineScope\(Dispatchers.IO \+ SupervisorJob\(\)\)" src/main/kotlin/com/nanyin/nacos/search
```

Expected: tests PASS; `rg` returns no orphan refresh scope or periodic job.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/nanyin/nacos/search/NacosSearchPlugin.kt src/main/kotlin/com/nanyin/nacos/search/services/CacheService.kt src/main/kotlin/com/nanyin/nacos/search/actions/RefreshCacheAction.kt src/test/kotlin/com/nanyin/nacos/search/NacosSearchPluginTest.kt src/test/kotlin/com/nanyin/nacos/search/services/CacheServiceTest.kt
git commit -m "fix: bind cache and refresh work to platform lifecycles"
```
