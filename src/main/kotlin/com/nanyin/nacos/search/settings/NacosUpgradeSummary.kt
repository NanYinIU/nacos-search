package com.nanyin.nacos.search.settings

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** Presents the migration result once without interrupting the user's work. */
object NacosUpgradeSummary {
    fun showOnce(project: Project, session: NacosProjectSession, settings: NacosSettings) {
        if (session.sessionState.upgradeSummaryShown) return
        val defaults = settings.migrationDefaults()
        Notification(
            "Nacos Search",
            "Nacos environment upgrade complete",
            "Migrated ${defaults.profiles.size} global environment profile(s). " +
                "This project starts with ${defaults.defaultProfileId} / ${defaults.defaultNamespaceId}; " +
                "future project selections stay independent. Legacy cache data was not reused.",
            NotificationType.INFORMATION
        ).notify(project)
        session.markUpgradeSummaryShown()
    }
}
