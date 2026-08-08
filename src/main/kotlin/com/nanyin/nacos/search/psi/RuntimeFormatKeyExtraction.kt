package com.nanyin.nacos.search.psi

import com.nanyin.nacos.search.models.NacosConfiguration

/**
 * The keys [config] contributes, read under the 运行时格式 its data id decides.
 *
 * The format no longer comes from the configuration's 声明格式, which no runtime
 * consults, but from [RuntimeFormatDecision] (#171); and a refusal no longer
 * has to read as "no keys" here, because the caller that would have been misled
 * by that reading now asks [runtimeFormatRefusal] first and reaches
 * 格式不参与解析 without ever looking at a body (#172).
 *
 * What is left flattens deliberately. Every caller of this one has a body in
 * hand and wants the keys in it, and none of them turns the empty map into an
 * absence claim: the 派生 key 索引 contributes nothing for a refusal, the detail
 * panel's own gutter markers have no lines to mark, and the marker's lazy-load
 * navigation asks only where to put the caret and already answers
 * [ConfigKeyExtractor.LINE_NOT_FOUND] when it does not know — a position it
 * degrades on, never a claim about the key.
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

/**
 * Why a configuration named [dataId] contributes no keys, or null when its
 * 运行时格式 is one the plugin parses.
 *
 * Answered from the data id alone. That is not a shortcut: whether the body is
 * cached, listed, or has never been fetched changes nothing about whether a
 * placeholder could ever resolve against it, and waiting for a body before
 * saying so is exactly what kept the plugin sweeping the server forever for a
 * format it will never parse (#172).
 */
internal fun runtimeFormatRefusal(dataId: String): KeyExtraction.Reason? =
    ConfigKeyExtractor.refusalFor(RuntimeFormatDecision.forDataId(dataId))
