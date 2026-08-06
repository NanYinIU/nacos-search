package com.nanyin.nacos.search.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.ResolvedGenerationLocator
import com.nanyin.nacos.search.services.captureAccessIdentity

/** Project-local state; profiles themselves remain application-wide. */
data class NacosProjectSessionState(
    var selectedProfileId: String = "",
    var namespaceId: String = "public",
    var selectionWasExplicit: Boolean = false,
    /**
     * Schema version for which the dismissible upgrade summary was already
     * shown in this project. 0 means never shown. Replaces the old boolean so
     * a later schema bump can present a new summary once (issue #104).
     */
    var upgradeSummaryShownForSchemaVersion: Int = 0,
    /**
     * Legacy boolean retained for one-release XML compatibility. Mapped into
     * [upgradeSummaryShownForSchemaVersion] on load when the version field is 0.
     */
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
        // Explicit selections must not silently retarget after profile deletion
        // (ADR 0025). Leave the stale id so captureOperationContext fails closed.
        if (selectionWasExplicit && selectedProfileId.isNotBlank()) return
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
        // Promote legacy boolean: shown once under the previous boolean model
        // counts as shown for schema v1 so we do not re-notify on upgrade to
        // the versioned field alone.
        if (sessionState.upgradeSummaryShownForSchemaVersion == 0 && sessionState.upgradeSummaryShown) {
            sessionState.upgradeSummaryShownForSchemaVersion = 1
        }
    }

    fun seedIfNew(defaults: LegacyMigrationResult) = sessionState.seedIfNew(defaults)

    fun healSelection(settings: NacosSettings) {
        val defaults = settings.migrationDefaults()
        sessionState.healSelection(defaults) { profileId -> settings.getProfile(profileId) != null }
    }

    fun select(profileId: String, namespace: String) = sessionState.select(profileId, namespace)

    fun markUpgradeSummaryShown(schemaVersion: Int = SettingsSchema.CURRENT) {
        sessionState.upgradeSummaryShownForSchemaVersion =
            maxOf(sessionState.upgradeSummaryShownForSchemaVersion, schemaVersion)
        sessionState.upgradeSummaryShown = schemaVersion > 0
    }

    /**
     * Profile-deletion posture (ADR-0025 / design §13.6): keep the stale
     * [NacosProjectSessionState.selectedProfileId] and force
     * [NacosProjectSessionState.selectionWasExplicit] so [healSelection] will
     * not silently retarget to another environment. Returns false when this
     * session is not currently selecting [profileId].
     */
    fun markSelectedProfileUnavailable(profileId: String): Boolean {
        val id = profileId.trim()
        if (id.isBlank() || sessionState.selectedProfileId != id) return false
        sessionState.selectionWasExplicit = true
        return true
    }
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

/**
 * The namespace an operation on [configuration] addresses: the configuration's
 * own tenant when the response carried one, otherwise this project's session
 * namespace.
 *
 * List responses omit the tenant, and assuming public while the tool window is
 * browsing another namespace would address a different configuration. This is
 * the one derivation — the edit session binds what it returns, and the tool
 * window asks the same question when deciding whether a click retargets that
 * binding, so the two cannot disagree.
 */
internal fun Project.operationNamespaceIdFor(configuration: NacosConfiguration): String {
    configuration.tenantId?.takeIf { it.isNotBlank() }?.let { return it }
    getService(NacosProjectSession::class.java)?.sessionState?.namespaceId
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return NamespaceInfo.PUBLIC
}

/**
 * The access identity this project's cache reads address. The resolved API
 * generation comes from this project's own session first (ADR-0004: sessions are
 * per-project), then from the persisted last-known value — never from a
 * credential (issue #72).
 */
internal fun Project.captureSelectedAccessIdentity(
    settings: NacosSettings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
): AccessIdentity = settings.captureAccessIdentity(
    selectedNacosProfileId(settings),
    ResolvedGenerationLocator.forProject(this)
)

internal fun Project.allowCrossNamespaceNavigation(
    settings: NacosSettings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
): Boolean {
    val profileId = selectedNacosProfileId(settings)
    return settings.preferencesFor(profileId).allowCrossNamespaceNavigation
}

/**
 * Reads the navigation detail prefetch preference for this project's selected
 * environment at call time (ADR-0042): the toggle is not captured into the
 * operation context, so the prefetch observes cancellation / disable itself.
 * Resolved from the profile-associated preference record (issue #101), not the
 * legacy server entry.
 */
internal fun Project.navigationDetailPrefetchEnabled(
    settings: NacosSettings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
): Boolean {
    val profileId = selectedNacosProfileId(settings)
    return settings.preferencesFor(profileId).navigationDetailPrefetchEnabled
}

internal fun NacosSettings.navigationDetailPrefetchEnabled(profileId: String? = null): Boolean {
    val id = profileId?.takeIf { it.isNotBlank() } ?: activeServerId
    return preferencesFor(id).navigationDetailPrefetchEnabled
}

internal fun NacosSettings.allowCrossNamespaceNavigation(profileId: String? = null): Boolean {
    val id = profileId?.takeIf { it.isNotBlank() } ?: activeServerId
    return preferencesFor(id).allowCrossNamespaceNavigation
}
