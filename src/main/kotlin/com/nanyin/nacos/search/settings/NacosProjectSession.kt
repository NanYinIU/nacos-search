package com.nanyin.nacos.search.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.captureAccessIdentity

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
        namespaceId = defaults.defaultNamespaceId.ifBlank { "public" }
    }

    /**
     * Keeps [selectedProfileId] pointing at a live profile. Stale workspace
     * selections (deleted server, failed Instantiator migration, empty seed)
     * otherwise make every search fail with "Select a Nacos environment profile".
     */
    fun healSelection(defaults: LegacyMigrationResult, profileExists: (String) -> Boolean) {
        seedIfNew(defaults)
        if (selectedProfileId.isNotBlank() && profileExists(selectedProfileId)) return
        val healed = defaults.defaultProfileId.takeIf { it.isNotBlank() && profileExists(it) }
            ?: defaults.profiles.firstOrNull { profileExists(it.id) }?.id
            ?: ""
        if (healed.isNotBlank()) {
            selectedProfileId = healed
            if (namespaceId.isBlank()) {
                namespaceId = defaults.defaultNamespaceId.ifBlank { "public" }
            }
        }
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

    fun healSelection(settings: NacosSettings) {
        val defaults = settings.migrationDefaults()
        sessionState.healSelection(defaults) { profileId -> settings.getProfile(profileId) != null }
    }

    fun select(profileId: String, namespace: String) = sessionState.select(profileId, namespace)
    fun markUpgradeSummaryShown() { sessionState.upgradeSummaryShown = true }
}

/**
 * PSI/UI helpers that read the project-selected profile and namespace.
 * Tool-window selection lives here; app-wide [NacosSettings.activeServerId] and
 * [NamespaceService] must not retarget another project's navigation.
 */
internal fun Project.selectedNacosProfileId(
    settings: NacosSettings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
): String {
    val session = getService(NacosProjectSession::class.java) ?: return settings.resolveDefaultProfileId()
    session.healSelection(settings)
    return session.sessionState.selectedProfileId.ifBlank { settings.resolveDefaultProfileId() }
}

internal fun Project.selectedNacosNamespaceId(
    settings: NacosSettings = ApplicationManager.getApplication().getService(NacosSettings::class.java),
    namespaceService: NamespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
): String? {
    val session = getService(NacosProjectSession::class.java)
    if (session != null) {
        session.seedIfNew(settings.migrationDefaults())
        if (session.sessionState.selectionWasExplicit) {
            return session.sessionState.namespaceId
        }
        val seeded = session.sessionState.namespaceId.takeIf { it.isNotBlank() }
        // Prefer an explicit app-service override only before the project has
        // made its own selection (tests / cold start).
        return namespaceService.getCurrentNamespace()?.namespaceId ?: seeded
    }
    return namespaceService.getCurrentNamespace()?.namespaceId
}

internal fun Project.captureSelectedAccessIdentity(
    settings: NacosSettings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
): AccessIdentity = settings.captureAccessIdentity(selectedNacosProfileId(settings))

internal fun Project.allowCrossNamespaceNavigation(
    settings: NacosSettings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
): Boolean {
    val profileId = selectedNacosProfileId(settings)
    return settings.cloneServers().firstOrNull { it.id == profileId }?.allowCrossNamespaceNavigation
        ?: settings.getActiveServer().allowCrossNamespaceNavigation
}
