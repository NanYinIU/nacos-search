package com.nanyin.nacos.search.psi

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.nanyin.nacos.search.NacosIcons
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.nanyin.nacos.search.services.NacosApiService
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.CacheSnapshot
import com.nanyin.nacos.search.services.NavigationIndexRefreshService
import com.nanyin.nacos.search.services.NamespaceIndexRefreshService
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.allowCrossNamespaceNavigation
import com.nanyin.nacos.search.settings.captureSelectedAccessIdentity
import com.nanyin.nacos.search.settings.selectedNacosNamespaceId
import com.nanyin.nacos.search.settings.selectedNacosProfileId
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
    private val refreshObserver: (Project, NacosCodeContext) -> Unit
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
        val project = anchor.project
        // One snapshot for the whole decision: every hit it reports and the
        // absence check that follows are judged against a single as-of instant,
        // so a list of matches cannot straddle a freshness boundary (issue #64).
        val snapshot = ApplicationManager.getApplication().getService(CacheService::class.java)
            .snapshot(project.captureSelectedAccessIdentity())
        val resolution = currentResolution(project, snapshot, placeholder.key, codeContext)
        // STALE is resolved-but-aged: render the amber icon without network.
        // Only states that cannot decide from cache may start a sweep (issue #145).
        if (resolution.status.mayRequestBackgroundRefresh()) {
            refreshObserver(project, codeContext)
        }

        // Only show the marker if the key is in the cache or a dataId context
        // is available for remote lookup fallback.
        if (!shouldShowMarker(project, snapshot, resolution, codeContext)) return null

        val presentation = markerPresentation(resolution)
        // The icon for 格式不参与解析 is the one member of the family drawn
        // without a jump arrow, and that has to be true of the marker and not
        // only of the picture: a click that fetched the body anyway would reopen
        // the very leak the terminal state closes (issue #172).
        val navigationHandler: GutterIconNavigationHandler<PsiElement>? =
            if (resolution.status == ConfigReferenceStatus.FORMAT_NOT_PARSED) {
                null
            } else {
                GutterIconNavigationHandler { _, _ ->
                    navigateFromCode(anchor, literal, placeholder.key, codeContext)
                }
            }
        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            presentation.icon,
            { NacosSearchBundle.message(presentation.tooltipKey) },
            navigationHandler,
            GutterIconRenderer.Alignment.RIGHT,
            { "nacos.value.${placeholder.key}" }
        )
    }

    private fun shouldShowMarker(
        project: Project,
        snapshot: CacheSnapshot,
        resolution: ConfigResolution,
        codeContext: NacosCodeContext
    ): Boolean {
        if (resolution.hits.isNotEmpty()) return true
        // Hard declared-Data-ID miss: key proven absent from the named source
        // (or the source itself proven absent). No icon — do not paint gray
        // noise or invite a click that would soft-navigate elsewhere.
        if (resolution.status == ConfigReferenceStatus.UNRESOLVED) return false
        val dataId = codeContext.dataId ?: return false
        return keyIndexService().isDataIdKnown(
            snapshot,
            dataId,
            activeNamespaceId = effectiveNamespaceId(project, codeContext)
        )
    }

    private fun currentResolution(
        project: Project,
        snapshot: CacheSnapshot,
        key: String,
        codeContext: NacosCodeContext
    ): ConfigResolution =
        keyIndexService().resolveCurrentState(
            snapshot,
            key,
            preferredDataId = codeContext.dataId,
            allowCrossNamespace = project.allowCrossNamespaceNavigation(),
            activeNamespaceId = project.selectedNacosNamespaceId(),
            formatOverride = codeContext.runtimeFormatOverride()
        )

    private fun keyIndexService(): NacosKeyIndexService =
        ApplicationManager.getApplication().getService(NacosKeyIndexService::class.java)

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
                val items = results.mapNotNull { it.element as? NacosConfigKeyElement }
                    .map { NacosConfigChoiceItem(it, namespaceDisplayName(it.namespaceId)) }
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
            val profileId = project.selectedNacosProfileId(settings)
            val namespaceId = effectiveNamespaceId(project, codeContext)
            val accessIdentity = project.captureSelectedAccessIdentity(settings)
            val operationContext = settings.captureOperationContext(profileId).getOrNull()
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
                // The same reading the gutter decided with: a caret placed by
                // the data id's format while the marker resolved by the site's
                // declaration would land on a line for a key that never matched.
                val lineIndex = keysUnderRuntimeFormat(
                    cached,
                    codeContext.runtimeFormatOverride(),
                    coordinateNamespaceId = namespaceId
                )[key]?.lineIndex
                    ?: ConfigKeyExtractor.LINE_NOT_FOUND
                // Lazy-load already chose [namespaceId] as the coordinate to
                // fetch under — pass it through so go-to does not fall back to
                // a missing payload tenant and switch the tool window to public
                // (issue #190).
                NacosConfigNavigator.navigate(project, cached, lineIndex, namespaceId)
                return@executeOnPooledThread
            }
            val observed = runBlocking {
                apiService.getConfiguration(
                    dataId = dataId,
                    group = group,
                    namespaceId = namespaceId,
                    useCache = true,
                    operationContext = operationContext
                ).getOrNull()
            }
            val config = observed?.value ?: return@executeOnPooledThread

            // Nothing is written back here. The read above already cached the
            // configuration through the gateway, and this layer now addresses
            // the same key space it wrote under, so a write of our own would be
            // a plain duplicate — and one this layer is not entitled to make
            // (ADR-0052). It used to be neither, only because an AUTO profile
            // left the two spaces apart (issue #72).

            // Re-derive the identity: on an AUTO profile that read may have been
            // the first to resolve the generation for this session, so the
            // identity captured before it can no longer name where the entry
            // landed. Publishing the index under the stale one would build it
            // for a key space nothing writes to (ADR-0053).
            val resolvedIdentity = project.captureSelectedAccessIdentity(settings)

            // Rebuild the key index synchronously. We are on a pooled thread
            // (never the highlighter/dispatch thread), so a blocking rebuild is
            // safe and makes hasKey()/resolve() reflect the freshly cached
            // config immediately.
            ApplicationManager.getApplication()
                .getService(NavigationIndexRefreshService::class.java)
                .refresh(resolvedIdentity, project)

            val lineIndex = keysUnderRuntimeFormat(
                config,
                codeContext.runtimeFormatOverride(),
                coordinateNamespaceId = namespaceId
            )[key]?.lineIndex
                ?: ConfigKeyExtractor.LINE_NOT_FOUND
            NacosConfigNavigator.navigate(project, config, lineIndex, namespaceId)

        }
    }

    private fun namespaceDisplayName(namespaceId: String): String {
        val normalized = namespaceId.takeIf { it.isNotBlank() && it != "public" } ?: ""
        return if (normalized.isBlank()) "public" else namespaceId
    }

    companion object {
        private data class MarkerPresentation(
            val icon: javax.swing.Icon,
            val tooltipKey: String
        )

        private fun markerPresentation(resolution: ConfigResolution): MarkerPresentation =
            when (resolution.status) {
                ConfigReferenceStatus.RESOLVED -> MarkerPresentation(
                    NacosIcons.GutterConfig,
                    "nacosvalue.marker.tooltip.resolved"
                )
                ConfigReferenceStatus.STALE -> MarkerPresentation(
                    NacosIcons.GutterConfigStale,
                    "nacosvalue.marker.tooltip.stale"
                )
                ConfigReferenceStatus.FORMAT_NOT_PARSED -> MarkerPresentation(
                    NacosIcons.GutterConfigFormatNotParsed,
                    formatNotParsedTooltipKey(resolution.formatRefusal)
                )
                ConfigReferenceStatus.UNDECIDABLE -> MarkerPresentation(
                    NacosIcons.GutterConfigUnresolved,
                    // A block hides the identity or the evaluation Namespace:
                    // the undecidable tooltip must say why, or it would claim
                    // the data id is known when it may merely be hidden
                    // (issue #126).
                    if (resolution.visibilityBlocked) {
                        "nacosvalue.marker.tooltip.undecidable.blocked"
                    } else {
                        "nacosvalue.marker.tooltip.undecidable"
                    }
                )
                ConfigReferenceStatus.UNRESOLVED, ConfigReferenceStatus.UNAVAILABLE -> MarkerPresentation(
                    NacosIcons.GutterConfigUnresolved,
                    "nacosvalue.marker.tooltip.unresolved"
                )
            }

        /**
         * One icon for 格式不参与解析, two explanations: the outcome is the same
         * either way, but the user's next action is not. A suffix no runtime
         * reads for keys leaves nothing to do; a data id that names no format
         * at all can be renamed. The two reasons that leave nothing to do share
         * a string — a fourth gray variant would be indistinguishable at gutter
         * size, and "we do not parse XML yet" is not a next action either
         * (issue #172).
         */
        private fun formatNotParsedTooltipKey(reason: KeyExtraction.Reason?): String = when (reason) {
            KeyExtraction.Reason.FORMAT_UNDETERMINED ->
                "nacosvalue.marker.tooltip.formatNotParsed.undetermined"
            KeyExtraction.Reason.KNOWN_NON_PARSING_FORMAT,
            KeyExtraction.Reason.PARSER_NOT_IMPLEMENTED,
            null -> "nacosvalue.marker.tooltip.formatNotParsed"
        }

        private fun effectiveNamespaceId(project: Project, codeContext: NacosCodeContext): String? =
            if (project.allowCrossNamespaceNavigation()) {
                codeContext.namespaceId ?: project.selectedNacosNamespaceId()
            } else {
                project.selectedNacosNamespaceId()
            }

        private fun requestBackgroundRefresh(
            project: Project,
            codeContext: NacosCodeContext
        ) {
            try {
                val application = ApplicationManager.getApplication()
                val settings = application.getService(NacosSettings::class.java)
                if (!settings.cacheEnabled) return
                val namespaceId = effectiveNamespaceId(project, codeContext)
                application.getService(NamespaceIndexRefreshService::class.java)
                    .requestIfNeeded(
                        project.captureSelectedAccessIdentity(settings),
                        namespaceId.orEmpty(),
                        project
                    )
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
