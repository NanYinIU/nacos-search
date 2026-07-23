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
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.NavigationIndexRefreshService
import com.nanyin.nacos.search.services.NamespaceService
import com.nanyin.nacos.search.services.NamespaceIndexRefreshService
import com.nanyin.nacos.search.services.captureNamespaceIndexRequest
import com.nanyin.nacos.search.services.captureAccessIdentity
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
class NacosValueLineMarkerProvider internal constructor(
    private val refreshObserver: (com.intellij.openapi.project.Project, NacosCodeContext) -> Unit
) : LineMarkerProvider {
    constructor() : this(::requestBackgroundRefresh)

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
        val resolution = currentResolution(placeholder.key)
        if (resolution.status != ConfigReferenceStatus.RESOLVED) {
            refreshObserver(anchor.project, codeContext)
        }

        // Only show the marker if the key is in the cache or a dataId context
        // is available for remote lookup fallback.
        if (!shouldShowMarker(resolution, codeContext)) return null

        val presentation = markerPresentation(resolution.status)
        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            presentation.icon,
            { NacosSearchBundle.message(presentation.tooltipKey) },
            { _, _ ->
                navigateFromCode(anchor, literal, placeholder.key, codeContext)
            },
            GutterIconRenderer.Alignment.RIGHT,
            { "nacos.value.${placeholder.key}" }
        )
    }

   private fun shouldShowMarker(resolution: ConfigResolution, codeContext: NacosCodeContext): Boolean {
       if (resolution.hits.isNotEmpty()) return true
       val dataId = codeContext.dataId ?: return false
       return NacosKeyResolver.isDataIdKnown(
           dataId,
           activeNamespaceId = effectiveNamespaceId(codeContext),
           activeIdentity = currentAccessIdentity()
       )
   }

    private fun currentResolution(key: String): ConfigResolution =
        NacosKeyResolver.resolveCurrentState(
            key,
            allowCrossNamespace = crossNamespaceEnabled(),
            activeIdentity = currentAccessIdentity()
        )

    private fun currentAccessIdentity() =
        ApplicationManager.getApplication().getService(NacosSettings::class.java).captureAccessIdentity()

    private fun effectiveNamespaceId(codeContext: NacosCodeContext): String? =
        if (crossNamespaceEnabled()) {
            codeContext.namespaceId
        } else {
            ApplicationManager.getApplication()
                .getService(NamespaceService::class.java)
                .getCurrentNamespace()?.namespaceId
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
            val accessIdentity = settings.captureAccessIdentity()
            val cached = runBlocking {
                cacheService.getConfigDetail(
                    accessIdentity,
                    namespaceId,
                    dataId,
                    group,
                    allowStale = true
                )
            }
            if (cached != null) {
                val lineIndex = ConfigKeyExtractor.extract(cached)[key]?.lineIndex ?: -1
                NacosConfigNavigator.navigate(project, cached, lineIndex)
                return@executeOnPooledThread
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
                    accessIdentity,
                    namespaceId,
                    config,
                    settings.getCacheTtlMillis()
                )
            }

            // Rebuild the key index synchronously. We are on a pooled thread
            // (never the highlighter/dispatch thread), so a blocking rebuild is
            // safe and makes hasKey()/resolve() reflect the freshly cached
            // config immediately.
            ApplicationManager.getApplication()
                .getService(NavigationIndexRefreshService::class.java)
                .refresh(accessIdentity, project)

            val lineIndex = ConfigKeyExtractor.extract(config)[key]?.lineIndex ?: -1
            NacosConfigNavigator.navigate(project, config, lineIndex)

        }
    }

    private fun namespaceDisplayName(namespaceService: NamespaceService, namespaceId: String): String {
        val normalized = namespaceId.takeIf { it.isNotBlank() && it != "public" } ?: ""
        return namespaceService.findNamespaceById(normalized)?.getDisplayName()
            ?: if (normalized.isBlank()) "public" else namespaceId
    }

    companion object {
        private data class MarkerPresentation(
            val icon: javax.swing.Icon,
            val tooltipKey: String
        )

        private fun markerPresentation(status: ConfigReferenceStatus): MarkerPresentation = when (status) {
            ConfigReferenceStatus.RESOLVED -> MarkerPresentation(
                NacosIcons.GutterConfig,
                "nacosvalue.marker.tooltip.resolved"
            )
            ConfigReferenceStatus.STALE -> MarkerPresentation(
                NacosIcons.GutterConfigStale,
                "nacosvalue.marker.tooltip.stale"
            )
            ConfigReferenceStatus.UNRESOLVED, ConfigReferenceStatus.UNAVAILABLE -> MarkerPresentation(
                NacosIcons.GutterConfigUnresolved,
                "nacosvalue.marker.tooltip.unresolved"
            )
        }

        private fun requestBackgroundRefresh(
            project: com.intellij.openapi.project.Project,
            codeContext: NacosCodeContext
        ) {
            try {
                val application = ApplicationManager.getApplication()
                val settings = application.getService(NacosSettings::class.java)
                if (!settings.cacheEnabled) return
                val namespaceId = if (settings.getActiveServer().allowCrossNamespaceNavigation) {
                    codeContext.namespaceId
                } else {
                    application.getService(NamespaceService::class.java)
                        .getCurrentNamespace()?.namespaceId
                }
                application.getService(NamespaceIndexRefreshService::class.java)
                    .requestIfNeeded(settings.captureAccessIdentity(namespaceId), namespaceId.orEmpty(), project)
            } catch (_: Exception) {
                // Gutter calculation is best-effort and must never fail PSI analysis.
            }
        }
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
