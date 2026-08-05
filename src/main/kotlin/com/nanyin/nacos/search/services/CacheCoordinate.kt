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
 * Versioned key component used to derive the persisted storage key. This is
 * intentionally not compatible with the pre-profile schema, whose ownership
 * could not be proven after credentials or endpoints changed.
     */
    fun storageKey(): String

    data class NamespaceIndex(
        override val identity: AccessIdentity,
        override val serverUrl: String,
        override val namespaceId: String
    ) : CacheCoordinate {
        override fun storageKey(): String =
            identityPrefix(identity) + "|${ns(namespaceId)}"
    }

    data class Detail(
        override val identity: AccessIdentity,
        override val serverUrl: String,
        override val namespaceId: String,
        val dataId: String,
        val group: String
    ) : CacheCoordinate {
        override fun storageKey(): String =
            identityPrefix(identity) + "|${ns(namespaceId)}|$dataId|$group"
    }

    data class ListPage(
        override val identity: AccessIdentity,
        override val serverUrl: String,
        override val namespaceId: String,
        val requestKey: String
    ) : CacheCoordinate {
        override fun storageKey(): String =
            identityPrefix(identity) + "|${ns(namespaceId)}|$requestKey"
    }

    companion object {
        private fun ns(namespaceId: String): String =
            com.nanyin.nacos.search.models.NamespaceInfo.canonicalId(namespaceId)

        // The one derivation of each storage key. Both the cache and the
        // snapshot it hands out look entries up by these, so a lookup and the
        // write it should find can never be derived two different ways —
        // the failure mode issue #62 removed for access identities.

        fun detailKey(identity: AccessIdentity, namespaceId: String?, dataId: String, group: String): String =
            Detail(identity, identity.serverId, namespaceId.orEmpty(), dataId, group).storageKey()

        fun listPageKey(identity: AccessIdentity, namespaceId: String?, requestKey: String): String =
            ListPage(identity, identity.serverId, namespaceId.orEmpty(), requestKey).storageKey()

        fun namespaceIndexKey(identity: AccessIdentity, namespaceId: String?): String =
            NamespaceIndex(identity, identity.serverId, namespaceId.orEmpty()).storageKey()

        fun identityPrefix(identity: AccessIdentity): String = listOf(
            "v2",
            identity.profileId,
            identity.accessRevision.toString(),
            identity.canonicalEndpoint,
            identity.resolvedGeneration.toString(),
            identity.authMode.name,
            identity.principal
        ).joinToString("|")
    }
}
