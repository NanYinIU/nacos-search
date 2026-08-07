package com.nanyin.nacos.search.psi

/**
 * Decidability of a configuration placeholder against local cache + the
 * namespace summary index. Gutter icons map as:
 *
 * | status | icon | when |
 * | --- | --- | --- |
 * | [RESOLVED] | blue solid | detail-backed key hit, still within TTL |
 * | [STALE] | amber + clock | detail-backed key hit, past TTL but not deep-stale |
 * | [UNDECIDABLE] | gray hollow | data id known in the summary index (or visibility
 *   blocked) but no detail-backed key hit yet — content not loaded / index not
 *   caught up |
 * | [UNRESOLVED] | gray hollow | fresh complete namespace index proves the data id absent |
 * | [UNAVAILABLE] | gray hollow | no usable key index / summary yet (cold start,
 *   rebuild in flight with nothing to serve, partial index) |
 *
 * Upper boundary for leaving gray when the configuration **does** exist:
 * a detail must be in the cache for this access identity **and** the derived
 * key index must have published that snapshot **and** the daemon must
 * re-analyze. An async rebuild that publishes keys without a gutter pass leaves
 * the hollow icon stuck even though [RESOLVED] would succeed on the next ask.
 */
enum class ConfigReferenceStatus { RESOLVED, STALE, UNRESOLVED, UNDECIDABLE, UNAVAILABLE }

data class ConfigResolution(
    val status: ConfigReferenceStatus,
    val hits: List<NacosKeyResolver.KeyHit>,
    /**
     * True when the reference is [ConfigReferenceStatus.UNDECIDABLE] because a
     * visibility block hides the whole access identity or the evaluation
     * Namespace — distinct from the data-id-known-but-detail-missing case,
     * where the block reason would be a lie in the gutter tooltip
     * (issue #126).
     */
    val visibilityBlocked: Boolean = false
)
