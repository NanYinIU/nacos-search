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
     * [coordinateNamespaceId] is the Namespace of the 配置坐标 when the caller
     * holds one (issue #190). Defaults to the payload tenant only for call
     * sites that have a body and no coordinate — that path cannot distinguish
     * tenant-less non-public entries; prefer the overload that takes a
     * coordinate whenever one is available.
     *
     * The one place *that* question is answered, because a site that overrode a
     * configuration it did not name would extract it by rules nobody wrote for
     * it — and a site whose override silently missed the one it did name would
     * fall back to the very reading it is contradicting.
     */
    fun appliesTo(
        config: NacosConfiguration,
        coordinateNamespaceId: String? = config.tenantId
    ): Boolean =
        namesDataId(config.dataId) &&
            (group == null || config.group == group) &&
            (namespaceId == null ||
                canonicalNamespace(coordinateNamespaceId) == canonicalNamespace(namespaceId))

    /**
     * True when this site's declaration is about [dataId].
     *
     * Deliberately weaker than [appliesTo], and the difference is the whole
     * distinction #172 drew. [appliesTo] chooses which cached *body* to re-read,
     * so it needs the full coordinate. This one answers the question that needs
     * no body at all — whether any body under this data id's 运行时格式 could
     * ever make a placeholder resolve — where the group and Namespace have
     * nothing to narrow.
     */
    fun namesDataId(dataId: String): Boolean = dataId == this.dataId

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
 * this is the configuration that override names (#173), and otherwise the one
 * its data id decides (#171). The configuration's own 声明格式 takes no part in
 * either, because no runtime consults it.
 *
 * A refusal no longer has to read as "no keys" here, because the caller that
 * would have been misled by that reading now asks [runtimeFormatRefusal] first
 * and reaches 格式不参与解析 without ever looking at a body (#172).
 *
 * What is left flattens deliberately. Every caller of this one has a body in
 * hand and wants the keys in it, and none of them turns the empty map into an
 * absence claim: the 派生 key 索引 contributes nothing for a refusal, the detail
 * panel's own gutter markers have no lines to mark, and the marker's lazy-load
 * navigation asks only where to put the caret and already answers
 * [ConfigKeyExtractor.LINE_NOT_FOUND] when it does not know — a position it
 * degrades on, never a claim about the key.
 *
 * Per-reference extraction is computed on demand and never cached (#173). If it
 * ever measures as a cost, key a cache by data id plus 运行时格式 — do not build
 * one up front.
 */
internal fun keysUnderRuntimeFormat(
    config: NacosConfiguration,
    override: ReferenceFormatOverride? = null,
    /**
     * Coordinate Namespace when the caller holds one (issue #190). Passed
     * through to [ReferenceFormatOverride.appliesTo] so a tenant-less body
     * still re-reads under the site's declared format for its coordinate.
     */
    coordinateNamespaceId: String? = config.tenantId
): Map<String, ConfigKeyExtractor.KeyLocation> {
    val format = override?.takeIf { it.appliesTo(config, coordinateNamespaceId) }?.format
        ?: RuntimeFormatDecision.forDataId(config.dataId)
    return when (val extraction = ConfigKeyExtractor.extract(config.content, format)) {
        is KeyExtraction.Extracted -> extraction.keys
        is KeyExtraction.NotExtractable -> emptyMap()
    }
}

/**
 * Why a configuration named [dataId] contributes no keys, or null when its
 * 运行时格式 is one the plugin parses.
 *
 * Answered without a body. That is not a shortcut: whether the body is cached,
 * listed, or has never been fetched changes nothing about whether a placeholder
 * could ever resolve against it, and waiting for a body before saying so is
 * exactly what kept the plugin sweeping the server forever for a format it will
 * never parse (#172).
 *
 * [override] is what keeps the two slices from contradicting each other. A
 * reference site that declares a type is naming the 运行时格式 the runtime will
 * read its configuration under, so it decides this refusal too — which is how a
 * data id with no recognizable suffix stops reaching the terminal state once a
 * site declares one, the whole reason a developer writes the declaration (#173).
 * It is matched on the data id alone: the question has no body for a group or
 * Namespace to select between.
 */
internal fun runtimeFormatRefusal(
    dataId: String,
    override: ReferenceFormatOverride? = null
): KeyExtraction.Reason? = ConfigKeyExtractor.refusalFor(
    override?.takeIf { it.namesDataId(dataId) }?.format ?: RuntimeFormatDecision.forDataId(dataId)
)
