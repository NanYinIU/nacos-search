package com.nanyin.nacos.search.ui

/**
 * Stable Group picker options for one Namespace.
 *
 * Options are a facet of the Namespace dataset, not of the current filtered
 * hit page: absorbing an empty result must not wipe groups already seen, and
 * switching Namespace starts a new set.
 */
internal class NamespaceGroupFacet {
    private var initialized = false
    private var namespaceId: String? = null
    private val groups = linkedSetOf<String>()

    fun absorb(namespaceId: String?, groups: Iterable<String>): List<String> {
        if (!initialized || this.namespaceId != namespaceId) {
            initialized = true
            this.namespaceId = namespaceId
            this.groups.clear()
        }
        groups.asSequence().filter { it.isNotBlank() }.forEach { this.groups.add(it) }
        return snapshot()
    }

    fun snapshot(): List<String> = groups.toList()
}
