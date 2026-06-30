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
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.settings.NacosSettings
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

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
        return NacosKeyResolver.hasKey(
            key,
            activeServerUrl = NacosKeyResolver.currentServerUrl(),
            allowCrossNamespace = crossNamespaceEnabled()
        )
    }

    private fun crossNamespaceEnabled(): Boolean =
        try {
            ApplicationManager.getApplication()
                ?.getService(NacosSettings::class.java)
                ?.getActiveServer()
                ?.allowCrossNamespaceNavigation == true
        } catch (e: Exception) {
            false
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
                val namespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
                val items = results.mapNotNull { it.element as? NacosConfigKeyElement }
                    .map { NacosConfigChoiceItem(it, namespaceDisplayName(namespaceService, it.namespaceId)) }
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(items)
                    .setTitle("Choose Nacos configuration")
                    .setRenderer(NacosConfigChoiceRenderer())
                    .setItemChosenCallback { it.navigate(true) }
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
            val namespaceService = ApplicationManager.getApplication().getService(NamespaceService::class.java)
            val namespaceId = if (settings.getActiveServer().allowCrossNamespaceNavigation) {
                codeContext.namespaceId
            } else {
                namespaceService.getCurrentNamespace()?.namespaceId
            }
            val config = runBlocking {
                apiService.getConfiguration(
                    dataId = dataId,
                    group = group,
                    namespaceId = namespaceId,
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
                    namespaceId,
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

    private fun namespaceDisplayName(namespaceService: NamespaceService, namespaceId: String): String {
        val normalized = namespaceId.takeIf { it.isNotBlank() && it != "public" } ?: ""
        return namespaceService.findNamespaceById(normalized)?.getDisplayName()
            ?: if (normalized.isBlank()) "public" else namespaceId
    }

    private class NacosConfigChoiceRenderer : JPanel(BorderLayout()), ListCellRenderer<NacosConfigChoiceItem> {
        private val primary = JLabel()
        private val secondary = JLabel()

        init {
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
            primary.font = primary.font.deriveFont(Font.PLAIN)
            secondary.font = secondary.font.deriveFont(Font.PLAIN, secondary.font.size2D - 1f)
            add(primary, BorderLayout.NORTH)
            add(secondary, BorderLayout.SOUTH)
            isOpaque = true
        }

        override fun getListCellRendererComponent(
            list: JList<out NacosConfigChoiceItem>,
            value: NacosConfigChoiceItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            primary.text = value.primaryText
            secondary.text = value.secondaryText
            background = if (isSelected) list.selectionBackground else list.background
            foreground = if (isSelected) list.selectionForeground else list.foreground
            primary.foreground = foreground
            secondary.foreground = if (isSelected) list.selectionForeground else com.intellij.ui.JBColor.GRAY
            return this
        }
    }
}
