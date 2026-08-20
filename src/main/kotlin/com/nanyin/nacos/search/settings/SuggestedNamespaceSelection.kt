package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.services.operations.DiscoveredNamespace

/**
 * Inputs that determine which Namespace discovery list belongs to the
 * unsaved settings form. Changing any of them invalidates discovered options.
 */
data class DiscoveryOptionKey(
    val endpoint: String,
    val apiPolicy: String,
    val authStrategy: String,
    val principal: String,
    val secret: String
)

/**
 * Maps the settings suggested-Namespace editor to a persistable Namespace ID
 * and filters discovered options. Display names are for humans; the identifier
 * is what the draft stores.
 */
object SuggestedNamespaceSelection {
    fun persistableId(
        editorText: String,
        options: List<DiscoveredNamespace>,
        lastCommitted: String = ""
    ): String {
        val text = editorText.trim()
        if (text.isEmpty()) return ""
        options.find { it.namespaceId == text }?.let { return it.namespaceId }
        val byName = options.filter { it.displayName.equals(text, ignoreCase = true) }
        if (byName.size == 1) return byName.single().namespaceId
        if (options.isEmpty()) return text
        val stillFiltering = options.any { matchesFilter(it, text) }
        return if (stillFiltering) lastCommitted else text
    }

    fun matchesFilter(option: DiscoveredNamespace, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return option.displayName.contains(q, ignoreCase = true) ||
            option.namespaceId.contains(q, ignoreCase = true)
    }

    fun label(option: DiscoveredNamespace): String {
        val name = option.displayName.trim()
        return if (name.isEmpty() || name == option.namespaceId) option.namespaceId
        else "$name (${option.namespaceId})"
    }
}

/**
 * Non-persistable rows shown while isolated discovery is in flight or failed.
 * They never become the 建议 Namespace identifier.
 */
internal object SuggestedNamespaceChooserStatus {
    val LOADING = DiscoveredNamespace("\u0000chooser-loading", "")
    val FAILED = DiscoveredNamespace("\u0000chooser-failed", "")

    fun isTransient(option: DiscoveredNamespace): Boolean =
        option === LOADING || option === FAILED
}
