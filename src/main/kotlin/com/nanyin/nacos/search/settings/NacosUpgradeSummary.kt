package com.nanyin.nacos.search.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Presents completed migration effects once per schema version without
 * interrupting the user's work (ADR-0037 / issue #104).
 */
object NacosUpgradeSummary {
    fun showOnce(project: Project, session: NacosProjectSession, settings: NacosSettings) {
        val report = settings.lastMigrationReport
        val schemaVersion = report?.toSchemaVersion ?: settings.settingsSchemaVersion
        if (schemaVersion <= 0) return
        if (session.sessionState.upgradeSummaryShownForSchemaVersion >= schemaVersion) return

        val actions = report?.actions.orEmpty()
        // Fresh installs and already-current reloads produce no actions — skip.
        if (actions.isEmpty() && (report == null || !report.completedUpgrade)) {
            session.markUpgradeSummaryShown(schemaVersion)
            return
        }
        if (actions.isEmpty()) {
            session.markUpgradeSummaryShown(schemaVersion)
            return
        }

        val defaults = settings.migrationDefaults()
        val body = buildString {
            append("Settings schema v")
            append(report?.fromSchemaVersion ?: 0)
            append(" → v")
            append(schemaVersion)
            append(". ")
            append(actions.joinToString("; "))
            append(". This project starts with ")
            append(defaults.defaultProfileId)
            append(" / ")
            append(defaults.defaultNamespaceId)
            append("; future project selections stay independent.")
        }
        Notification(
            "Nacos Search",
            "Nacos environment upgrade complete",
            body,
            NotificationType.INFORMATION
        ).notify(project)
        session.markUpgradeSummaryShown(schemaVersion)
    }
}
