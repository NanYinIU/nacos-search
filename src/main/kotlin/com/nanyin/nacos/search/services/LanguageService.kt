package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.ui.LanguageRefreshUtil
import java.util.*

/**
 * Service for managing language settings and localization
 */
@Service(Level.APP)
class LanguageService {
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    private val listeners = mutableListOf<LanguageChangeListener>()
    
    /**
     * Supported languages
     */
    enum class SupportedLanguage(val code: String, val displayName: String, val locale: Locale) {
        ENGLISH("en", "English", Locale.ENGLISH),
        CHINESE("zh", "中文", Locale.CHINESE)
    }
    
    /**
     * Get current language
     */
    fun getCurrentLanguage(): SupportedLanguage {
        return SupportedLanguage.values().find { it.code == settings.language } ?: SupportedLanguage.ENGLISH
    }
    
    /**
     * Set current language
     */
    fun setLanguage(languageCode: String) {
        val newLanguage = SupportedLanguage.values().find { it.code == languageCode } ?: SupportedLanguage.ENGLISH
        
        if (settings.language != newLanguage.code) {
            settings.language = newLanguage.code
            notifyLanguageChanged(newLanguage)
        }
    }
    
    /**
     * Get current locale
     */
    fun getCurrentLocale(): Locale {
        return getCurrentLanguage().locale
    }
    
    /**
     * Add language change listener
     */
    fun addLanguageChangeListener(listener: LanguageChangeListener) {
        listeners.add(listener)
    }
    
    /**
     * Remove language change listener
     */
    fun removeLanguageChangeListener(listener: LanguageChangeListener) {
        listeners.remove(listener)
    }
    
    /**
     * Notify all listeners about language change
     */
    private fun notifyLanguageChanged(newLanguage: SupportedLanguage) {
        // Notify registered listeners
        listeners.forEach { listener ->
            try {
                listener.onLanguageChanged(newLanguage)
            } catch (e: Exception) {
                // Log error but don't fail the entire notification process
                e.printStackTrace()
            }
        }
        
        // Refresh UI components
        try {
            LanguageRefreshUtil.refreshAllComponents(newLanguage)
            LanguageRefreshUtil.showLanguageChangeNotification(newLanguage)
        } catch (e: Exception) {
            // Log error but don't fail the language change
            e.printStackTrace()
        }
    }
    
    /**
     * Get localized message using current language
     */
    fun getMessage(key: String, vararg params: Any): String {
        return NacosSearchBundle.message(key, *params)
    }
    
    /**
     * Check if a language is supported
     */
    fun isLanguageSupported(languageCode: String): Boolean {
        return SupportedLanguage.values().any { it.code == languageCode }
    }
    
    /**
     * Get all supported languages
     */
    fun getSupportedLanguages(): List<SupportedLanguage> {
        return SupportedLanguage.values().toList()
    }
}

/**
 * Interface for listening to language changes
 */
fun interface LanguageChangeListener {
    /**
     * Called when the language is changed
     */
    fun onLanguageChanged(newLanguage: LanguageService.SupportedLanguage)
}