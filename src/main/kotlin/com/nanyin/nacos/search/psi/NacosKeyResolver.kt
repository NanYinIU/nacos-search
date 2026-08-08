package com.nanyin.nacos.search.psi

import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.CacheSnapshot

/**
 * Derives the `@NacosValue` key index from a [CacheSnapshot] and resolves a
 * placeholder key against it.
 *
 * Pure: every function here takes the snapshot and index it works from, holds
 * no state, and starts no work. [NacosKeyIndexService] owns the cached index
 * and the scope its rebuilds run in.
 *
 * Key extraction stays here, in the code-navigation layer. Reading keys out of
 * configuration content is configuration-format knowledge, and moving the
 * derivation into the cache would make the cache module depend on this one.
 *
 * Results are ordered so the most relevant definition (active namespace >
 * public > others) comes first, matching how a developer would expect
 * "go to declaration" to behave when the same key exists in several places.
 */
object NacosKeyResolver {

    /**
     * A key definition as the index holds it: timeless. Its freshness
     * boundaries are absolute instants, so an index built once stays usable as
     * the clock moves and nothing in it can report a freshness of its own.
     *
     * [namespaceId] is required and is the canonical Namespace of the 配置坐标
     * the detail was written under, not the payload tenant (issue #190). Callers
     * take it from [CacheService.CachedConfiguration.namespaceId]; there is no
     * payload-tenant default so a forgotten argument cannot reintroduce that
     * routing class.
     */
    data class KeyDefinition(
        val config: NacosConfiguration,
        val location: ConfigKeyExtractor.KeyLocation,
        val freshUntilMillis: Long = Long.MAX_VALUE,
        val deepStaleAtMillis: Long = Long.MAX_VALUE,
        val namespaceId: String
    ) {
        fun judgedAt(asOfMillis: Long): KeyHit = KeyHit(
            config,
            location,
            when {
                asOfMillis <= freshUntilMillis -> CacheService.DetailFreshness.FRESH
                asOfMillis <= deepStaleAtMillis -> CacheService.DetailFreshness.STALE
                else -> CacheService.DetailFreshness.DEEP_STALE
            },
            namespaceId
        )
    }

    /**
     * A key definition judged against one as-of instant. A hit cannot be built
     * without one, which is what keeps every hit in a single decision from
     * straddling a freshness boundary.
     *
     * [namespaceId] is required: the coordinate Namespace (issue #190), not the
     * payload tenant — so cross-Namespace filtering, ranking, and navigation
     * stay correct when the response body omits tenant.
     */
    data class KeyHit(
        val config: NacosConfiguration,
        val location: ConfigKeyExtractor.KeyLocation,
        val freshness: CacheService.DetailFreshness,
        val namespaceId: String
    )

    /**
     * The derived index, stamped with the snapshot version it was built from
     * and the visibility state it was built under. The version — not a
     * modification counter, a clock, or an object identity — is the whole
     * content-staleness rule; [visibilityCompatibleWith] is the extra gate
     * that stops an index built while a block hid definitions from serving a
     * snapshot that hides different ones (issue #126).
     */
    data class KeyIndex(
        val accessIdentity: AccessIdentity,
        val version: Long,
        val definitionsByKey: Map<String, List<KeyDefinition>>,
        val dataIdsByNamespace: Map<String, Set<String>> = emptyMap(),
        /** True when built under an identity-wide authentication block. */
        val accessBlocked: Boolean = false,
        /** Canonical Namespace ids hidden by authorization blocks at build time. */
        val blockedNamespaces: Set<String> = emptySet()
    ) {
        /**
         * True when [snapshot]'s visibility state is the one this index was
         * derived under. The snapshot's payload views are already filtered to
         * the complement of its hidden set, so an index built from it cannot
         * hold a definition the snapshot hides; an index built under a
         * different state may, and must not be served (issue #126).
         */
        fun visibilityCompatibleWith(snapshot: CacheSnapshot): Boolean =
            accessBlocked == snapshot.isAccessBlocked &&
                blockedNamespaces == snapshot.blockedNamespaces
    }

    fun buildIndex(snapshot: CacheSnapshot): KeyIndex {
        val cached = snapshot.configurations
        return KeyIndex(
            accessIdentity = snapshot.identity,
            version = snapshot.version,
            definitionsByKey = cached
                .asSequence()
                .flatMap { cachedConfig ->
                    keysUnderRuntimeFormat(cachedConfig.configuration).values.asSequence().map { loc ->
                        loc.key to KeyDefinition(
                            cachedConfig.configuration,
                            loc,
                            cachedConfig.freshUntilMillis,
                            cachedConfig.deepStaleAtMillis,
                            namespaceId = cachedConfig.namespaceId
                        )
                    }
                }
                .groupBy({ it.first }, { it.second }),
            dataIdsByNamespace = cached
                .asSequence()
                .filter { it.configuration.dataId.isNotBlank() }
                .groupBy { normalizeNamespaceId(it.namespaceId) }
                .mapValues { (_, entries) -> entries.mapTo(mutableSetOf()) { it.configuration.dataId } },
            accessBlocked = snapshot.isAccessBlocked,
            blockedNamespaces = snapshot.blockedNamespaces
        )
    }

    /**
     * Resolves [key] against [index], judging every hit at [snapshot]'s as-of
     * instant.
     *
     * @param activeNamespaceId when non-null, hits in this namespace sort first
     * @param preferredDataId when present and any hit matches, hard-filter to those
     *   hits (typical `@NacosPropertySource`); when it yields zero matches the
     *   three-way declared-Data-ID filter decides soft discovery vs no hits
     *   (issue #192) — see [preferDataIdDefinitions]
     * @param formatOverride when present, the 运行时格式 the reference site
     *   declared for the one configuration it names — see [scopedDefinitions]
     */
    fun resolve(
        key: String,
        index: KeyIndex?,
        snapshot: CacheSnapshot,
        activeNamespaceId: String? = null,
        preferredGroup: String? = null,
        preferredNamespaceId: String? = null,
        preferredDataId: String? = null,
        allowCrossNamespace: Boolean = true,
        formatOverride: ReferenceFormatOverride? = null
    ): List<KeyHit> {
        if (key.isBlank() || index == null) return emptyList()
        // A blocked identity hides every cached definition; an index derived
        // under a different visibility state may hold definitions the snapshot
        // hides and must not serve them (issue #126).
        if (snapshot.isAccessBlocked || !index.visibilityCompatibleWith(snapshot)) return emptyList()
        val scoped = scopedDefinitions(
            index, snapshot, key, activeNamespaceId, allowCrossNamespace, preferredDataId, formatOverride
        )
        return scoped
            .sortedWith(definitionComparator(activeNamespaceId, preferredGroup, preferredNamespaceId, preferredDataId))
            .map { it.judgedAt(snapshot.asOfMillis) }
    }

    fun resolveCurrentState(
        key: String,
        index: KeyIndex?,
        snapshot: CacheSnapshot,
        activeNamespaceId: String? = null,
        preferredDataId: String? = null,
        allowCrossNamespace: Boolean = true,
        formatOverride: ReferenceFormatOverride? = null
    ): ConfigResolution {
        if (key.isBlank()) return ConfigResolution(ConfigReferenceStatus.UNRESOLVED, emptyList())
        // A blocked identity returns no cached key hits, and absence is
        // undecidable rather than a confident miss: the payload may exist but
        // is hidden (issue #126).
        if (snapshot.isAccessBlocked) {
            return ConfigResolution(ConfigReferenceStatus.UNDECIDABLE, emptyList(), visibilityBlocked = true)
        }
        // No index, or one derived under a different visibility state — the
        // latter may hold hidden definitions and must not be served. Identity
        // gating stays the service's job (the service never hands an index for
        // another identity), so this guard only has to decide visibility-state
        // mismatches.
        if (index == null || !index.visibilityCompatibleWith(snapshot)) {
            // Neither can serve a hit, so concluding here takes nothing from
            // the user — and the terminal format conclusion needs neither of
            // them. Deferring it to a later pass would spend a sweep on a
            // format no sweep can help, which is the cold-start half of the
            // leak this state closes (issue #172).
            terminalFormatResolution(preferredDataId, formatOverride)?.let { return it }
            return ConfigResolution(ConfigReferenceStatus.UNAVAILABLE, emptyList())
        }
        val hits = scopedDefinitions(
            index, snapshot, key, activeNamespaceId, allowCrossNamespace, preferredDataId, formatOverride
        ).map { it.judgedAt(snapshot.asOfMillis) }
        if (hits.isNotEmpty()) return resolutionFromHits(hits)

        // No detail hit for the key. A Namespace-scoped block hides that
        // Namespace's definitions and its summary index, so absence in it is
        // undecidable — never a confident miss (issue #126). Only a visible
        // Namespace's fresh complete index may prove a data id absent.
        if (isNamespaceBlocked(snapshot, activeNamespaceId)) {
            return ConfigResolution(ConfigReferenceStatus.UNDECIDABLE, emptyList(), visibilityBlocked = true)
        }

        // A declared data id is what makes a miss attributable, and it decides
        // these questions in order.
        val dataId = preferredDataId?.takeIf { it.isNotBlank() }
        if (dataId != null) {
            // First, whether any body could have helped.
            terminalFormatResolution(dataId, formatOverride)?.let { return it }
            // The declared body's detail is already in this Namespace's
            // 配置详情缓存: soft discovery found nothing else, so the key is
            // genuinely absent — not "body not loaded" (issue #192 follow-up to
            // the pre-existing empty-hit path that re-asked presence).
            if (isDetailCachedForDataId(dataId, index, activeNamespaceId)) {
                return ConfigResolution(ConfigReferenceStatus.UNRESOLVED, emptyList())
            }
            // Then, whether it is there at all: the complete namespace summary
            // index decides absence (UNRESOLVED) vs "exists but body not
            // loaded" (UNDECIDABLE) — issue #52 / ADR-0041.
            return dataIdPresenceResolution(dataId, snapshot, activeNamespaceId)
        }
        return resolutionFromHits(hits)
    }

    /**
     * The terminal 格式不参与解析 conclusion for [preferredDataId], or null when
     * its 运行时格式 is one the plugin reads keys from — or when there is no data
     * id to attribute the miss to.
     *
     * Settled without a body, so it needs neither one nor an index:
     * cached, listed, or never fetched, no body under this 运行时格式 makes the
     * placeholder resolve. Answering "exists but body not loaded" instead was a
     * lie about a body already in hand, and the lie is what kept a sweep pending
     * for the life of the IDE (issue #172).
     *
     * Two things outrank it, and only two. A **key hit** does: a placeholder
     * that resolves somewhere is resolved whatever its declared data id says,
     * and a refusal the runtime would not make costs navigation that would have
     * worked (ADR-0055). A **visibility block** does: the plugin never converts
     * "cannot see" into a conclusion (issue #126). Presence does not — whether
     * the configuration exists changes nothing about whether a placeholder could
     * resolve against it, so proving absence here would buy nothing and cost the
     * sweep that proves it.
     *
     * [formatOverride] is not a third thing that outranks the conclusion — it
     * changes what the conclusion is drawn from. A site that declares a type is
     * naming the 运行时格式 its configuration will actually be read under, so a
     * data id with no recognizable suffix stops arriving here once a site
     * declares one; rescuing it from this state is the whole reason a developer
     * writes the declaration (#173).
     */
    private fun terminalFormatResolution(
        preferredDataId: String?,
        formatOverride: ReferenceFormatOverride?
    ): ConfigResolution? {
        val dataId = preferredDataId?.takeIf { it.isNotBlank() } ?: return null
        val reason = runtimeFormatRefusal(dataId, formatOverride) ?: return null
        return ConfigResolution(ConfigReferenceStatus.FORMAT_NOT_PARSED, emptyList(), formatRefusal = reason)
    }

    private fun isNamespaceBlocked(snapshot: CacheSnapshot, namespaceId: String?): Boolean =
        NamespaceInfo.canonicalId(namespaceId) in snapshot.blockedNamespaces

    /**
     * Returns true when [dataId] is known to exist among the cached
     * configurations behind [snapshot].
     *
     * When no index has been built yet, or the cache is empty (cold start /
     * namespace not yet loaded), this returns true optimistically so the
     * unresolved gutter marker can still appear and drive a lazy remote fetch.
     * Only a fresh, complete namespace index may prove absence — and only when
     * visibility permits it: under a block the index and the namespace view
     * for that coordinate are filtered out, so this falls through to the
     * optimistic `true` and absence stays unproven (issue #126).
     */
    fun isDataIdKnown(
        dataId: String,
        index: KeyIndex?,
        snapshot: CacheSnapshot,
        activeNamespaceId: String? = null
    ): Boolean {
        if (dataId.isBlank()) return false
        val normalizedNamespace = normalizeNamespaceId(activeNamespaceId)
        // A compatible index never lists a hidden Namespace; an index that is
        // not compatible is not served by the service, so the only way past
        // this check is the snapshot's own authority below.
        if (dataId in index?.dataIdsByNamespace?.get(normalizedNamespace).orEmpty()) return true
        if (isNamespaceBlocked(snapshot, activeNamespaceId)) return true

        val namespaceState = snapshot.namespaceIndex(activeNamespaceId) ?: return true
        if (namespaceState.freshness != CacheService.DetailFreshness.FRESH ||
            !namespaceState.authoritativeForAbsence
        ) return true
        return dataId in namespaceState.dataIds
    }

    /**
     * Classifies a preferred data id against the authoritative namespace summary
     * index when no key hit exists in the detail-backed key index.
     */
    internal fun dataIdPresenceResolution(
        dataId: String,
        snapshot: CacheSnapshot,
        activeNamespaceId: String?
    ): ConfigResolution = when (dataIdPresence(dataId, snapshot, activeNamespaceId)) {
        DataIdPresence.PRESENT -> ConfigResolution(ConfigReferenceStatus.UNDECIDABLE, emptyList())
        DataIdPresence.ABSENT -> ConfigResolution(ConfigReferenceStatus.UNRESOLVED, emptyList())
        DataIdPresence.UNKNOWN -> ConfigResolution(ConfigReferenceStatus.UNAVAILABLE, emptyList())
    }

    internal fun resolutionFromHits(hits: List<KeyHit>): ConfigResolution {
        return ConfigResolution(
            when {
                hits.isEmpty() -> ConfigReferenceStatus.UNRESOLVED
                hits.any { it.freshness == CacheService.DetailFreshness.FRESH } -> ConfigReferenceStatus.RESOLVED
                else -> ConfigReferenceStatus.STALE
            },
            hits
        )
    }

    private fun scopedDefinitions(
        index: KeyIndex,
        snapshot: CacheSnapshot,
        key: String,
        activeNamespaceId: String?,
        allowCrossNamespace: Boolean,
        preferredDataId: String?,
        formatOverride: ReferenceFormatOverride?
    ): List<KeyDefinition> {
        val scoped = definitionsFor(index, snapshot, key, formatOverride)
            .filter { allowCrossNamespace || sameNamespace(it.namespaceId, activeNamespaceId) }
        return preferDataIdDefinitions(scoped, preferredDataId, index, snapshot, activeNamespaceId)
    }

    /**
     * Every definition of [key] this reference may see.
     *
     * Without an override that is what the 派生 key 索引 holds. With one, the
     * definitions of the coordinate the site named are *replaced* rather than
     * added to: the index read that configuration under the format its data id
     * decides, and a site that declares another is asserting the index's
     * reading is not what the runtime will do. Everything the override does not
     * name keeps what the index holds — including another copy of the same data
     * id in a group or Namespace the site said nothing about — so a declaration
     * that finds nothing still degrades to soft discovery when the three-way
     * filter allows it (issue #192), and discovery reaches those configurations
     * under their own data ids, never under this site's declaration (#173).
     */
    private fun definitionsFor(
        index: KeyIndex,
        snapshot: CacheSnapshot,
        key: String,
        formatOverride: ReferenceFormatOverride?
    ): List<KeyDefinition> {
        val indexed = index.definitionsByKey[key].orEmpty()
        if (formatOverride == null) return indexed
        // Coordinate Namespace is authority for which definition the site named
        // (issue #190); do not match the override against payload tenant.
        return indexed.filterNot { formatOverride.appliesTo(it.config, it.namespaceId) } +
            overrideDefinitions(snapshot, formatOverride, key)
    }

    /**
     * Definitions of [key] in the configuration [override] names, re-derived
     * from the snapshot under the format its reference site declared.
     *
     * Computed on demand and deliberately not cached (#173). It reaches the
     * snapshot's own cached details rather than resolving through the index,
     * because the index holds this configuration read the wrong way; the walk is
     * bounded by the cache's entry limit, and a declaration that agrees with the
     * data id raises no override at all, so the ordinary gutter pass never
     * arrives here. If it ever measures as a cost, key a cache by data id plus
     * 运行时格式 — do not build one up front.
     *
     * It reads the snapshot and writes nothing, so it takes no observation
     * sequence and needs none (ADR-0052).
     */
    private fun overrideDefinitions(
        snapshot: CacheSnapshot,
        override: ReferenceFormatOverride,
        key: String
    ): List<KeyDefinition> = snapshot.configurations
        .filter { override.appliesTo(it.configuration, it.namespaceId) }
        .mapNotNull { cached ->
            keysUnderRuntimeFormat(
                cached.configuration,
                override,
                coordinateNamespaceId = cached.namespaceId
            )[key]?.let { location ->
                KeyDefinition(
                    cached.configuration,
                    location,
                    cached.freshUntilMillis,
                    cached.deepStaleAtMillis,
                    namespaceId = cached.namespaceId
                )
            }
        }

    /**
     * Three-way declared-Data-ID filter for 已声明配置来源 (issue #192).
     *
     * - Hits under the declared Data ID → hard constraint (those hits alone).
     * - Zero key-index hits and the declared body's detail is cached → soft
     *   discovery: the body was read and genuinely lacks the key, so a stale or
     *   incomplete PropertySource still degrades across the other definitions.
     * - Zero key-index hits and a fresh complete Namespace 索引 proves the
     *   Data ID absent → soft discovery for the same reason (mistyped source).
     * - Zero key-index hits and the Data ID is present in a fresh authoritative
     *   Namespace 索引 but absent from the 配置详情缓存 → no hits, so
     *   [resolveCurrentState] reports 不可判定配置引用 and the gutter can
     *   lazy-load the declared body instead of navigating to a substitute.
     * - Zero key-index hits and absence cannot be proven (no index, stale, or
     *   not authoritative for absence) → no hits; do not silently substitute
     *   another Data ID.
     */
    internal fun preferDataIdDefinitions(
        definitions: List<KeyDefinition>,
        preferredDataId: String?,
        index: KeyIndex,
        snapshot: CacheSnapshot,
        activeNamespaceId: String?
    ): List<KeyDefinition> {
        val dataId = preferredDataId?.takeIf { it.isNotBlank() } ?: return definitions
        val matched = definitions.filter { it.config.dataId == dataId }
        if (matched.isNotEmpty()) return matched
        return if (maySoftFallBackForDeclaredDataId(dataId, index, snapshot, activeNamespaceId)) {
            definitions
        } else {
            emptyList()
        }
    }

    /**
     * Whether zero key-index hits for [dataId] may soft-fall to the other
     * definitions. True only when the declared source has been read (body in
     * the 配置详情缓存, so the key is proven absent from it) or a fresh complete
     * Namespace 索引 proves the Data ID itself is absent. A listed-but-unfetched
     * Data ID, or one whose absence cannot be proven, must not substitute
     * another configuration (issue #192).
     */
    internal fun maySoftFallBackForDeclaredDataId(
        dataId: String,
        index: KeyIndex,
        snapshot: CacheSnapshot,
        activeNamespaceId: String?
    ): Boolean {
        if (isDetailCachedForDataId(dataId, index, activeNamespaceId)) return true
        return when (dataIdPresence(dataId, snapshot, activeNamespaceId)) {
            DataIdPresence.ABSENT -> true
            DataIdPresence.PRESENT, DataIdPresence.UNKNOWN -> false
        }
    }

    /**
     * True when the 派生 key 索引 was built from a detail-cached configuration
     * with [dataId] in the same Namespace that presence is judged under
     * ([activeNamespaceId], blank/public normalized as in [isDataIdKnown]).
     * A same-named body in another Namespace must not unlock soft discovery
     * for this one — that reopens the #192 substitute (cross-Namespace twist).
     */
    private fun isDetailCachedForDataId(
        dataId: String,
        index: KeyIndex,
        activeNamespaceId: String?
    ): Boolean {
        val ns = normalizeNamespaceId(activeNamespaceId)
        return dataId in index.dataIdsByNamespace[ns].orEmpty()
    }

    /**
     * Presence of [dataId] against the Namespace 索引 only. Does not consult
     * the detail cache — that is [isDetailCachedForDataId]'s job.
     */
    internal fun dataIdPresence(
        dataId: String,
        snapshot: CacheSnapshot,
        activeNamespaceId: String?
    ): DataIdPresence {
        val namespaceState = snapshot.namespaceIndex(activeNamespaceId)
            ?: return DataIdPresence.UNKNOWN
        if (namespaceState.freshness != CacheService.DetailFreshness.FRESH ||
            !namespaceState.authoritativeForAbsence
        ) {
            return DataIdPresence.UNKNOWN
        }
        return if (dataId in namespaceState.dataIds) DataIdPresence.PRESENT else DataIdPresence.ABSENT
    }

    /**
     * How a declared Data ID sits in the Namespace 索引 when no key hit exists.
     * Distinct from [ConfigReferenceStatus]: presence is an input to the
     * three-way filter, not a gutter conclusion (issue #192).
     */
    enum class DataIdPresence {
        /** Listed by a fresh authoritative Namespace 索引. */
        PRESENT,
        /** Proven absent by a fresh complete Namespace 索引. */
        ABSENT,
        /** No index, stale index, or one not authoritative for absence. */
        UNKNOWN
    }

    /**
     * Order: active namespace first, then public, then everything else
     * (stable within a tier by dataId/group).
     */
    private fun definitionComparator(
        activeNamespaceId: String?,
        preferredGroup: String?,
        preferredNamespaceId: String?,
        preferredDataId: String? = null
    ): Comparator<KeyDefinition> {
        val active = activeNamespaceId?.takeIf { it.isNotBlank() && it != "public" }
        val group = preferredGroup?.takeIf { it.isNotBlank() }
        val namespace = preferredNamespaceId?.takeIf { it.isNotBlank() && it != "public" }
        val dataId = preferredDataId?.takeIf { it.isNotBlank() }
        return Comparator { a, b ->
            compareValuesBy(
                a,
                b,
                { namespaceTier(it, active, namespace) },
                { groupTier(it, group) },
                { dataIdTier(it, dataId) },
                { it.config.dataId },
                { it.config.group }
            )
        }
    }

    private fun dataIdTier(definition: KeyDefinition, preferredDataId: String?): Int =
        if (preferredDataId != null && definition.config.dataId == preferredDataId) 0 else 1

    private fun namespaceTier(
        definition: KeyDefinition,
        activeNamespaceId: String?,
        preferredNamespaceId: String?
    ): Int = when {
        preferredNamespaceId != null && sameNamespace(definition.namespaceId, preferredNamespaceId) -> 0
        preferredNamespaceId != null -> 1
        activeNamespaceId != null && sameNamespace(definition.namespaceId, activeNamespaceId) -> 0
        // Coordinate Namespace is authority; public ranks above other non-active Namespaces.
        normalizeNamespaceId(definition.namespaceId).isEmpty() -> 1
        else -> 2
    }

    private fun groupTier(definition: KeyDefinition, preferredGroup: String?): Int =
        if (preferredGroup != null && definition.config.group == preferredGroup) 0 else 1

    private fun sameNamespace(hitNamespaceId: String?, activeNamespaceId: String?): Boolean =
        normalizeNamespaceId(hitNamespaceId) == normalizeNamespaceId(activeNamespaceId)

    private fun normalizeNamespaceId(namespaceId: String?): String =
        namespaceId?.takeIf { it.isNotBlank() && it != "public" } ?: ""
}
