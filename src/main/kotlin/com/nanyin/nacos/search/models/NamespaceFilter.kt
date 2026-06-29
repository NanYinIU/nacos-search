package com.nanyin.nacos.search.models

/**
 * Pure, side-effect-free filtering for the namespace picker.
 *
 * Rules:
 *  - Empty/blank query matches everything.
 *  - Otherwise the query is matched as a case-insensitive substring against
 *    the namespace name, id and description.
 *  - The public namespace is always considered a match and is pinned to the
 *    top of the result so it remains reachable regardless of query length.
 */
object NamespaceFilter {

    fun filter(namespaces: List<NamespaceInfo>, query: String): List<NamespaceInfo> {
        val q = query.trim().lowercase()
        val matched = if (q.isEmpty()) namespaces else namespaces.filter { matches(it, q) }
        return pinPublicFirst(matched)
    }

    fun matches(ns: NamespaceInfo, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        if (ns.isPublicNamespace()) return true
        return ns.namespaceName.lowercase().contains(q) ||
            ns.namespaceId.lowercase().contains(q) ||
            ns.namespaceDesc.lowercase().contains(q)
    }

    private fun pinPublicFirst(list: List<NamespaceInfo>): List<NamespaceInfo> {
        val (public, rest) = list.partition { it.isPublicNamespace() }
        return public + rest
    }
}
