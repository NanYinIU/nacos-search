package com.nanyin.nacos.search.services.operations

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
    val history: CapabilityCoverage
) {
    companion object {
        /** No optional operations; used by stubs that only exercise core reads. */
        val NONE = ProtocolCapabilities(
            contentSearch = CapabilityCoverage.UNAVAILABLE,
            namespaceDiscovery = CapabilityCoverage.UNAVAILABLE,
            history = CapabilityCoverage.UNAVAILABLE
        )

        val V1 = ProtocolCapabilities(
            contentSearch = CapabilityCoverage.LIMITED,
            namespaceDiscovery = CapabilityCoverage.COMPLETE,
            history = CapabilityCoverage.COMPLETE
        )

        val V3 = ProtocolCapabilities(
            contentSearch = CapabilityCoverage.COMPLETE,
            namespaceDiscovery = CapabilityCoverage.COMPLETE,
            history = CapabilityCoverage.COMPLETE
        )
    }
}
