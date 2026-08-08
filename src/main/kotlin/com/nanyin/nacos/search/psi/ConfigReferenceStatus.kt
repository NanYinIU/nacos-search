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
 * | [FORMAT_NOT_PARSED] | gray, no arrow | the plugin reads no keys from the declared
 *   data id's 运行时格式 — terminal, and nowhere to jump |
 *
 * Every state but the last is transitional: more data, or a later pass, can
 * change the answer. [FORMAT_NOT_PARSED] cannot, which is the whole reason it
 * exists as a state of its own rather than borrowing [UNDECIDABLE].
 *
 * Upper boundary for leaving gray when the configuration **does** exist:
 * a detail must be in the cache for this access identity **and** the derived
 * key index must have published that snapshot **and** the daemon must
 * re-analyze. An async rebuild that publishes keys without a gutter pass leaves
 * the hollow icon stuck even though [RESOLVED] would succeed on the next ask.
 */
enum class ConfigReferenceStatus {
    RESOLVED,
    STALE,
    UNRESOLVED,
    UNDECIDABLE,
    UNAVAILABLE,

    /**
     * 格式不参与解析 — the plugin reads no keys from the 运行时格式 the
     * placeholder's declared data id decides, so no configuration body could
     * make this placeholder resolve (issue #172).
     *
     * "The plugin reads none", not "no runtime parses it": xml is a format both
     * runtimes parse and this one does not yet
     * ([KeyExtraction.Reason.PARSER_NOT_IMPLEMENTED]), and it belongs here for
     * the same reason the formats no runtime parses do — the gutter can promise
     * nothing about it, and asking the server again will not change that.
     */
    FORMAT_NOT_PARSED
}

/**
 * Whether a gutter pass for this status may kick off a background namespace-index
 * refresh. Resolved-but-stale markers render amber without network; only
 * unresolved / undecidable / unavailable states need a sweep (issue #145).
 *
 * [ConfigReferenceStatus.FORMAT_NOT_PARSED] is terminal, so a sweep can only
 * confirm what is already known. Borrowing [ConfigReferenceStatus.UNDECIDABLE]
 * for it is what had the plugin asking the server about a format it will never
 * parse for the life of the IDE (issue #172).
 */
fun ConfigReferenceStatus.mayRequestBackgroundRefresh(): Boolean = when (this) {
    ConfigReferenceStatus.UNRESOLVED,
    ConfigReferenceStatus.UNDECIDABLE,
    ConfigReferenceStatus.UNAVAILABLE -> true
    ConfigReferenceStatus.RESOLVED,
    ConfigReferenceStatus.STALE,
    ConfigReferenceStatus.FORMAT_NOT_PARSED -> false
}

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
    val visibilityBlocked: Boolean = false,
    /**
     * Why the 运行时格式 contributes no keys, when the status is
     * [ConfigReferenceStatus.FORMAT_NOT_PARSED].
     *
     * Carried at full width rather than collapsed here: the outcome is the same
     * for every reason, but the user's next action is not, and how many of
     * these read as one explanation is a presentation decision the gutter makes
     * (issue #172).
     */
    val formatRefusal: KeyExtraction.Reason? = null
)
