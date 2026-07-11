package com.nanyin.nacos.search.settings

import com.intellij.util.messages.Topic

/**
 * Application-level notification fired after Nacos settings are applied.
 */
interface NacosSettingsListener {
    fun settingsChanged()

    /**
     * Fired when only non-connection preferences changed (e.g.
    * allowCrossNamespaceNavigation, displayName).
     * Subscribers that only care about connection-level changes can ignore
     * this; subscribers that need to refresh UI markers should handle it.
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
