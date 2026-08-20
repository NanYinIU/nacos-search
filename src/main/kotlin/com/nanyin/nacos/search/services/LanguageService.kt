package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.settings.NacosSettings
import java.awt.GraphicsEnvironment
import java.util.*
import com.intellij.openapi.application.ModalityState
import com.nanyin.nacos.search.Edt
/**
 * Service for managing language settings and localization
 */
@Service(Level.APP)
class LanguageService {
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)

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
     * Notify all listeners about language change.
     *
     * One mechanism only: subscribers take [NacosLanguageListener.TOPIC]. Each
     * re-reads the bundle itself and owns the thread it touches Swing on.
     */
    private fun notifyLanguageChanged(newLanguage: SupportedLanguage) {
        try {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(NacosLanguageListener.TOPIC)
                .languageChanged()
            showLanguageChangeNotification(newLanguage)
        } catch (e: Exception) {
            // Log error but don't fail the language change
            e.printStackTrace()
        }
    }

    /**
     * Show a notification about language change
     */
    private fun showLanguageChangeNotification(newLanguage: SupportedLanguage) {
        if (GraphicsEnvironment.isHeadless()) {
            return
        }

        val message = NacosSearchBundle.message(
            "language.changed.notification",
            newLanguage.displayName
        )

        Edt.invokeOnEdt(ModalityState.defaultModalityState()) {
            javax.swing.JOptionPane.showMessageDialog(
                null,
                message,
                NacosSearchBundle.message("language.changed.title"),
                javax.swing.JOptionPane.INFORMATION_MESSAGE
            )
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