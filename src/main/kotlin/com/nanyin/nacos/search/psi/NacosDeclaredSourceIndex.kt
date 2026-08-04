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
 * Persistent file-based index mapping declared configuration-source data ids
 * (e.g. `@NacosPropertySource(dataId = "common.properties")`) to the `.java`
 * files that declare them.
 *
 * Powers the navigation detail prefetch work set (ADR-0041) and is the same
 * signal that hard-filters code-reference resolution. Reverse Find Usages that
 * applies this filter is out of scope for the index's first consumer.
 *
 * The [DeclaredSourceIndexer] uses a lightweight regex rather than PSI (which
 * would be too slow during indexing). Version bumps force a one-time full
 * reindex of the project.
 */
class NacosDeclaredSourceIndex : FileBasedIndexExtension<String, DeclaredSourceMarker>() {

    override fun getName(): ID<String, DeclaredSourceMarker> = INDEX_ID

    override fun getVersion(): Int = 1

    override fun dependsOnFileContent(): Boolean = true

    override fun getIndexer(): DataIndexer<String, DeclaredSourceMarker, FileContent> = DeclaredSourceIndexer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> file.fileType is JavaFileType }

    override fun getKeyDescriptor(): KeyDescriptor<String> = DeclaredSourceKeyDescriptor

    override fun getValueExternalizer(): DataExternalizer<DeclaredSourceMarker> = DeclaredSourceMarkerExternalizer

    companion object {
        val INDEX_ID: ID<String, DeclaredSourceMarker> = ID.create("nacos.declared.sources")
    }
}

/** Singleton value stored for each indexed data id — avoids Kotlin Void null interop. */
object DeclaredSourceMarker {
    private const val serialVersionUID = 1L
}

/**
 * Text-based extractor: finds `dataId = "..."` attributes on configuration-source
 * annotations and emits the data id. Avoids PSI in the indexing hot path.
 */
object DeclaredSourceIndexer : DataIndexer<String, DeclaredSourceMarker, FileContent> {
    /**
     * Matches `@NacosPropertySource(...)` (with optional package prefix) and
     * extracts string `dataId` attribute values. Nested `@NacosPropertySources`
     * arrays are covered because each inner annotation is still an independent
     * `@NacosPropertySource(...)` occurrence.
     */
    private val propertySourceAnnotation = Regex(
        """@(?:[\w.]*\.)?NacosPropertySource\s*\(([^)]*)\)"""
    )
    private val dataIdAttribute = Regex(
        """(?:dataId\s*=\s*)?["']([^"']+)["']"""
    )
    private val namedDataIdAttribute = Regex(
        """dataId\s*=\s*["']([^"']+)["']"""
    )

    override fun map(inputData: FileContent): MutableMap<String, DeclaredSourceMarker> {
        val map = HashMap<String, DeclaredSourceMarker>()
        for (dataId in extractDeclaredDataIds(inputData.contentAsText.toString())) {
            map[dataId] = DeclaredSourceMarker
        }
        return map
    }

    /**
     * Pure extraction logic returning the set of declared data ids found in
     * [text]. Testable without FileContent or platform interop.
     */
    fun extractDeclaredDataIds(text: String): Set<String> {
        if (text.isEmpty()) return emptySet()
        val result = linkedSetOf<String>()
        for (match in propertySourceAnnotation.findAll(text)) {
            val body = match.groupValues[1]
            // Prefer an explicit dataId = "..." attribute; fall back to the
            // first string literal only when it is the sole positional form.
            val named = namedDataIdAttribute.findAll(body).map { it.groupValues[1].trim() }
            val ids = named.toList().ifEmpty {
                // Single-attribute shorthand: @NacosPropertySource("foo.properties")
                // is uncommon but accepted; multi-literal bodies without dataId=
                // are ignored to avoid picking up groupId by accident.
                val literals = dataIdAttribute.findAll(body).map { it.groupValues[1].trim() }.toList()
                if (literals.size == 1 && !body.contains('=')) literals else emptyList()
            }
            for (id in ids) {
                if (id.isNotEmpty()) result.add(id)
            }
        }
        return result
    }
}

private object DeclaredSourceKeyDescriptor : KeyDescriptor<String> {
    override fun getHashCode(value: String?): Int = value?.hashCode() ?: 0
    override fun isEqual(a: String?, b: String?): Boolean = a == b
    override fun save(out: DataOutput, value: String?) {
        out.writeUTF(value ?: "")
    }
    override fun read(input: DataInput): String = input.readUTF()
}

private object DeclaredSourceMarkerExternalizer : DataExternalizer<DeclaredSourceMarker> {
    override fun save(out: DataOutput, value: DeclaredSourceMarker) {
        out.writeByte(MARKER)
    }

    override fun read(input: DataInput): DeclaredSourceMarker {
        check(input.readUnsignedByte() == MARKER) { "Invalid declared-source marker" }
        return DeclaredSourceMarker
    }

    private const val MARKER = 1
}
