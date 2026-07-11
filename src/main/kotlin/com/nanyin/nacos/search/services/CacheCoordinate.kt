package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity

/**
 * Typed coordinate for cache lookups. Replaces positional server/namespace
 * parameters so that every cache key derivation flows through a single,
 * identity-aware path. The storage key never includes password or token.
 */
sealed interface CacheCoordinate {
    val identity: AccessIdentity
    val serverUrl: String
    val namespaceId: String

    /**
     * Normalized, slash-trimmed key component used to derive the persisted
     * storage key. Format: `serverId|authMode|principal|namespace|...`
     */
    fun storageKey(): String

    private companion object {
        fun normalize(url: String): String = url.trim().trimEnd('/').ifBlank { "<default>" }
        fun ns(namespaceId: String): String =
            namespaceId.takeIf { it.isNotBlank() && it != "public" } ?: "public"
    }

    data class NamespaceIndex(
        override val identity: AccessIdentity,
        override val serverUrl: String,
        override val namespaceId: String
    ) : CacheCoordinate {
        override fun storageKey(): String =
            "${identity.serverId}|${identity.authMode}|${identity.principal}|${ns(namespaceId)}"
    }

    data class Detail(
        override val identity: AccessIdentity,
        override val serverUrl: String,
        override val namespaceId: String,
        val dataId: String,
        val group: String
    ) : CacheCoordinate {
        override fun storageKey(): String =
            "${identity.serverId}|${identity.authMode}|${identity.principal}|${ns(namespaceId)}|$dataId|$group"
    }

    data class ListPage(
        override val identity: AccessIdentity,
        override val serverUrl: String,
        override val namespaceId: String,
        val requestKey: String
    ) : CacheCoordinate {
        override fun storageKey(): String =
            "${identity.serverId}|${identity.authMode}|${identity.principal}|${ns(namespaceId)}|$requestKey"
    }
}
