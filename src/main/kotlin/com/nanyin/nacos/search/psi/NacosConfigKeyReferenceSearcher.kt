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
 * supported annotation. This keeps the config-detail gutter rendering
 * sub-second even on 100k-line codebases.
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
        findUsages(project, key, parameters.effectiveSearchScope as? GlobalSearchScope ?: GlobalSearchScope.projectScope(project))
            .forEach { consumer.process(it) }
        return true
    }

    companion object {
        /**
         * Result cache: (configIdentity + md5 + psiModCount) -> set of used keys.
         * Avoids re-querying the index when reopening the same config with no
         * code changes (plan section 9.3).
         */
        private data class UsedKeysCacheKey(
            val configKey: String,
            val md5: String?,
            val psiModCount: Long
        )

        @Volatile
        private var usedKeysCache: Pair<UsedKeysCacheKey, Set<String>>? = null

        fun hasUsages(
            project: Project,
            key: String,
            scope: GlobalSearchScope = GlobalSearchScope.projectScope(project)
        ): Boolean = findUsages(project, key, scope).isNotEmpty()

        fun findUsages(
            project: Project,
            key: String,
            scope: GlobalSearchScope = GlobalSearchScope.projectScope(project)
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
                    references.add(
                        NacosValueReference(
                            literal,
                            key,
                            NacosCodeContextExtractor.fromLiteral(literal)
                        )
                    )
                }
            }
            references
        }

        /**
         * Returns the subset of [keys] that are referenced by at least one
         * @Value / @NacosValue annotation in the project.
         *
         * Uses the persistent [NacosPlaceholderIndex] for the initial key
         * existence check (O(keys) hash lookups), then verifies candidates
         * via PSI only for keys the index reports as present. Results are
         * cached per (config, md5, psiModificationCount).
         */
        fun findUsedKeys(
            project: Project,
            keys: Collection<String>,
            configIdentity: String = "",
            configMd5: String? = null
        ): Set<String> {
            if (keys.isEmpty()) return emptySet()

            // Check cache
            val psiModCount = currentPsiModCount(project)
            val cacheKey = UsedKeysCacheKey(configIdentity, configMd5, psiModCount)
            usedKeysCache?.let { (cachedKey, cachedValue) ->
                if (cachedKey == cacheKey) return cachedValue
            }

            val result = ReadAction.compute<Set<String>, RuntimeException> {
                findUsedKeysViaIndex(project, keys)
            }

            usedKeysCache = cacheKey to result
            return result
        }

        private fun findUsedKeysViaIndex(project: Project, keys: Collection<String>): Set<String> {
            val fbi = FileBasedIndex.getInstance()
            val scope = GlobalSearchScope.projectScope(project)
            val found = linkedSetOf<String>()
            for (key in keys) {
                if (key.isBlank()) continue
                val values = fbi.getValues(NacosPlaceholderIndex.INDEX_ID, key, scope)
                if (values.isNotEmpty()) {
                    // Index reports at least one file; PSI-verify to filter
                    // regex false positives (e.g. placeholder in a comment).
                    if (verifyKeyInProject(project, key, scope)) {
                        found.add(key)
                    }
                }
            }
            return found
        }

        /**
         * PSI-verify that [key] appears in a supported annotation in at least
         * one candidate file.
         */
        private fun verifyKeyInProject(
            project: Project,
            key: String,
            scope: GlobalSearchScope
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
                    if (placeholder.key == key && NacosValueReferenceContributor.isInSupportedAnnotation(literal)) {
                        return true
                    }
                }
            }
            return false
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
