# Stability Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the non-UI stability foundation for Nacos Search without EDT blocking, uncontrolled refresh jobs, cross-identity cache exposure, or ambiguous partial/stale results.

**Architecture:** Execute four independently testable plans in order. Shared result types and lifecycle rules land first; the network executor and cache model then provide stable primitives; search orchestration consumes those primitives last. Visible UI changes are blocked behind a separate approval package containing exact file locations and complete mockups.

**Tech Stack:** Kotlin 2.0.21, Java 17, IntelliJ Platform 243+, kotlinx.coroutines, Gson, JUnit 5, Mockito Kotlin, Gradle 9.

---

## Plan Set

1. [Lifecycle and PSI safety](./2026-07-11-stability-01-lifecycle-psi.md)
2. [Network execution and compatibility](./2026-07-11-stability-02-network-execution.md)
3. [Identity-aware cache and freshness](./2026-07-11-stability-03-cache-model.md)
4. [Search, full-index refresh, and UI approval gate](./2026-07-11-stability-04-search-orchestration.md)

## Required Order

- Plan 01 owns lifecycle primitives and removes periodic refresh.
- Plan 02 owns request budgets, retry classification, cancellation, and partial full-load results.
- Plan 03 owns access identity, cache timestamps, seven-day stale limit, atomic persistence, and migration.
- Plan 04 owns request generations, namespace-index single-flight/cooldown, front-end 15-second cutoff, and the UI approval package.

Do not implement Plan 04 UI sections until the approval artifacts named there exist and the user has explicitly approved them.

## Release Verification

Run after all four plans:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
./gradlew clean test compileKotlin compileTestKotlin verifyPlugin
```

Expected: all tests pass, compilation succeeds, and plugin verification reports no compatibility errors for configured IDE targets.

