package com.nanyin.nacos.search.services.operations

/**
 * The operation axis shared by the protocol capability declaration and the
 * access-visibility module (ADR-0050 / issue #48 / #125).
 *
 * One vocabulary, two judgments on the same axis: [ProtocolCapabilities]
 * grades dialect *support* per capability, and the visibility module scopes
 * *permission* per capability. There is deliberately no parallel
 * visibility-only capability enumeration.
 */
enum class ProtocolCapability {
    /** Configuration list, detail (and content-search) reads. */
    CONFIGURATION_READ,

    /** Publishing a configuration (write). */
    PUBLISH,

    /** Namespace discovery. */
    NAMESPACE_DISCOVERY,

    /** Configuration history browsing. */
    HISTORY,

    /** Formal generation / authentication contact that is not a data read. */
    AUTHENTICATED_CONTACT
}

/**
 * Graded support a protocol dialect declares for an operation.
 *
 * Coverage limitations are distinct from [UNAVAILABLE], and neither is an
 * authentication, authorization, or network failure (ADR-0013 / ADR-0048).
 */
enum class CapabilityCoverage {
    /** Full protocol support for the operation. */
    COMPLETE,
    /** Operation works with documented limitations (reported as coverage). */
    LIMITED,
    /** Dialect does not provide the operation at all. */
    UNAVAILABLE
}

/**
 * Operations and coverage a [ProtocolAdapter] dialect actually provides.
 *
 * Upper layers consume this declaration instead of switching on API generation.
 * A grade of [CapabilityCoverage.UNAVAILABLE] is what makes
 * [RemoteOperationError.CapabilityUnsupported] reachable in production.
 */
data class ProtocolCapabilities(
    val contentSearch: CapabilityCoverage,
    val namespaceDiscovery: CapabilityCoverage,
    val history: CapabilityCoverage,
    /**
     * Whether configuration-list responses already carry full bodies.
     * [CapabilityCoverage.COMPLETE] means a COMPLETE namespace-index load may
     * seed ordinary detail coordinates from those bodies (issue #146);
     * [CapabilityCoverage.UNAVAILABLE] means lists are summaries only and
     * navigation detail prefetch remains the body source (ADR-0041).
     */
    val listCarriesBodies: CapabilityCoverage
) {
    /**
     * Support grade for one capability of the shared vocabulary. Core
     * operations — configuration reads, publishing, and authenticated contact —
     * are provided by every dialect and always register [COMPLETE]; only the
     * optional capabilities declare a graded coverage.
     *
     * Support (here) and permission (visibility module) stay separate judgments
     * on the same [ProtocolCapability] axis (issue #48 / #125).
     */
    fun coverageOf(capability: ProtocolCapability): CapabilityCoverage = when (capability) {
        ProtocolCapability.NAMESPACE_DISCOVERY -> namespaceDiscovery
        ProtocolCapability.HISTORY -> history
        ProtocolCapability.CONFIGURATION_READ,
        ProtocolCapability.PUBLISH,
        ProtocolCapability.AUTHENTICATED_CONTACT -> CapabilityCoverage.COMPLETE
    }

    companion object {
        /** No optional operations; used by stubs that only exercise core reads. */
        val NONE = ProtocolCapabilities(
            contentSearch = CapabilityCoverage.UNAVAILABLE,
            namespaceDiscovery = CapabilityCoverage.UNAVAILABLE,
            history = CapabilityCoverage.UNAVAILABLE,
            listCarriesBodies = CapabilityCoverage.UNAVAILABLE
        )

        val V1 = ProtocolCapabilities(
            contentSearch = CapabilityCoverage.LIMITED,
            namespaceDiscovery = CapabilityCoverage.COMPLETE,
            history = CapabilityCoverage.COMPLETE,
            // V1 /nacos/v1/cs/configs list pages include each row's content.
            listCarriesBodies = CapabilityCoverage.COMPLETE
        )

        val V3 = ProtocolCapabilities(
            contentSearch = CapabilityCoverage.COMPLETE,
            namespaceDiscovery = CapabilityCoverage.COMPLETE,
            history = CapabilityCoverage.COMPLETE,
            // V3 admin list is summaries; bodies arrive via detail reads / prefetch.
            listCarriesBodies = CapabilityCoverage.UNAVAILABLE
        )
    }
}
