package com.nanyin.nacos.search.settings

import com.intellij.util.messages.Topic

/**
 * Application-level notification fired after Nacos settings are applied.
 */
interface NacosSettingsListener {
    fun settingsChanged()

    /**
     * Fired when only non-connection preferences changed (e.g.
     * allowCrossNamespaceNavigation, navigationDetailPrefetchEnabled, displayName).
     * Subscribers that only care about connection-level changes can ignore
     * this; subscribers that need to refresh UI markers should handle it.
     * Preference-only changes must not bump the profile revision or advance
     * the session epoch (ADR-0042).
     */
    fun preferencesChanged() {}

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<NacosSettingsListener> = Topic.create(
            "Nacos Search Settings",
            NacosSettingsListener::class.java
        )
    }
}
