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
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.CacheSnapshot
import com.nanyin.nacos.search.services.NavigationDetailPrefetchService
import com.nanyin.nacos.search.services.NamespaceIndexRefreshService
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.allowCrossNamespaceNavigation
import com.nanyin.nacos.search.settings.captureSelectedAccessIdentity
import com.nanyin.nacos.search.settings.selectedNacosNamespaceId
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
/**
 * What a marker asks to be refreshed when it cannot decide from cache, or when
 * what it decided from has aged out.
 *
 * [dataId] is the one body that could answer this marker: for a resolved-but-aged
 * marker the body its own hit came from — no declaration needed — and otherwise
 * whatever its reference site declared. Null when the marker can name none, and
 * then the Namespace index is the only thing that could answer, which is why the
 * Namespace is carried either way (ADR-0057).
 */
internal data class MarkerRefreshRequest(
    val namespaceId: String?,
    val dataId: String?,
    val group: String?
)

class NacosValueLineMarkerProvider internal constructor(
    private val refreshObserver: (Project, MarkerRefreshRequest) -> Unit
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
        // The icon is always painted from cache — no state waits on a read. What
        // a state may *ask for* is one coordinate: the aged body this marker
        // resolved against, or the source its site declared (ADR-0057).
        if (resolution.status.mayRequestBackgroundRefresh()) {
            refreshObserver(project, refreshRequestFor(project, snapshot, resolution, codeContext))
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
        // (or the source itself proven absent in this Namespace). No icon —
        // do not paint gray noise or invite a click that would soft-navigate
        // elsewhere.
        if (resolution.status == ConfigReferenceStatus.UNRESOLVED) return false
        val dataId = codeContext.dataId ?: return false
        // Visibility block is undecidable, not a miss — keep the hollow icon
        // and access-refused tooltip for a declared source even when the same
        // dataId is cached under another Namespace. Without a declared dataId
        // there is still nothing to show (identity-wide block + no context).
        if (resolution.visibilityBlocked) return true
        // Hollow / terminal markers only when the declared Data ID is
        // actionable under the *session-selected* Namespace. A source that
        // exists only in another Namespace (es.properties in uxinlive while
        // public is selected) must not paint gray under the current one —
        // background refresh can still warm the active Namespace index without
        // an icon. Cold start with no multi-Namespace evidence still shows a
        // hollow marker so the first load can be driven from the gutter.
        return keyIndexService().isDeclaredSourceActionableInActiveNamespace(
            snapshot,
            dataId,
            activeNamespaceId = project.selectedNacosNamespaceId()
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

    /**
     * The narrowest thing that could answer this marker.
     *
     * A hit outranks the declaration: a resolved-but-aged marker is looking at
     * one specific body, and that body's own 配置坐标 is what has to be re-read —
     * which also means a placeholder resolved by discovery, with no
     * `@NacosPropertySource` anywhere, still refreshes at coordinate width. The
     * top hit only: it is the one the icon speaks for and the one a click
     * navigates to.
     *
     * One coordinate must get one spelling, whichever marker names it. A hit
     * carries the real group; a reference site usually declares none, and the
     * refresh is bounded per coordinate — so leaving the group unspelled here
     * made two markers on one aged configuration (one whose key resolves, one
     * whose key was added since) claim two windows and issue **two identical
     * detail reads**. The group is therefore taken from the cached body whenever
     * the cache holds one, which is exactly the case a duplicate would cost
     * something.
     */
    private fun refreshRequestFor(
        project: Project,
        snapshot: CacheSnapshot,
        resolution: ConfigResolution,
        codeContext: NacosCodeContext
    ): MarkerRefreshRequest {
        resolution.hits.firstOrNull()?.let { hit ->
            return MarkerRefreshRequest(hit.namespaceId, hit.config.dataId, hit.config.group)
        }
        val namespaceId = effectiveNamespaceId(project, codeContext)
        val dataId = codeContext.dataId?.takeIf { it.isNotBlank() }
            ?: return MarkerRefreshRequest(namespaceId, null, null)
        return MarkerRefreshRequest(
            namespaceId,
            dataId,
            codeContext.group?.takeIf { it.isNotBlank() }
                ?: NacosKeyResolver.cachedGroupFor(dataId, snapshot, namespaceId)
        )
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
            val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
            val namespaceId = effectiveNamespaceId(project, codeContext)
            val accessIdentity = project.captureSelectedAccessIdentity(settings)
            val result = runBlocking {
                ApplicationManager.getApplication()
                    .getService(NavigationDetailPrefetchService::class.java)
                    .readForNavigation(project, accessIdentity, namespaceId, dataId, group)
                    .await()
            }
            val config = result.body() ?: return@executeOnPooledThread
            // The same reading the gutter decided with: a caret placed by
            // the data id's format while the marker resolved by the site's
            // declaration would land on a line for a key that never matched.
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
            request: MarkerRefreshRequest
        ) {
            try {
                val application = ApplicationManager.getApplication()
                val settings = application.getService(NacosSettings::class.java)
                if (!settings.cacheEnabled) return
                val identity = project.captureSelectedAccessIdentity(settings)
                // Named coordinate: one detail read, bounded per coordinate per
                // TTL. The project-wide prefetch and the Namespace page walk
                // behind requestIfNeeded re-read every declared source and every
                // row in the Namespace to settle one placeholder, which the TTL
                // turned into a repeating full re-scan for as long as one marker
                // that could not decide stayed on screen (ADR-0057).
                val dataId = request.dataId
                if (!dataId.isNullOrBlank()) {
                    application.getService(NavigationDetailPrefetchService::class.java)
                        .requestCoordinate(project, identity, request.namespaceId, dataId, request.group)
                    return
                }
                // Nothing nameable: no hit to re-read and no declared source, so
                // only the Namespace index could answer. That request is now
                // cold-start-only — see NamespaceIndexRefreshService.
                application.getService(NamespaceIndexRefreshService::class.java)
                    .requestIfNeeded(identity, request.namespaceId.orEmpty(), project)
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
