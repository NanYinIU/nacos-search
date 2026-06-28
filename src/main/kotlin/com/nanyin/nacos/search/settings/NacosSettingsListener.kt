package com.nanyin.nacos.search.settings

import com.intellij.util.messages.Topic

/**
 * Application-level notification fired after Nacos settings are applied.
 */
interface NacosSettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<NacosSettingsListener> = Topic.create(
            "Nacos Search Settings",
            NacosSettingsListener::class.java
        )
    }
}
