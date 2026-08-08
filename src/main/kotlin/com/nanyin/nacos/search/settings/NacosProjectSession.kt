package com.nanyin.nacos.search.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.models.NamespaceInfo
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
    var upgradeSummaryShown: Boolean = false,
    /**
     * True after this project has read the migration seed once (or restored a
     * prior workspace selection). An initialized session never re-reads the
     * application-wide default as a live selection (issue #107).
     */
    var sessionInitialized: Boolean = false
) {
    /**
     * Reads the stable migration default exactly once for a blank session and
     * persists the resulting project-local selection. Subsequent calls are no-ops
     * even when the shared seed later moves (issue #107).
     */
    fun ensureInitialized(defaults: LegacyMigrationResult) {
        if (sessionInitialized) return
        if (selectedProfileId.isNotBlank() || selectionWasExplicit) {
            sessionInitialized = true
            return
        }
        selectedProfileId = defaults.defaultProfileId
        namespaceId = defaults.defaultNamespaceId.ifBlank { "public" }
        sessionInitialized = true
    }

    /** @see ensureInitialized */
    fun seedIfNew(defaults: LegacyMigrationResult) = ensureInitialized(defaults)

    /**
     * Ensures the session is initialized. Never retargets a missing profile —
     * leave the stale id for profile-unavailable (ADR-0025 / #107).
     */
    @Suppress("UNUSED_PARAMETER")
    fun healSelection(defaults: LegacyMigrationResult, profileExists: (String) -> Boolean) {
        ensureInitialized(defaults)
    }

    fun select(profileId: String, namespace: String) {
        selectedProfileId = profileId
        namespaceId = namespace.ifBlank { "public" }
        selectionWasExplicit = true
        sessionInitialized = true
    }
}

@Service(Service.Level.PROJECT)
@State(
    name = "NacosProjectSession",
    storages = [
        Storage(StoragePathMacros.WORKSPACE_FILE),
        // Pre-#107 projects wrote `$PROJECT_CONFIG_DIR$/nacos-project-session.xml`.
        // Keep it as a deprecated load source so upgrades adopt the selection
        // once into workspace.xml instead of re-seeding from the app-wide default.
        Storage(value = "nacos-project-session.xml", deprecated = true)
    ]
)
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
        // Pre-#107 workspace XML had a selection but no initialized flag —
        // treat any restored selection as already initialized so we do not
        // re-seed from the application-wide default.
        if (!sessionState.sessionInitialized &&
            (sessionState.selectedProfileId.isNotBlank() || sessionState.selectionWasExplicit)
        ) {
            sessionState.sessionInitialized = true
        }
    }

    fun seedIfNew(defaults: LegacyMigrationResult) = sessionState.ensureInitialized(defaults)

    fun ensureInitialized(defaults: LegacyMigrationResult) = sessionState.ensureInitialized(defaults)

    fun healSelection(settings: NacosSettings) {
        val defaults = settings.migrationDefaults()
        sessionState.healSelection(defaults) { profileId -> settings.getProfile(profileId) != null }
    }

    fun select(profileId: String, namespace: String) = sessionState.select(profileId, namespace)

    /**
     * Project-local environment adoption (issue #107). Updates only this
     * session — never [NacosSettings.activeServerId] or the migration seed.
     *
     * [namespaceId] semantics (ADR-0015 / public spelling):
     * - **null (omitted)** — keep the current Namespace when the profile is
     *   unchanged; otherwise public. Callers that switch environments without
     *   a suggested Namespace must not carry the previous server's tenant.
     * - **blank or `"public"`** — the public Namespace. Blank is how
     *   [NamespaceInfo.createPublicNamespace] spells the id; it is an explicit
     *   selection, not an omission. Treating blank as omit left the previous
     *   non-public Namespace on the session after a UI switch to public, so
     *   gutters re-ranked under the wrong input (issue #193 follow-up).
     * - **any other non-blank id** — that Namespace.
     */
    fun adoptEnvironment(profileId: String, namespaceId: String? = null) {
        val sameProfile = profileId == sessionState.selectedProfileId
        val ns = when {
            // Explicit argument, including blank/"public" for the public Namespace.
            namespaceId != null -> namespaceId.ifBlank { NamespaceInfo.PUBLIC }
            sameProfile && sessionState.namespaceId.isNotBlank() -> sessionState.namespaceId
            else -> NamespaceInfo.PUBLIC
        }
        select(profileId, ns)
    }

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
        sessionState.sessionInitialized = true
        return true
    }
}

/**
 * Namespace id owned by an initialized project session. External (application-
 * wide) namespace ids are ignored once the session exists (issue #107).
 */
internal fun resolveProjectNamespaceId(
    sessionState: NacosProjectSessionState,
    externalNamespaceId: String? = null
): String? {
    if (sessionState.sessionInitialized || sessionState.selectedProfileId.isNotBlank() ||
        sessionState.selectionWasExplicit
    ) {
        return sessionState.namespaceId.takeIf { it.isNotBlank() }
    }
    return externalNamespaceId?.takeIf { it.isNotBlank() }
        ?: sessionState.namespaceId.takeIf { it.isNotBlank() }
}

/**
 * PSI/UI helpers that read the project-selected profile and namespace.
 * Tool-window selection lives here; app-wide [NacosSettings.activeServerId] and
 * NamespaceService must not retarget another project's navigation.
 */
internal fun Project.selectedNacosProfileId(
    settings: NacosSettings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
): String {
    val session = getService(NacosProjectSession::class.java) ?: return settings.resolveDefaultProfileId()
    session.ensureInitialized(settings.migrationDefaults())
    return session.sessionState.selectedProfileId
}

internal fun Project.selectedNacosNamespaceId(
    settings: NacosSettings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
): String? {
    val session = getService(NacosProjectSession::class.java) ?: return null
    session.ensureInitialized(settings.migrationDefaults())
    return resolveProjectNamespaceId(session.sessionState)
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
