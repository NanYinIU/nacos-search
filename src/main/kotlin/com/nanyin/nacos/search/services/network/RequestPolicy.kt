package com.nanyin.nacos.search.services.network

/**
 * Bounded request profiles for every Nacos query context.
 *
 * Each policy fixes connect timeout, read timeout, total execution budget,
 * and maximum attempts (including the first). Both profiles retry an
 * idempotent read at most once; a diagnostic differs only in being allowed
 * a longer total budget for the same work.
 *
 * A policy deliberately cannot express "no retry". Per ADR-0021 retry is
 * classified by operation kind at the transport seam — writes and login get
 * none because [NacosRequestExecutor.post] never retries, not because a
 * caller selected a no-retry profile. An earlier PREHEAT profile existed for
 * background namespace preheat and was removed: it let a caller quietly
 * withhold a retry the operation kind was entitled to.
 */
enum class RequestPolicy(
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val totalBudgetMs: Long,
    val maxAttempts: Int
) {
    INTERACTIVE(3_000, 8_000, 15_000, 2),
    DIAGNOSTIC(3_000, 8_000, 30_000, 2)
}
