package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.models.SearchCriteria
import com.nanyin.nacos.search.services.NacosSearchService
import com.nanyin.nacos.search.services.SearchSessionContext
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicLong

/**
 * The tool window's search orchestration, without Swing.
 *
 * It does two things. It hands [NacosSearchService] the session context to
 * search under whenever the project session changes — the capture runs through
 * [captureOperationContext], which the window supplies off the event dispatch
 * thread, so no Swing handler ever reaches the credential store (ADR-0039 /
 * ADR-0046). And it turns the panels' gestures into the service's intents:
 * search this, next page, this page size. It names no target and assembles no
 * request; the service holds the one place a search's environment is decided.
 *
 * It renders nothing and judges no result. The service already orders what it
 * publishes — latest request wins, and a result issued under a superseded
 * session is dropped — so the window renders published state directly rather
 * than fencing it a second time.
 */
class ToolWindowSearchController(
    private val searchService: NacosSearchService,
    private val selectedProfileId: () -> String,
    private val captureOperationContext: suspend (String) -> NacosOperationContext?
) {
    /** What became of an attempt to hand the service a session. */
    enum class Adoption {
        /** A different environment or namespace is now the search target. */
        ADOPTED,

        /** The same target, re-prepared: what is on screen still applies. */
        UNCHANGED,

        /** A newer adoption or a moved selection won; this one installed nothing. */
        DISCARDED
    }

    /**
     * Orders concurrent adoptions. Switching environments quickly starts a
     * second capture before the first returns; without this an older capture
     * would install itself over the newer one and searches would silently
     * target the environment the user just left.
     */
    private val adoptions = AtomicLong(0)

    /**
     * Gives the search service the environment to search under: the selected
     * profile, [namespace], and a freshly captured operation context.
     *
     * A capture that finishes after a newer one started, or after the selection
     * has moved to another profile, is [Adoption.DISCARDED] rather than adopted.
     */
    suspend fun adoptSession(namespace: NamespaceInfo?): Adoption {
        val adoption = adoptions.incrementAndGet()
        val profileId = selectedProfileId()
        val context = captureOperationContext(profileId)
        if (adoption != adoptions.get()) return Adoption.DISCARDED
        if (profileId != selectedProfileId()) return Adoption.DISCARDED
        val changed = searchService.adoptSession(SearchSessionContext(profileId, namespace, context))
        return if (changed) Adoption.ADOPTED else Adoption.UNCHANGED
    }

    /**
     * Adopts [namespace] and shows its first page. Used when the user picks a
     * namespace and when a reload follows an environment or settings change.
     *
     * A discarded adoption loads nothing: the session it would have searched
     * under is not the one held, so the adoption that beat it owns the reload.
     */
    suspend fun openNamespace(namespace: NamespaceInfo?) {
        if (adoptSession(namespace) == Adoption.DISCARDED) return
        if (namespace != null) searchService.reload()
    }

    /**
     * Abandons the current environment: the held session drops to no namespace,
     * which cancels the pending search and clears the published results. The
     * caller re-selects a namespace afterwards, which loads the new list.
     */
    suspend fun leaveEnvironment() {
        adoptSession(namespace = null)
    }

    /** The environment searches currently target. */
    fun sessionContext(): SearchSessionContext = searchService.sessionContext()

    suspend fun search(criteria: SearchCriteria) = searchService.search(criteria)

    fun searchAsYouType(query: String, coroutineScope: CoroutineScope) =
        searchService.searchAsYouType(query, coroutineScope)

    /** Drops the criteria and shows the namespace's first page again. */
    suspend fun clearCriteria() = searchService.clearCriteria()

    /** Re-runs the current search, bypassing the cache. */
    suspend fun refresh() = searchService.reload(forceRefresh = true)

    suspend fun nextPage() = searchService.nextPage()

    suspend fun previousPage() = searchService.previousPage()

    suspend fun changePageSize(pageSize: Int) = searchService.changePageSize(pageSize)
}
