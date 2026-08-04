package com.nanyin.nacos.search.psi

/**
 * Decidability of a configuration placeholder against local cache + the
 * namespace summary index.
 *
 * - [RESOLVED] / [STALE]: a detail hit exists (fresh or aged).
 * - [UNRESOLVED]: a complete namespace index proves the referenced data id is
 *   absent, so the reference is a confident miss.
 * - [UNDECIDABLE]: the data id is present in the summary index but its detail
 *   has not been fetched, so the key cannot be proven present or absent
 *   (issue #52 / ADR-0041).
 * - [UNAVAILABLE]: no reliable snapshot exists yet (cold start / partial index).
 */
enum class ConfigReferenceStatus { RESOLVED, STALE, UNRESOLVED, UNDECIDABLE, UNAVAILABLE }

data class ConfigResolution(
    val status: ConfigReferenceStatus,
    val hits: List<NacosKeyResolver.KeyHit>
)
