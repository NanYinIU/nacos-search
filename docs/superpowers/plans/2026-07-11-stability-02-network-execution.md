# Network Execution and Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every Nacos query deterministic cancellation, error classification, retry behavior, and operation budgets.

**Architecture:** A focused `NacosRequestExecutor` owns HTTP execution policy; `NacosApiService` owns endpoint construction and response parsing. Full namespace loading returns completeness metadata instead of collapsing partial detail failures into success.

**Tech Stack:** Kotlin coroutines, IntelliJ `HttpRequests`, `HttpURLConnection`, Gson, JUnit 5.

---

## File Map

- Create `services/network/NacosRequestError.kt`: typed failures.
- Create `services/network/RequestPolicy.kt`: interactive, preheat, and diagnostic budgets.
- Create `services/network/NacosRequestExecutor.kt`: cancellable GET/POST executor.
- Create `models/NamespaceLoadResult.kt`: complete/partial/failed full-load result.
- Modify `services/NacosApiService.kt`: endpoint adapter only.
- Test `services/network/NacosRequestExecutorTest.kt` and `services/NacosApiServiceTest.kt`.

### Task 1: Define request errors and policies

- [ ] **Step 1: Write failing policy tests**

```kotlin
@Test
fun `interactive policy has bounded retries and duration`() {
    assertEquals(3_000, RequestPolicy.INTERACTIVE.connectTimeoutMs)
    assertEquals(8_000, RequestPolicy.INTERACTIVE.readTimeoutMs)
    assertEquals(15_000, RequestPolicy.INTERACTIVE.totalBudgetMs)
    assertEquals(2, RequestPolicy.INTERACTIVE.maxAttempts)
}
```

- [ ] **Step 2: Run the focused test**

Expected: compilation failure for missing `RequestPolicy`.

- [ ] **Step 3: Create exact types**

```kotlin
enum class RequestPolicy(val connectTimeoutMs: Int, val readTimeoutMs: Int, val totalBudgetMs: Long, val maxAttempts: Int) {
    INTERACTIVE(3_000, 8_000, 15_000, 2),
    PREHEAT(3_000, 8_000, 15_000, 1),
    DIAGNOSTIC(3_000, 8_000, 30_000, 2)
}

sealed class NacosRequestError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ConnectTimeout(cause: Throwable) : NacosRequestError("Connection timed out", cause)
    class ReadTimeout(cause: Throwable) : NacosRequestError("Read timed out", cause)
    class Connection(cause: Throwable) : NacosRequestError("Connection failed", cause)
    data class Authentication(val status: Int) : NacosRequestError("Authentication failed")
    data class RateLimited(val retryAfterMs: Long?) : NacosRequestError("Rate limited")
    data class Client(val status: Int, val body: String) : NacosRequestError("Client error $status")
    data class Server(val status: Int, val body: String) : NacosRequestError("Server error $status")
    class Protocol(message: String, cause: Throwable? = null) : NacosRequestError(message, cause)
}
```

- [ ] **Step 4: Run tests and commit**

```bash
git add src/main/kotlin/com/nanyin/nacos/search/services/network src/test/kotlin/com/nanyin/nacos/search/services/network
git commit -m "refactor: define bounded Nacos request policies"
```

### Task 2: Implement cancellable retries

- [ ] **Step 1: Add failing executor tests**

Cover: IO failure retries once after 250ms jitter; 401 does not retry in executor; 429 retries only within budget; 400 and JSON protocol errors do not retry; cancellation prevents the next attempt; Authorization and `accessToken` never appear in error text.

- [ ] **Step 2: Run executor tests**

Expected: FAIL because executor is missing.

- [ ] **Step 3: Implement executor**

Use `withTimeout(policy.totalBudgetMs)`, `delay(jitterProvider())`, and `ensureActive()` before each attempt. Inject transport, monotonic clock, and jitter provider for deterministic tests. Preserve the second-attempt `Connection: close` and `Accept-Encoding: identity` compatibility headers.

- [ ] **Step 4: Run executor tests**

Expected: PASS with virtual time; no `Thread.sleep` in `services/network`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/nanyin/nacos/search/services/network/NacosRequestExecutor.kt src/test/kotlin/com/nanyin/nacos/search/services/network/NacosRequestExecutorTest.kt
git commit -m "fix: make Nacos retries cancellable and bounded"
```

### Task 3: Adapt API methods and auth replay

- [ ] **Step 1: Add failing API tests**

Test that list/detail/namespaces use `INTERACTIVE`, preheat uses `PREHEAT`, a 401 refreshes Token and replays once inside the same budget, malformed JSON becomes `Protocol`, and non-JSON error bodies preserve status without leaking secrets.

- [ ] **Step 2: Run `NacosApiServiceTest`**

Expected: FAIL on current `requestJson` behavior and settings-driven 30/60-second timeouts.

- [ ] **Step 3: Route GET requests through executor**

Remove `requestJson` retry loop and `Thread.sleep`. Pass a `RequestPolicy` parameter through query methods with a default of `INTERACTIVE`. Keep publish POST non-retryable; it is outside the stability feature scope except for shared error mapping.

- [ ] **Step 4: Run API tests and commit**

```bash
./gradlew test --tests "com.nanyin.nacos.search.services.NacosApiServiceTest" --tests "com.nanyin.nacos.search.services.network.*"
git add src/main/kotlin/com/nanyin/nacos/search/services/NacosApiService.kt src/test/kotlin/com/nanyin/nacos/search/services/NacosApiServiceTest.kt
git commit -m "refactor: route Nacos queries through request executor"
```

### Task 4: Model partial namespace loads

- [ ] **Step 1: Add failing full-load tests**

Test 500 metadata items with 12 detail failures returns `PARTIAL`, `expectedCount = 500`, 488 configurations, and 12 failures; a list failure returns `FAILED`; cancellation throws rather than returning partial success.

- [ ] **Step 2: Add the result model**

```kotlin
enum class DatasetCompleteness { COMPLETE, PARTIAL, FAILED }

data class NamespaceLoadResult(
    val completeness: DatasetCompleteness,
    val expectedCount: Int,
    val configurations: List<NacosConfiguration>,
    val failures: List<ConfigLoadFailure>
)

data class ConfigLoadFailure(val dataId: String, val group: String, val error: NacosRequestError)
```

- [ ] **Step 3: Change `getAllConfigurations`**

Return `Result<NamespaceLoadResult>`, keep concurrency at 8, use supervisor semantics for individual details, and preserve cancellation by rethrowing `CancellationException`.

- [ ] **Step 4: Run tests and commit**

```bash
./gradlew test --tests "com.nanyin.nacos.search.services.NacosApiServiceTest"
git add src/main/kotlin/com/nanyin/nacos/search/models/NamespaceLoadResult.kt src/main/kotlin/com/nanyin/nacos/search/services/NacosApiService.kt src/test/kotlin/com/nanyin/nacos/search/services/NacosApiServiceTest.kt
git commit -m "feat: preserve partial namespace load semantics"
```

