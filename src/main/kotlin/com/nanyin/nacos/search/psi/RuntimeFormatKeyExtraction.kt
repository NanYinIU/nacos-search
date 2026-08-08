package com.nanyin.nacos.search.psi

import com.nanyin.nacos.search.models.NacosConfiguration

/**
 * The 运行时格式 a reference site imposes on the configuration coordinate it
 * names.
 *
 * It exists as a value of its own because it is the whole of what a code
 * context contributes to extraction, and the 派生 key 索引 must not learn about
 * it: the index is built once per 缓存快照 over every cached configuration and
 * has no code context, and the same configuration can need extracting two ways
 * for two sites that declare different types. So the index stays built from
 * what a configuration decides about itself, and this is resolved per reference
 * against one coordinate (#173).
 *
 * The coordinate is as narrow as the site wrote it. [dataId] is always present
 * — a declaration with none names nothing to apply itself to — while [group]
 * and [namespaceId] narrow it further only where the site declared them, the
 * same way resolution already treats those two as hints and the data id as a
 * hard constraint. A site that declares neither is asserting something about
 * every copy of that data id it can reach, which is exactly as much as it knows.
 */
data class ReferenceFormatOverride(
    val dataId: String,
    val group: String?,
    val namespaceId: String?,
    val format: RuntimeConfigFormat
) {
    /**
     * True when [config] is the configuration this site named.
     *
     * The one place that question is answered, because a site that overrode a
     * configuration it did not name would extract it by rules nobody wrote for
     * it — and a site whose override silently missed the one it did name would
     * fall back to the very reading it is contradicting.
     */
    fun appliesTo(config: NacosConfiguration): Boolean =
        config.dataId == dataId &&
            (group == null || config.group == group) &&
            (namespaceId == null || canonicalNamespace(config.tenantId) == canonicalNamespace(namespaceId))

    private fun canonicalNamespace(namespaceId: String?): String =
        namespaceId?.takeIf { it.isNotBlank() && it != "public" } ?: ""
}

/**
 * The override this code context raises, or null when it raises none.
 *
 * Null in three cases that are one case: the site names no configuration to
 * apply a declaration to, it declares nothing, or it declares the very format
 * the data id already decides. In the last one the index holds exactly these
 * definitions already, so re-deriving them per reference would buy nothing —
 * which is what keeps the per-reference path off the ordinary hot path.
 */
internal fun NacosCodeContext.runtimeFormatOverride(): ReferenceFormatOverride? {
    val dataId = dataId?.takeIf { it.isNotBlank() } ?: return null
    val declared = RuntimeFormatDecision.forReference(dataId, declaredType)
    if (declared == RuntimeFormatDecision.forDataId(dataId)) return null
    return ReferenceFormatOverride(
        dataId = dataId,
        group = group?.takeIf { it.isNotBlank() },
        namespaceId = namespaceId?.takeIf { it.isNotBlank() },
        format = declared
    )
}

/**
 * The keys [config] contributes, read under its 运行时格式 — [override]'s where
 * this is the configuration that override names, and otherwise the one its data
 * id decides.
 *
 * Half of what was transitional here is settled: the format no longer comes
 * from the configuration's 声明格式, which no runtime consults, but from
 * [RuntimeFormatDecision] (#171), and a reference site may now decide it
 * instead (#173).
 *
 * The other half is not. A refusal still reads as "no keys" — the reading
 * [KeyExtraction] exists to prevent — until it reaches its own terminal
 * resolution state, which, unlike an empty key set, never asks for a background
 * Namespace index refresh (#166). This stays one function so that when it does,
 * there is one place to change rather than one per caller; it is the module's
 * only flattening of a refusal, and there is deliberately no general
 * `keysOrEmpty(content, format)` beside it.
 *
 * Per-reference extraction is computed on demand and never cached (#173). If it
 * ever measures as a cost, key a cache by data id plus 运行时格式 — do not build
 * one up front.
 */
internal fun keysUnderRuntimeFormat(
    config: NacosConfiguration,
    override: ReferenceFormatOverride? = null
): Map<String, ConfigKeyExtractor.KeyLocation> {
    val format = override?.takeIf { it.appliesTo(config) }?.format
        ?: RuntimeFormatDecision.forDataId(config.dataId)
    return when (val extraction = ConfigKeyExtractor.extract(config.content, format)) {
        is KeyExtraction.Extracted -> extraction.keys
        is KeyExtraction.NotExtractable -> emptyMap()
    }
}
