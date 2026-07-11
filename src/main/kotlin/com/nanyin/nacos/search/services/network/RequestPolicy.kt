package com.nanyin.nacos.search.services.network

/**
 * Bounded request profiles for every Nacos query context.
 *
 * Each policy fixes connect timeout, read timeout, total execution budget,
 * and maximum attempts (including the first). Interactive queries get one
 * retry; preheat gets none; diagnostics get a longer budget.
 */
enum class RequestPolicy(
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val totalBudgetMs: Long,
    val maxAttempts: Int
) {
    INTERACTIVE(3_000, 8_000, 15_000, 2),
    PREHEAT(3_000, 8_000, 15_000, 1),
    DIAGNOSTIC(3_000, 8_000, 30_000, 2)
}
