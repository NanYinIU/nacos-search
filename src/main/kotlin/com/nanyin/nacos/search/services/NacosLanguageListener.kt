package com.nanyin.nacos.search.services

import com.intellij.util.messages.Topic

/**
 * Application-level notification fired after the plugin UI language changes.
 *
 * The new language is deliberately not an argument: [com.nanyin.nacos.search.bundle.NacosSearchBundle]
 * resolves the locale globally, so every subscriber simply re-reads the bundle.
 * Subscribing per component — rather than dispatching to the tool window and
 * fanning out by hand — is what keeps a component from being forgotten.
 */
interface NacosLanguageListener {
    fun languageChanged()

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<NacosLanguageListener> = Topic.create(
            "Nacos Search Language",
            NacosLanguageListener::class.java
        )
    }
}
