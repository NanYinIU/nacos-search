package com.nanyin.nacos.search.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.settings.NacosSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@TestApplication
class LanguageServiceTest {

    private lateinit var languageService: LanguageService
    private lateinit var settings: NacosSettings

    @BeforeEach
    fun setUp() {
        settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
        settings.resetToDefaults()
        languageService = LanguageService()
    }

    @Test
    fun `test default language is English`() {
        assertEquals(LanguageService.SupportedLanguage.ENGLISH, languageService.getCurrentLanguage())
    }

    @Test
    fun `test setLanguage to Chinese`() {
        languageService.setLanguage("zh")
        assertEquals(LanguageService.SupportedLanguage.CHINESE, languageService.getCurrentLanguage())
    }

    @Test
    fun `test setLanguage to English`() {
        settings.language = "zh"
        languageService.setLanguage("en")
        assertEquals(LanguageService.SupportedLanguage.ENGLISH, languageService.getCurrentLanguage())
    }

    @Test
    fun `test setLanguage with invalid code defaults to English`() {
        languageService.setLanguage("invalid")
        assertEquals(LanguageService.SupportedLanguage.ENGLISH, languageService.getCurrentLanguage())
    }

    @Test
    fun `test language change publishes on the language topic`() {
        val subscription = Disposer.newDisposable("language-topic-test")
        try {
            val notifications = countNotifications(subscription)

            languageService.setLanguage("zh")

            assertEquals(1, notifications.get())
        } finally {
            Disposer.dispose(subscription)
        }
    }

    @Test
    fun `test setLanguage does not notify when language unchanged`() {
        val subscription = Disposer.newDisposable("language-topic-test")
        try {
            val notifications = countNotifications(subscription)

            languageService.setLanguage("en")
            languageService.setLanguage("en")

            assertEquals(0, notifications.get())
        } finally {
            Disposer.dispose(subscription)
        }
    }

    private fun countNotifications(subscription: Disposable): AtomicInteger {
        val count = AtomicInteger()
        ApplicationManager.getApplication().messageBus.connect(subscription)
            .subscribe(NacosLanguageListener.TOPIC, object : NacosLanguageListener {
                override fun languageChanged() {
                    count.incrementAndGet()
                }
            })
        return count
    }
}
