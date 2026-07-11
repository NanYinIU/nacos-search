package com.nanyin.nacos.search.psi

enum class ConfigReferenceStatus { RESOLVED, UNRESOLVED, UNAVAILABLE }

data class ConfigResolution(
    val status: ConfigReferenceStatus,
    val hits: List<NacosKeyResolver.KeyHit>
)
