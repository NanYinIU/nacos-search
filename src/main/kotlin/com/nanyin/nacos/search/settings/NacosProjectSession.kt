package com.nanyin.nacos.search.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/** Project-local state; profiles themselves remain application-wide. */
data class NacosProjectSessionState(
    var selectedProfileId: String = "",
    var namespaceId: String = "public",
    var selectionWasExplicit: Boolean = false,
    var upgradeSummaryShown: Boolean = false
) {
    fun seedIfNew(defaults: LegacyMigrationResult) {
        if (selectionWasExplicit || selectedProfileId.isNotBlank()) return
        selectedProfileId = defaults.defaultProfileId
        namespaceId = defaults.defaultNamespaceId
    }

    fun select(profileId: String, namespace: String) {
        selectedProfileId = profileId
        namespaceId = namespace.ifBlank { "public" }
        selectionWasExplicit = true
    }
}

@Service(Service.Level.PROJECT)
@State(name = "NacosProjectSession", storages = [Storage("nacos-project-session.xml")])
class NacosProjectSession : PersistentStateComponent<NacosProjectSessionState> {
    var sessionState = NacosProjectSessionState()
        private set

    override fun getState(): NacosProjectSessionState = sessionState

    override fun loadState(state: NacosProjectSessionState) {
        XmlSerializerUtil.copyBean(state, this.sessionState)
    }

    fun seedIfNew(defaults: LegacyMigrationResult) = sessionState.seedIfNew(defaults)
    fun select(profileId: String, namespace: String) = sessionState.select(profileId, namespace)
    fun markUpgradeSummaryShown() { sessionState.upgradeSummaryShown = true }
}
