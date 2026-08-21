package com.nanyin.nacos.search.psi

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.KeyDescriptor

/**
 * Persistent file-based index mapping Spring/Nacos placeholder keys
 * (e.g. `app.config.timeout`) extracted from @Value / @NacosValue
 * annotations to the set of .java files that reference them.
 *
 * Replaces the per-config-open full-project PsiSearchHelper scan with
 * O(keys) in-memory hash lookups, keeping 100k-line-codebase config-detail
 * opening well under one second.
 *
 * The [Indexer] locates `@Value` / `@NacosValue` argument lists with a
 * lightweight regex rather than PSI (which would be too slow during indexing),
 * then reuses [PlaceholderParser] for the `${key[:default]}` grammar. False
 * positives are filtered by PSI verification in
 * [NacosConfigKeyReferenceSearcher] at query time.
 */
class NacosPlaceholderIndex : FileBasedIndexExtension<String, PlaceholderMarker>() {

    override fun getName(): ID<String, PlaceholderMarker> = INDEX_ID

    override fun getVersion(): Int = 2

    override fun dependsOnFileContent(): Boolean = true

    override fun getIndexer(): DataIndexer<String, PlaceholderMarker, FileContent> = Indexer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> file.fileType is JavaFileType }

    override fun getKeyDescriptor(): KeyDescriptor<String> = FileIndexStringKeyDescriptor

    override fun getValueExternalizer(): DataExternalizer<PlaceholderMarker> =
        placeholderMarkerExternalizer

    companion object {
        val INDEX_ID: ID<String, PlaceholderMarker> = ID.create("nacos.placeholder.keys")
    }
}

/** Singleton value stored for each indexed key — avoids Kotlin Void null interop. */
object PlaceholderMarker {
    private const val serialVersionUID = 1L
}

/**
 * Text-based extractor: finds `@Value` / `@NacosValue` argument lists and
 * emits keys decided by [PlaceholderParser]. Avoids PSI in the indexing hot path.
 */
object Indexer : DataIndexer<String, PlaceholderMarker, FileContent> {
    private val valueAnnotation = Regex(
        """@(?:[\w.]*\.)?(?:NacosValue|Value)\s*\(([^)]*)\)"""
    )

    override fun map(inputData: FileContent): MutableMap<String, PlaceholderMarker> {
        val map = HashMap<String, PlaceholderMarker>()
        for (k in extractPlaceholderKeys(inputData.contentAsText.toString())) {
            map[k] = PlaceholderMarker
        }
        return map
    }

    /**
     * Pure extraction logic returning the set of placeholder keys found
     * in [text]. Testable without FileContent or Void interop.
     */
    fun extractPlaceholderKeys(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val result = linkedSetOf<String>()
        for (match in valueAnnotation.findAll(text)) {
            PlaceholderParser.parse(match.groupValues[1])?.let { result.add(it.key) }
        }
        return result
    }
}

private val placeholderMarkerExternalizer =
    FileIndexMarkerExternalizer(PlaceholderMarker, "Invalid placeholder marker")
