# Jenkins CI (beijing)

CI for this repo runs on the self-hosted Jenkins at `https://jenkins.gorsen.icu`
(host `beijing`). GitHub Actions was removed; push to `master` triggers the
`nacos-search` Pipeline job via the org GitHub App `Jenkins CI For Nanyiniu`.

## What runs

| Stage | What | Notes |
|---|---|---|
| Checkout | shallow clone `master` | credentials `jenkins-github-app`; HTTP/1.1 + retry |
| Unit tests | `compileKotlin` + `test` + `testVintage` | sibling Docker, same flags as old GHA |
| Live smoke V1 | Nacos `v2.5.3` + `LiveSmokeTest.V1*` | sibling Docker network; publish/Namespace/search/detail/refresh |
| Live smoke V3 | Nacos `v3.2.3` + `LiveSmokeTest.V3*` | Admin auth disabled; same browse/publish path + generation resolve |

ADR-0031 release-gate invariants map to one authoritative suite each — see
[`release-gate.md`](./release-gate.md).

Heavy Gradle/IDE work runs in **sibling** Docker containers (host
`docker.sock`), not inside the Jenkins 1.5G cgroup. Mounts use the **host**
path `/opt/jenkins/home/workspace/nacos-search` (never `$WORKSPACE` alone for
`-v`).

## Host constraints (validated)

| Resource | Value |
|---|---|
| RAM | 3.6G |
| Swap | `/swapfile-ci` 3G (required for full IntelliJ test suite) |
| Build container | `--memory=2500m --memory-swap=3500m` |
| Test JVM | maxHeap 1024m + MaxMetaspace 384m via `gradle-caches/init.d/ci-heap.init.gradle` (and `build.gradle.kts` when `CI`/`JENKINS_URL` is set). **Do not** add `-XX:+UseSerialGC` — IntelliJ Platform already injects `-XX:+UseG1GC` from `idea64.vmoptions`; two collectors abort every Gradle Test Executor with `Conflicting collector combinations`. |
| Gradle dist | Tencent mirror (pipeline `sed` before `./gradlew`) |
| Cache | `/opt/jenkins/home/gradle-caches` (~5G after first warm) |
| TestApplication dispose | Skipped on CI (`CI=true` / `JENKINS_URL`) — see below |

Without swap, `:test` dies with **exit 137** (OOM) even at 2.5G. A 1.2G
container OOMs the Gradle daemon during `compileKotlin`.

### `@TestApplication` dispose timeout

JUnit5 `@TestApplication` closes the shared IDE application with a **hard-coded
20s** `timeoutRunBlocking` (10s of which can be `waitForAppLeakingThreads`).
After a full ~900-test suite on this host the dispose step often exceeds that
budget: every test method has already passed, then the engine reports

```text
JUnit Jupiter > executionError FAILED
TimeoutCancellationException: Timed out waiting for 20000 ms
  at TestApplicationResource.close
```

The unit-test container exports `CI=true` (and `JENKINS_URL`). `build.gradle.kts`
then sets

`intellij.testFramework.junit5.skip.test.application.dispose=true`

— the same escape hatch JetBrains uses under Bazel. The Gradle test worker JVM
exits after the suite; local runs without those env vars still dispose and run
leak checks.

Nacos containers need `--security-opt seccomp=unconfined` on this CentOS 7
host — otherwise embedded Derby fails with `pwrite ... Operation not permitted`.

During unit tests the pipeline briefly `docker stop linkding` to free ~300MB,
then restarts it in `post`.

## Job

- Name: `nacos-search`
- Definition: inline `CpsFlowDefinition` (mirror of repo root `Jenkinsfile` —
  **must be re-pasted into the job after every `Jenkinsfile` change**; the
  pipeline does **not** load `Jenkinsfile` from the checkout). Until that sync
  happens, Checkout keeps rewriting `gradle-caches/init.d/ci-heap.init.gradle`
  from the stale inline script — historically that re-injected
  `-XX:+UseSerialGC` and aborted every Test Executor next to IntelliJ's G1.
  `build.gradle.kts` now strips collector flags from direct `jvmArgs` as a
  backstop once the repo change is on the built branch.
- Trigger: `githubPush()` (registers after the first manual build); job checks
  out `master` only (no PR multi-branch)
- Concurrency: disabled — do not run alongside heavy `stillness-backend` builds

## Ops

```bash
# Logs / poke
docker logs jenkins --since 10m | grep -iE 'PushEvent|Poked|nacos-search'

# Confirm swap (required)
swapon --show | grep swapfile-ci

# Manual build (needs admin password)
# Prefer UI: https://jenkins.gorsen.icu/job/nacos-search/build
```

First build after a cold cache downloads the IntelliJ Platform SDK and is slow
(many minutes). Subsequent builds reuse `/opt/jenkins/home/gradle-caches`.

## Out of scope on this host

- `verifyPlugin` (multi-IDE download — too heavy)
- Parallel stages / concurrent jobs (OOM risk)
- PR multi-branch pipeline (optional follow-up)

## Migration notes (from GHA)

| GHA | Jenkins beijing |
|---|---|
| 3 parallel jobs | 1 sequential pipeline |
| `ubuntu-latest` free RAM | 3.6G + 3G swap |
| actions/cache | host `gradle-caches` volume |
| services.gradle.org | Tencent mirror in pipeline |
