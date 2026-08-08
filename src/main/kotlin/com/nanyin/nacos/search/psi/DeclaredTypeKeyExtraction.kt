package com.nanyin.nacos.search.psi

import com.nanyin.nacos.search.models.NacosConfiguration

/**
 * Extraction as the callers still perform it: the 运行时格式 derived from the
 * configuration's own declared type, and a refusal read as "no keys".
 *
 * Both halves are transitional, and both are wrong in a way that is already
 * understood (#166). The derivation trusts the one field no runtime consults
 * and will be replaced by the 运行时格式 decision table, which reads the type
 * declared at the reference site and then the data id suffix. The refusal will
 * reach its own terminal resolution state, which — unlike an empty key set —
 * never asks for a background Namespace index refresh.
 *
 * Neither is changed here. This function exists so that when they are, there is
 * one place to change rather than one per caller.
 */
internal fun keysUnderDeclaredType(
    config: NacosConfiguration
): Map<String, ConfigKeyExtractor.KeyLocation> {
    val extraction = ConfigKeyExtractor.extract(
        config.content,
        RuntimeConfigFormat.ofTypeName(config.getConfigType())
    )
    return when (extraction) {
        is KeyExtraction.Extracted -> extraction.keys
        is KeyExtraction.NotExtractable -> emptyMap()
    }
}
