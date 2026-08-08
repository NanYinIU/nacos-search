package com.nanyin.nacos.search.psi

import com.nanyin.nacos.search.models.NacosConfiguration

/**
 * The keys [config] contributes, read under the 运行时格式 its data id decides.
 *
 * Half of what was transitional here is settled: the format no longer comes
 * from the configuration's 声明格式, which no runtime consults, but from
 * [RuntimeFormatDecision] (#171).
 *
 * The other half is not. A refusal still reads as "no keys" — the reading
 * [KeyExtraction] exists to prevent — until it reaches its own terminal
 * resolution state, which, unlike an empty key set, never asks for a background
 * Namespace index refresh (#166). This stays one function so that when it does,
 * there is one place to change rather than one per caller.
 */
internal fun keysUnderRuntimeFormat(
    config: NacosConfiguration
): Map<String, ConfigKeyExtractor.KeyLocation> {
    val extraction = ConfigKeyExtractor.extract(
        config.content,
        RuntimeFormatDecision.forDataId(config.dataId)
    )
    return when (extraction) {
        is KeyExtraction.Extracted -> extraction.keys
        is KeyExtraction.NotExtractable -> emptyMap()
    }
}
