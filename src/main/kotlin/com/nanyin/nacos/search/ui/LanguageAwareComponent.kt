package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.services.LanguageService

/**
 * Interface for UI components that can respond to language changes
 */
interface LanguageAwareComponent {
    /**
     * Called when the language is changed
     */
    fun onLanguageChanged(newLanguage: LanguageService.SupportedLanguage)
    
    /**
     * Get the current language service
     */
    fun getLanguageService(): LanguageService
}