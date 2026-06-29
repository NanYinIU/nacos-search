package com.nanyin.nacos.search.psi

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.nanyin.nacos.search.NacosIcons
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.runBlocking

/**
 * Renders a gutter icon next to `@NacosValue` / `@Value` annotations whose
 * `value` is a `${...}` placeholder. Clicking it resolves the placeholder to
 * the cached Nacos configuration (via [NacosValueReference]) and navigates
 * there, falling back to the standard "go to declaration" list when multiple
 * definitions exist.
 */
class NacosValueLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val literal = literalForLeaf(element) ?: return null
        return markerForLiteral(literal, element)
    }

    private fun literalForLeaf(element: PsiElement): PsiLiteralExpression? {
        val literal = element.parent as? PsiLiteralExpression ?: return null
        return if (literal.firstChild == element) literal else null
    }

    private fun markerForLiteral(literal: PsiLiteralExpression, anchor: PsiElement): LineMarkerInfo<PsiElement>? {
        val text = literal.value as? String ?: return null
        val placeholder = PlaceholderParser.parse(text) ?: return null

        if (!NacosValueReferenceContributor.isInSupportedAnnotation(literal)) return null
        val codeContext = NacosCodeContextExtractor.fromLiteral(literal)

        // Only show the marker if the key is in the cache or a dataId context
        // is available for remote lookup fallback.
        if (!shouldShowMarker(placeholder.key, codeContext)) return null

        // Resolvability picks the visual state: solid arrow when the key is
        // already cached, hollow "unresolved" when only a dataId hint exists.
        val resolvable = isResolvable(placeholder.key)
        val icon = if (resolvable) NacosIcons.GutterConfig else NacosIcons.GutterConfigUnresolved
        val tooltipKey =
            if (resolvable) "nacosvalue.marker.tooltip.resolved" else "nacosvalue.marker.tooltip.unresolved"
        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            icon,
            { NacosSearchBundle.message(tooltipKey) },
            { _, _ ->
                navigateFromCode(anchor, literal, placeholder.key, codeContext)
            },
            GutterIconRenderer.Alignment.RIGHT,
            { "nacos.value.${placeholder.key}" }
        )
    }

   private fun shouldShowMarker(key: String, codeContext: NacosCodeContext): Boolean {
       if (NacosKeyResolver.hasKey(key, activeServerUrl = NacosKeyResolver.currentServerUrl())) return true
       val dataId = codeContext.dataId ?: return false
       // Only show the unresolved marker when the dataId is known to exist in
       // the cache. When the namespace has been loaded but the dataId is
       // absent, the config almost certainly doesn't exist in Nacos, so a
       // dead-end marker would be misleading. Returns true optimistically on a
       // cold/empty cache so lazy-loading still works.
       return NacosKeyResolver.isDataIdKnown(dataId, activeServerUrl = NacosKeyResolver.currentServerUrl())
   }

    private fun isResolvable(key: String): Boolean {
        return NacosKeyResolver.hasKey(key, activeServerUrl = NacosKeyResolver.currentServerUrl())
    }

    private fun navigateFromCode(
        anchor: PsiElement,
        literal: PsiLiteralExpression,
        key: String,
        codeContext: NacosCodeContext
    ) {
        val ref = NacosValueReference(literal, key, codeContext)
        val results = ref.multiResolve(false)
        when {
            results.size == 1 -> {
                val el = results.first().element
                if (el is com.intellij.pom.Navigatable) el.navigate(true)
            }
            results.size > 1 -> {
                val elements = results.mapNotNull { it.element }.toTypedArray()
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(elements.toList())
                    .setTitle("Choose Nacos configuration")
                    .setItemChosenCallback { (it as? com.intellij.pom.Navigatable)?.navigate(true) }
                    .createPopup()
                    .showCenteredInCurrentWindow(anchor.project)
            }
            else -> lazyLoadAndNavigate(anchor, key, codeContext)
        }
    }

    private fun lazyLoadAndNavigate(anchor: PsiElement, key: String, codeContext: NacosCodeContext) {
        val dataId = codeContext.dataId ?: return
        val group = codeContext.group ?: "DEFAULT_GROUP"
        val project = anchor.project
        ApplicationManager.getApplication().executeOnPooledThread {
            val apiService = ApplicationManager.getApplication().getService(NacosApiService::class.java)
            val cacheService = ApplicationManager.getApplication().getService(CacheService::class.java)
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            val config = runBlocking {
                apiService.getConfiguration(
                    dataId = dataId,
                    group = group,
                    namespaceId = codeContext.namespaceId,
                    useCache = true
                ).getOrNull()
            } ?: return@executeOnPooledThread

            // Ensure the fetched config lives in the detail cache so the key
            // index can see it. getConfiguration() only persists when
            // cacheEnabled is on, but the gutter marker relies on the cache to
            // decide resolved vs. unresolved, so we write it unconditionally.
            runBlocking {
                cacheService.putConfigDetail(
                    settings.serverUrl,
                    codeContext.namespaceId,
                    config,
                    settings.getCacheTtlMillis()
                )
            }

            // Rebuild the key index synchronously. We are on a pooled thread
            // (never the highlighter/dispatch thread), so a blocking rebuild is
            // safe and makes hasKey()/resolve() reflect the freshly cached
            // config immediately.
            NacosKeyResolver.rebuildBlocking(cacheService, NacosKeyResolver.currentServerUrl())

            val lineIndex = ConfigKeyExtractor.extract(config)[key]?.lineIndex ?: -1
            NacosConfigNavigator.navigate(project, config, lineIndex)

            // Re-run the highlighter pass so the gutter icon re-renders: the
            // previously hollow (unresolved) marker becomes solid now that the
            // key is resolvable.
            ApplicationManager.getApplication().invokeLater {
                DaemonCodeAnalyzer.getInstance(project).restart()
            }
        }
    }
}
