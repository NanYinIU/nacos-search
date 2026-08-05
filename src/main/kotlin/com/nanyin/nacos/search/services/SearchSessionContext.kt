package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.settings.NacosOperationContext

/**
 * The environment a search runs against: which environment profile, which
 * namespace, and the operation context already captured for that profile.
 *
 * [NacosSearchService] is *given* one of these when the project session changes
 * and holds it (ADR-0046). That is the opposite of capturing one per call, and
 * it is what stops a Swing handler from reading the credential store on the
 * event dispatch thread: the capture happens once, off the EDT, in the
 * component that observes the session change.
 */
data class SearchSessionContext(
    val profileId: String = "",
    val namespace: NamespaceInfo? = null,
    val operationContext: NacosOperationContext? = null
) {
    val namespaceId: String?
        get() = namespace?.namespaceId

    /**
     * True when [other] names the same search target as this context.
     *
     * Deliberately not `equals`: [NacosOperationContext] carries a
     * [com.nanyin.nacos.search.settings.CredentialSnapshot], which compares by
     * identity, so two captures of one unchanged profile are never equal as
     * values. Comparing the identity and both revisions instead means a fresh
     * capture of the same environment does not read as a session change — while
     * a credential rotation, which moves the access revision, still does.
     */
    fun addressesSameTargetAs(other: SearchSessionContext): Boolean {
        if (profileId != other.profileId) return false
        if (NamespaceInfo.canonicalId(namespaceId) != NamespaceInfo.canonicalId(other.namespaceId)) {
            return false
        }
        val mine = operationContext
        val theirs = other.operationContext
        if (mine == null || theirs == null) return (mine == null) == (theirs == null)
        return mine.identity == theirs.identity &&
            mine.profileRevision == theirs.profileRevision &&
            mine.accessRevision == theirs.accessRevision
    }

    companion object {
        /** No environment selected yet; a search under it addresses nothing. */
        val NONE = SearchSessionContext()
    }
}
