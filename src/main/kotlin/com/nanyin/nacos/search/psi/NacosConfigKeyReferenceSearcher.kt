package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor

/**
 * Feeds "Find Usages" for Nacos configuration keys.
 *
 * Uses the persistent [NacosPlaceholderIndex] (FileBasedIndex) for O(1)
 * key-to-file lookups instead of per-key full-project PSI scans. Candidate
 * files are then PSI-verified to confirm the placeholder lives inside a
 * supported annotation, and (when the search is anchored on a concrete
 * configuration) that the usage site's declared `@NacosPropertySource` dataId
 * does not hard-constrain the placeholder to a *different* Data ID — matching
 * the forward hard-filter so reverse navigation cannot land on a usage that
 * would refuse this configuration as a declaration target.
 *
 * This keeps the config-detail gutter rendering sub-second even on 100k-line
 * codebases.
 */
class NacosConfigKeyReferenceSearcher :
    QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> {

    override fun execute(
        parameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>
    ): Boolean {
        val target = parameters.elementToSearch as? NacosConfigKeyElement ?: return true
        val project = target.project
        val key = target.key
        // Respect the QueryExecutor contract: stop as soon as the consumer declines
        // (process() == false), so Find Usages can short-circuit instead of always
        // materializing the full reference list.
        val scope = parameters.effectiveSearchScope as? GlobalSearchScope
            ?: GlobalSearchScope.projectScope(project)
        for (ref in findUsages(project, key, scope, sourceDataId = target.config.dataId)) {
            if (!consumer.process(ref)) return false
        }
        return true
    }

    companion object {
        /**
         * Result cache: (configIdentity + md5 + sourceDataId + psiModCount) ->
         * set of used keys. Avoids re-querying the index when reopening the same
         * config with no code changes (plan section 9.3).
         */
        private data class UsedKeysCacheKey(
            val configKey: String,
            val md5: String?,
            val sourceDataId: String?,
            val psiModCount: Long
        )

        @Volatile
        private var usedKeysCache: Pair<UsedKeysCacheKey, Set<String>>? = null

        fun hasUsages(
            project: Project,
            key: String,
            scope: GlobalSearchScope = GlobalSearchScope.projectScope(project),
            sourceDataId: String? = null
        ): Boolean = findUsages(project, key, scope, sourceDataId).isNotEmpty()

        fun findUsages(
            project: Project,
            key: String,
            scope: GlobalSearchScope = GlobalSearchScope.projectScope(project),
            sourceDataId: String? = null
        ): List<PsiReference> = ReadAction.compute<List<PsiReference>, RuntimeException> {
            val fbi = FileBasedIndex.getInstance()
            val files = fbi.getContainingFiles(
                NacosPlaceholderIndex.INDEX_ID, key, scope
            )
            if (files.isEmpty()) return@compute emptyList()

            val psiManager = PsiManager.getInstance(project)
            val references = mutableListOf<PsiReference>()
            for (vf in files) {
                val psiFile = psiManager.findFile(vf) ?: continue
                val literals = PsiTreeUtil.findChildrenOfType(psiFile, PsiLiteralExpression::class.java)
                for (literal in literals) {
                    val text = literal.value as? String ?: continue
                    val placeholder = PlaceholderParser.parse(text) ?: continue
                    if (placeholder.key != key) continue
                    if (!NacosValueReferenceContributor.isInSupportedAnnotation(literal)) continue
                    val codeContext = NacosCodeContextExtractor.fromLiteral(literal)
                    if (!acceptsUsageForSourceConfig(sourceDataId, codeContext)) continue
                    references.add(NacosValueReference(literal, key, codeContext))
                }
            }
            references
        }

        /**
         * Returns the subset of [keys] that are referenced by at least one
         * @Value / @NacosValue annotation in the project that is allowed to
         * attribute to [sourceDataId] (see [acceptsUsageForSourceConfig]).
         *
         * Uses the persistent [NacosPlaceholderIndex] for the initial key
         * existence check (O(keys) hash lookups), then verifies candidates
         * via PSI only for keys the index reports as present. Results are
         * cached per (config, md5, sourceDataId, psiModificationCount).
         */
        fun findUsedKeys(
            project: Project,
            keys: Collection<String>,
            configIdentity: String = "",
            configMd5: String? = null,
            sourceDataId: String? = null
        ): Set<String> {
            if (keys.isEmpty()) return emptySet()

            // Check cache
            val psiModCount = currentPsiModCount(project)
            val cacheKey = UsedKeysCacheKey(configIdentity, configMd5, sourceDataId, psiModCount)
            usedKeysCache?.let { (cachedKey, cachedValue) ->
                if (cachedKey == cacheKey) return cachedValue
            }

            val result = ReadAction.compute<Set<String>, RuntimeException> {
                findUsedKeysViaIndex(project, keys, sourceDataId)
            }

            usedKeysCache = cacheKey to result
            return result
        }

        private fun findUsedKeysViaIndex(
            project: Project,
            keys: Collection<String>,
            sourceDataId: String?
        ): Set<String> {
            val fbi = FileBasedIndex.getInstance()
            val scope = GlobalSearchScope.projectScope(project)
            val found = linkedSetOf<String>()
            for (key in keys) {
                if (key.isBlank()) continue
                val values = fbi.getValues(NacosPlaceholderIndex.INDEX_ID, key, scope)
                if (values.isNotEmpty()) {
                    // Index reports at least one file; PSI-verify to filter
                    // regex false positives (e.g. placeholder in a comment) and
                    // usages hard-bound to a different declared Data ID.
                    if (verifyKeyInProject(project, key, scope, sourceDataId)) {
                        found.add(key)
                    }
                }
            }
            return found
        }

        /**
         * PSI-verify that [key] appears in a supported annotation in at least
         * one candidate file, under a usage site that may attribute to
         * [sourceDataId].
         */
        private fun verifyKeyInProject(
            project: Project,
            key: String,
            scope: GlobalSearchScope,
            sourceDataId: String?
        ): Boolean {
            val fbi = FileBasedIndex.getInstance()
            val files = fbi.getContainingFiles(NacosPlaceholderIndex.INDEX_ID, key, scope)
            val psiManager = PsiManager.getInstance(project)
            for (vf in files) {
                val psiFile = psiManager.findFile(vf) ?: continue
                val literals = PsiTreeUtil.findChildrenOfType(psiFile, PsiLiteralExpression::class.java)
                for (literal in literals) {
                    val text = literal.value as? String ?: continue
                    val placeholder = PlaceholderParser.parse(text) ?: continue
                    if (placeholder.key != key) continue
                    if (!NacosValueReferenceContributor.isInSupportedAnnotation(literal)) continue
                    if (!acceptsUsageForSourceConfig(
                            sourceDataId,
                            NacosCodeContextExtractor.fromLiteral(literal)
                        )
                    ) {
                        continue
                    }
                    return true
                }
            }
            return false
        }

        /**
         * Whether a code usage of a key may be attributed to the configuration
         * whose Data ID is [sourceDataId] (reverse of the forward hard filter).
         *
         * - No source Data ID → accept every usage (unscoped search).
         * - Usage declares no Data ID → accept (site has no hard constraint).
         * - Usage declares the same Data ID → accept.
         * - Usage declares a different Data ID → reject (forward navigation
         *   would not resolve that placeholder against this configuration).
         */
        internal fun acceptsUsageForSourceConfig(
            sourceDataId: String?,
            usageContext: NacosCodeContext
        ): Boolean {
            val source = sourceDataId?.takeIf { it.isNotBlank() } ?: return true
            val declared = usageContext.dataId?.takeIf { it.isNotBlank() } ?: return true
            return declared == source
        }

        private fun currentPsiModCount(project: Project): Long {
            return try {
                PsiManager.getInstance(project)
                    .modificationTracker.modificationCount
            } catch (e: Exception) {
                0L
            }
        }
    }
}
