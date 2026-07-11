# Identity-Aware Cache and Freshness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent cross-identity cache exposure and represent source, freshness, completeness, and retention independently.

**Architecture:** Typed cache coordinates replace stringly keyed calls. Persisted entries carry wall-clock fetch time and format version; live entries additionally carry monotonic creation time. File writes are atomic, old unscoped entries are discarded idempotently, and namespace indexes publish only when complete.

**Tech Stack:** Kotlin, Gson, `java.nio.file`, coroutines, JUnit 5.

---

## File Map

- Create `models/AccessIdentity.kt` and `models/DatasetState.kt`.
- Create `services/CacheCoordinate.kt`.
- Modify `services/CacheService.kt` and `services/CacheFileStorage.kt`.
- Modify call sites in `NacosApiService.kt`, `NacosSearchService.kt`, `NacosSearchPlugin.kt`, and `NacosSearchWindow.kt` only to pass typed coordinates; behavior changes land in Plan 04.
- Test `CacheServiceTest.kt`, `CacheFileStorageTest.kt`, and `NacosSettingsTest.kt`.

### Task 1: Add access identity and dataset state

- [ ] **Step 1: Write failing identity tests**

Assert same environment/auth mode/username yields equal identity; changing any field differs; password and Token do not affect equality; blank username maps to anonymous.

- [ ] **Step 2: Implement exact types**

`DatasetCompleteness` is created by Plan 02 in `NamespaceLoadResult.kt`; reuse it here rather than defining a second enum.

```kotlin
data class AccessIdentity(val serverId: String, val authMode: AuthMode, val principal: String) {
    companion object {
        fun of(serverId: String, authMode: AuthMode, username: String) =
            AccessIdentity(serverId, authMode, username.trim().ifBlank { "<anonymous>" })
    }
}

enum class DataSource { REMOTE, CACHE }
enum class DataFreshness { FRESH, STALE, UNKNOWN }
data class DatasetState(val source: DataSource, val freshness: DataFreshness, val completeness: DatasetCompleteness, val fetchedAtMillis: Long?)
```

- [ ] **Step 3: Run tests and commit**

```bash
git add src/main/kotlin/com/nanyin/nacos/search/models src/test/kotlin/com/nanyin/nacos/search/models
git commit -m "refactor: model cache identity and dataset state"
```

### Task 2: Replace cache keys with typed coordinates

- [ ] **Step 1: Add failing key-isolation tests**

Test list, detail, and namespace index entries written by identity A are invisible to identity B; anonymous is isolated; username and auth-mode switches cannot use stale fallback from the old identity.

- [ ] **Step 2: Add coordinates**

```kotlin
sealed interface CacheCoordinate { val identity: AccessIdentity; val serverUrl: String; val namespaceId: String }
data class NamespaceIndexCoordinate(override val identity: AccessIdentity, override val serverUrl: String, override val namespaceId: String) : CacheCoordinate
data class DetailCoordinate(override val identity: AccessIdentity, override val serverUrl: String, override val namespaceId: String, val dataId: String, val group: String) : CacheCoordinate
data class ListPageCoordinate(override val identity: AccessIdentity, override val serverUrl: String, override val namespaceId: String, val requestKey: String) : CacheCoordinate
```

- [ ] **Step 3: Change public cache signatures**

Replace positional server/namespace parameters with the coordinate types. Centralize normalized serialization in `CacheCoordinate.storageKey()` and never include password or Token.

- [ ] **Step 4: Update callers, run compile/tests, commit**

```bash
./gradlew compileKotlin compileTestKotlin
./gradlew test --tests "com.nanyin.nacos.search.services.CacheServiceTest"
git add src/main/kotlin/com/nanyin/nacos/search/services src/main/kotlin/com/nanyin/nacos/search/psi src/main/kotlin/com/nanyin/nacos/search/NacosSearchPlugin.kt src/test/kotlin/com/nanyin/nacos/search/services
git commit -m "fix: isolate cache entries by access identity"
```

### Task 3: Enforce TTL and seven-day maximum stale age

- [ ] **Step 1: Add clock-driven tests**

Use injected monotonic and wall clocks. Verify fresh before five-minute TTL, stale afterward, unavailable after seven days, manual refresh failure marks displayed state stale even before TTL, and detail expiry never expires namespace index.

- [ ] **Step 2: Extend cache entry timestamps**

Persist `fetchedAtEpochMillis`; keep `createdAtMonotonicMillis` in memory. Add `readFresh`, `readStale`, and `readAnyVisible` APIs so callers cannot accidentally request unlimited stale data with a Boolean flag.

- [ ] **Step 3: Enforce independent dimensions**

Namespace, detail, and list caches share the configured TTL value but calculate expiry per entry. `putNamespaceIndex` accepts only `DatasetCompleteness.COMPLETE`; partial loads may call `putConfigDetail` for successful details but cannot refresh index timestamps.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew test --tests "com.nanyin.nacos.search.services.CacheServiceTest"
git add src/main/kotlin/com/nanyin/nacos/search/services/CacheService.kt src/test/kotlin/com/nanyin/nacos/search/services/CacheServiceTest.kt
git commit -m "fix: bound stale cache visibility to seven days"
```

### Task 4: Make persistence atomic and migration idempotent

- [ ] **Step 1: Add failing storage tests**

Cover interrupted temporary writes, atomic replacement, corrupt single-file quarantine, startup with undeletable legacy files, and repeated migration. Verify startup completes in every case.

- [ ] **Step 2: Implement atomic store**

Write JSON to a sibling `.tmp`, flush and close, then call `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` with a `REPLACE_EXISTING` fallback when atomic moves are unsupported. Delete leftover `.tmp` files during background cleanup.

- [ ] **Step 3: Version the format and discard legacy entries**

Add `formatVersion = 2` to persisted envelopes. A missing/older version is invisible and scheduled for best-effort deletion. Migration errors are logged and retried later; they never fail `loadCompleted`.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew test --tests "com.nanyin.nacos.search.services.CacheFileStorageTest" --tests "com.nanyin.nacos.search.services.CacheServiceTest"
git add src/main/kotlin/com/nanyin/nacos/search/services/CacheFileStorage.kt src/main/kotlin/com/nanyin/nacos/search/services/CacheService.kt src/test/kotlin/com/nanyin/nacos/search/services
git commit -m "fix: persist cache entries atomically"
```
