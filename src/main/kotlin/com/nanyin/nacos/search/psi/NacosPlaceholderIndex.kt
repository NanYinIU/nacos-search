package com.nanyin.nacos.search.psi

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.KeyDescriptor
import java.io.DataInput
import java.io.DataOutput

/**
 * Persistent file-based index mapping Spring/Nacos placeholder keys
 * (e.g. `app.config.timeout`) extracted from @Value / @NacosValue
 * annotations to the set of .java files that reference them.
 *
 * Replaces the per-config-open full-project PsiSearchHelper scan with
 * O(keys) in-memory hash lookups, keeping 100k-line-codebase config-detail
 * opening well under one second.
 *
 * The [Indexer] uses a lightweight regex rather than PSI (which would be too
 * slow during indexing). False positives are filtered by PSI verification in
 * [NacosConfigKeyReferenceSearcher] at query time.
 */
class NacosPlaceholderIndex : FileBasedIndexExtension<String, PlaceholderMarker>() {

    override fun getName(): ID<String, PlaceholderMarker> = INDEX_ID

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getIndexer(): DataIndexer<String, PlaceholderMarker, FileContent> = Indexer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> file.fileType is JavaFileType }

    override fun getKeyDescriptor(): KeyDescriptor<String> = StringKeyDescriptor

    override fun getValueExternalizer(): DataExternalizer<PlaceholderMarker> = MarkerExternalizer

    companion object {
        val INDEX_ID: ID<String, PlaceholderMarker> = ID.create("nacos.placeholder.keys")
    }
}

/** Singleton value stored for each indexed key — avoids Kotlin Void null interop. */
object PlaceholderMarker {
    private const val serialVersionUID = 1L
}

/**
 * Text-based extractor: finds ${...} tokens near @Value / @NacosValue
 * and emits the key portion. Avoids PSI in the indexing hot path.
 */
object Indexer : DataIndexer<String, PlaceholderMarker, FileContent> {
    private val annotationPlaceholder = Regex(
        """@(?:[\w.]*\.)?(?:Value|NacosValue)\s*\([^)]*?\$\{([^}]*)\}"""
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
        val result = linkedSetOf<String>()
        for (match in annotationPlaceholder.findAll(text)) {
            val inner = match.groupValues[1].trim()
            if (inner.isEmpty()) continue
            val colon = inner.indexOf(':')
            val key = if (colon >= 0) inner.substring(0, colon).trim() else inner
            if (key.isNotEmpty()) {
                result.add(key)
            }
        }
        return result
    }
}

private object StringKeyDescriptor : KeyDescriptor<String> {
    override fun getHashCode(value: String?): Int = value?.hashCode() ?: 0
    override fun isEqual(a: String?, b: String?): Boolean = a == b
    override fun save(out: DataOutput, value: String?) {
        out.writeUTF(value ?: "")
    }
    override fun read(input: DataInput): String = input.readUTF()
}

private object MarkerExternalizer : DataExternalizer<PlaceholderMarker> {
    override fun save(out: DataOutput, value: PlaceholderMarker) {
        // Singleton — nothing to persist.
    }
    override fun read(input: DataInput): PlaceholderMarker = PlaceholderMarker
}
