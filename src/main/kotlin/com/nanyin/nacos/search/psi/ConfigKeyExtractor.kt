package com.nanyin.nacos.search.psi

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.StringReader
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The outcome of asking [ConfigKeyExtractor] for a configuration's keys.
 *
 * A closed set, because the two outcomes answer different questions and a
 * caller that flattens them silently turns "we do not parse this format" into
 * "this key is absent". That is the same mistake the cache already refuses to
 * make about data ids, where only a fresh, complete Namespace index may prove
 * one absent; here, only a successful parse under the 运行时格式 may prove a key
 * absent.
 *
 * There is deliberately no `keysOrEmpty()` on this type: the trapdoor is the
 * defect.
 */
sealed interface KeyExtraction {

    /** The body was parsed. [keys] is the whole key space it contributes. */
    data class Extracted(val keys: Map<String, ConfigKeyExtractor.KeyLocation>) : KeyExtraction

    /** The 运行时格式 contributes no keys, for [reason]. Says nothing about the body. */
    data class NotExtractable(val reason: Reason) : KeyExtraction

    enum class Reason {
        /** 格式不参与解析 — no runtime reads this body for placeholder keys. */
        KNOWN_NON_PARSING_FORMAT,

        /** Nothing available named a format, so no parse rules apply. */
        FORMAT_UNDETERMINED,

        /** A runtime parses this format; the plugin does not yet. */
        PARSER_NOT_IMPLEMENTED
    }
}

/**
 * Extracts property keys and their line index / value from a Nacos
 * configuration body, read under an explicitly supplied 运行时格式.
 *
 * Pure, side-effect-free — unit tested directly with synthetic content, and the
 * primary seam for extraction correctness.
 *
 * The format is an input, not something derived here: the same body is a
 * different key space under different rules, and only the caller knows which
 * rules the runtime will apply (see [RuntimeConfigFormat]).
 *
 * Key extraction lives here in the code-navigation layer. Reading keys out of
 * configuration content is configuration-format knowledge, and moving it into
 * the cache would make the cache module depend on this one (ADR-0051).
 */
object ConfigKeyExtractor {

    /**
     * Location of a key inside a configuration: the 0-based line index and the
     * resolved value (string form). The line index is used to position the
     * caret / gutter marker; the value is shown in navigation hints.
     *
     * [lineIndex] is [LINE_NOT_FOUND] when the key is real but its declaration
     * line could not be located. The key set and the line are derived
     * separately on purpose: a wrong line misplaces an icon, a wrong key set
     * invents or destroys navigation, and the two must not share a failure mode.
     */
    data class KeyLocation(val key: String, val lineIndex: Int, val value: String)

    /** The sentinel every consumer of [KeyLocation.lineIndex] already handles. */
    const val LINE_NOT_FOUND = -1

    /**
     * Extracts every key [content] contributes when read as [format].
     *
     * When the same key repeats within a single configuration the last
     * occurrence wins (matches properties/yaml override semantics).
     */
    fun extract(content: String?, format: RuntimeConfigFormat): KeyExtraction =
        // One switch over the closed set, with no `else`: adding a format the
        // plugin learns to parse must not compile until this decides what it
        // means. A branch that quietly answered `Extracted(emptyMap())` would
        // reintroduce the very confusion [KeyExtraction] exists to prevent.
        //
        // A refusal is a property of the format alone, so the body is never
        // consulted for one: an empty XML body is no more extractable than a
        // full one.
        when (format) {
            RuntimeConfigFormat.TEXT,
            RuntimeConfigFormat.HTML,
            RuntimeConfigFormat.TOML ->
                KeyExtraction.NotExtractable(KeyExtraction.Reason.KNOWN_NON_PARSING_FORMAT)
            RuntimeConfigFormat.UNDETERMINED ->
                KeyExtraction.NotExtractable(KeyExtraction.Reason.FORMAT_UNDETERMINED)
            RuntimeConfigFormat.XML ->
                KeyExtraction.NotExtractable(KeyExtraction.Reason.PARSER_NOT_IMPLEMENTED)
            RuntimeConfigFormat.PROPERTIES -> parsed(content, ::extractProperties)
            RuntimeConfigFormat.YAML -> parsed(content, ::extractYaml)
            RuntimeConfigFormat.JSON -> parsed(content, ::extractJson)
        }

    private fun parsed(
        content: String?,
        parse: (String) -> Map<String, KeyLocation>
    ): KeyExtraction.Extracted =
        if (content.isNullOrBlank()) {
            KeyExtraction.Extracted(emptyMap())
        } else {
            KeyExtraction.Extracted(parse(content))
        }

    // ----- key paths --------------------------------------------------------

    /**
     * One segment of the path from a document's root to a leaf.
     *
     * YAML and JSON share it because they flatten the same shape — a tree of
     * mappings and sequences — onto the same dotted key space. They did not
     * share it while the YAML reader was hand-written, because that reader's
     * bracket form omitted the separator after an index (`items[0]name.first`),
     * a shape Spring's relaxed binding can never match; unifying them then
     * would only have carried the defect into JSON.
     */
    private sealed class PathSeg {
        data class Name(val name: String) : PathSeg()
        data class Index(val n: Int) : PathSeg()
    }

    /**
     * Records a leaf at [path] under both the bracket form (`list[0].n`) and
     * the dot form (`list.0.n`), so either placeholder style resolves.
     *
     * Only a scalar reaches here. A container contributes no key of its own: a
     * runtime property source flattens to leaves, and `${list}` resolves
     * against none of them.
     */
    private fun emitLeaf(
        path: ArrayDeque<PathSeg>,
        value: String,
        declarationLine: Int,
        result: LinkedHashMap<String, KeyLocation>
    ) {
        // A scalar at the document root belongs to no key.
        if (path.isEmpty()) return
        val bracket = renderKey(path, bracketIndexes = true)
        result[bracket] = KeyLocation(bracket, declarationLine, value)
        val dot = renderKey(path, bracketIndexes = false)
        if (dot != bracket) {
            result[dot] = KeyLocation(dot, declarationLine, value)
        }
    }

    private fun renderKey(path: ArrayDeque<PathSeg>, bracketIndexes: Boolean): String {
        val sb = StringBuilder()
        for (seg in path) {
            when (seg) {
                is PathSeg.Name -> {
                    if (sb.isNotEmpty()) sb.append(".")
                    sb.append(seg.name)
                }
                is PathSeg.Index -> {
                    if (bracketIndexes) {
                        sb.append("[").append(seg.n).append("]")
                    } else {
                        if (sb.isNotEmpty()) sb.append(".")
                        sb.append(seg.n)
                    }
                }
            }
        }
        return sb.toString()
    }

    // ----- properties -------------------------------------------------------

    private fun extractProperties(content: String): Map<String, KeyLocation> {
        val result = LinkedHashMap<String, KeyLocation>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            val line = stripInlineComment(rawLine).trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEachIndexed
            val eq = firstAssignmentIndex(line)
            if (eq <= 0) return@forEachIndexed
            val key = unescapeKey(line.substring(0, eq).trim())
            if (key.isEmpty()) return@forEachIndexed
            val value = line.substring(eq + 1).trim()
            result[key] = KeyLocation(key, index, value)
        }
        return result
    }

    /** `#` starts an inline comment only when preceded by whitespace. */
    private fun stripInlineComment(line: String): String {
        val hash = line.indexOf('#')
        if (hash <= 0) return line
        // Allow '#' inside keys that are already escaped (\#), otherwise cut.
        return if (line[hash - 1] == ' ' || line[hash - 1] == '\t') line.substring(0, hash) else line
    }

    private fun firstAssignmentIndex(line: String): Int {
        val eq = line.indexOf('=')
        val colon = line.indexOf(':')
        return when {
            eq >= 0 && colon >= 0 -> minOf(eq, colon)
            eq >= 0 -> eq
            colon >= 0 -> colon
            else -> -1
        }
    }

    private fun unescapeKey(key: String): String {
        return key.replace("\\ ", " ").replace("\\:", ":").replace("\\=", "=")
    }

    // ----- yaml -------------------------------------------------------------

    /**
     * Reads the key space out of a YAML body with snakeyaml — the library
     * Spring Boot's own YAML property source loader uses, so the plugin and the
     * runtime cannot disagree about YAML syntax. Block scalars, multi-document
     * streams, anchors and flow style are the library's problem rather than
     * ours; what remains ours is only the derivation of a key path from the
     * node tree it composes.
     *
     * It replaces a hand-written indentation-stack reader that read a block
     * scalar's body back as keys, collapsed a sequence of nested mappings onto
     * index zero, attributed an anchored mapping's children to the document
     * root, kept only the last document of a stream, saw neither flow style nor
     * a quoted or digit-leading key, and read an inline comment as part of the
     * value it followed.
     *
     * `composeAll`, not `load`: composing builds the node tree without
     * constructing a single Java object out of the body, and each node carries
     * the mark this takes its line from — so YAML needs none of the permissive
     * locator JSON does. Every document contributes, because a multi-document
     * body is the ordinary Spring profile idiom and keeping only the last one
     * silently discards the first.
     *
     * A syntax error keeps the documents already composed, for the same reason
     * the JSON reader keeps what it read: a partial key space is strictly
     * better than none for navigation. It keeps no less and no more than that —
     * the granularity is a whole document, because composing yields one only
     * once it is complete, and it ends the stream, so a malformed document
     * costs every document after it as well. A body that trips one of the
     * loader's limits (its 3 MB code-point ceiling, its alias budget) therefore
     * contributes nothing at all; Nacos caps a configuration far below that.
     */
    private fun extractYaml(content: String): Map<String, KeyLocation> {
        val result = LinkedHashMap<String, KeyLocation>()
        val path = ArrayDeque<PathSeg>()
        val onPath = Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
        // Composing never invokes a constructor, so the body cannot instantiate
        // anything whichever constructor this names. Naming the safe one says so
        // outright, and carries the loader's limits into the composer.
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        try {
            for (document in yaml.composeAll(StringReader(content))) {
                readYamlNode(document, path, onPath, LINE_NOT_FOUND, result)
            }
        } catch (_: Exception) {
            // A malformed body. snakeyaml reports it as a YAMLException, and
            // composeAll parses lazily, so `result` holds every document read
            // before the one that gave out.
        }
        return result
    }

    private fun readYamlNode(
        node: Node,
        path: ArrayDeque<PathSeg>,
        onPath: MutableSet<Node>,
        declarationLine: Int,
        result: LinkedHashMap<String, KeyLocation>
    ) {
        when (node) {
            is MappingNode -> readYamlMapping(node, path, onPath, result)
            is SequenceNode -> readYamlSequence(node, path, onPath, result)
            // A block scalar's body arrives here as one value, which is the
            // whole reason it can no longer contribute keys of its own.
            is ScalarNode -> emitLeaf(path, node.value, declarationLine, result)
            else -> Unit
        }
    }

    private fun readYamlMapping(
        node: MappingNode,
        path: ArrayDeque<PathSeg>,
        onPath: MutableSet<Node>,
        result: LinkedHashMap<String, KeyLocation>
    ) {
        // An alias may reference a mapping that contains it, which composes a
        // cyclic node graph. That mapping's keys are already on the path, so
        // stopping here loses nothing and is what keeps the walk off the stack
        // limit.
        if (!onPath.add(node)) return
        for ((name, entry) in yamlEntries(node, onPath)) {
            path.addLast(PathSeg.Name(name))
            // The line is the key's own, so a value spanning many lines cannot
            // drag the key's line along with it.
            readYamlNode(entry.valueNode, path, onPath, entry.keyNode.startMark.line, result)
            path.removeLast()
        }
        onPath.remove(node)
    }

    private fun readYamlSequence(
        node: SequenceNode,
        path: ArrayDeque<PathSeg>,
        onPath: MutableSet<Node>,
        result: LinkedHashMap<String, KeyLocation>
    ) {
        // The same cyclic-anchor guard the mapping walk explains.
        if (!onPath.add(node)) return
        node.value.forEachIndexed { index, item ->
            path.addLast(PathSeg.Index(index))
            // An element declares no key, so it is its own declaration.
            readYamlNode(item, path, onPath, item.startMark.line, result)
            path.removeLast()
        }
        onPath.remove(node)
    }

    /** One entry of a mapping, after its `<<` merges are resolved. */
    private class YamlEntry(val keyNode: ScalarNode, val valueNode: Node)

    /**
     * The entries a mapping contributes once its `<<` merges are resolved: its
     * own, then those of each mapping it merges in that no earlier source and
     * the mapping itself did not already name.
     *
     * The merge is resolved here rather than by walking `<<` as a path segment,
     * because a segment would invent a `service.<<.timeout` no runtime produces
     * and lose the `service.timeout` every runtime does. It is resolved *before*
     * the walk rather than by letting a later write win, because YAML merge is
     * shallow: a mapping's own `db: {port: 1}` discards a merged `db` outright,
     * where a per-leaf overwrite would keep the merged `db.url` underneath it
     * and invent a key.
     */
    private fun yamlEntries(
        node: MappingNode,
        onPath: MutableSet<Node>
    ): Map<String, YamlEntry> {
        val entries = LinkedHashMap<String, YamlEntry>()
        for (tuple in node.value) {
            if (tuple.keyNode.tag == Tag.MERGE) continue
            // A complex key (`? [a, b]`) names nothing a placeholder can spell.
            val key = tuple.keyNode as? ScalarNode ?: continue
            entries[key.value] = YamlEntry(key, tuple.valueNode)
        }
        for (tuple in node.value) {
            if (tuple.keyNode.tag != Tag.MERGE) continue
            for (source in yamlMergeSources(tuple.valueNode)) {
                // A mapping that merges itself, directly or through a chain.
                if (!onPath.add(source)) continue
                yamlEntries(source, onPath).forEach { (name, entry) ->
                    if (name !in entries) entries[name] = entry
                }
                onPath.remove(source)
            }
        }
        return entries
    }

    /** `<<: *a` names one source; `<<: [*a, *b]` names several, `*a` winning. */
    private fun yamlMergeSources(node: Node): List<MappingNode> = when (node) {
        is MappingNode -> listOf(node)
        is SequenceNode -> node.value.filterIsInstance<MappingNode>()
        else -> emptyList()
    }

    // ----- json -------------------------------------------------------------

    /**
     * Reads the key space out of a JSON body with Gson's streaming reader.
     *
     * Structure comes from the parser, which is the whole point: the previous
     * hand-written scanner lost every key declared after an array whose last
     * element was a string, and never descended into arrays at all.
     *
     * Nesting flattens to dot paths through the same [PathSeg] the YAML reader
     * uses, so the two formats cannot drift into different key shapes.
     *
     * A syntax error ends the read and keeps the keys already parsed: a partial
     * key space is what the scanner it replaces would have produced, and it is
     * strictly better than none for navigation.
     */
    private fun extractJson(content: String): Map<String, KeyLocation> {
        val result = LinkedHashMap<String, KeyLocation>()
        val locator = JsonDeclarationLocator(content)
        val path = ArrayDeque<PathSeg>()
        try {
            JsonReader(StringReader(content)).use { reader ->
                readJsonValue(reader, locator, path, LINE_NOT_FOUND, result)
            }
        } catch (_: Exception) {
            // Malformed body. Gson signals this as IOException, IllegalStateException
            // or NumberFormatException depending on where the read gave out; none of
            // them are recoverable and all of them leave `result` holding what was
            // read up to that point.
        }
        return result
    }

    private fun readJsonValue(
        reader: JsonReader,
        locator: JsonDeclarationLocator,
        path: ArrayDeque<PathSeg>,
        declarationLine: Int,
        result: LinkedHashMap<String, KeyLocation>
    ) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    // The line is looked up here, before the value is read, so a
                    // value spanning many lines cannot drag the key's line along
                    // with it.
                    val line = locator.lineOf(name)
                    path.addLast(PathSeg.Name(name))
                    readJsonValue(reader, locator, path, line, result)
                    path.removeLast()
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var index = 0
                while (reader.hasNext()) {
                    path.addLast(PathSeg.Index(index))
                    // An element declares no name of its own, and Gson supplies
                    // no mark, so it keeps the line of the key that opened the
                    // array. YAML answers this from the element's own mark.
                    readJsonValue(reader, locator, path, declarationLine, result)
                    path.removeLast()
                    index++
                }
                reader.endArray()
            }
            // nextString() renders NUMBER tokens as the literal they were written
            // as, which is what the previous scanner reported and what a value
            // hint should show.
            JsonToken.STRING, JsonToken.NUMBER ->
                emitLeaf(path, reader.nextString(), declarationLine, result)
            JsonToken.BOOLEAN ->
                emitLeaf(path, reader.nextBoolean().toString(), declarationLine, result)
            JsonToken.NULL -> {
                reader.nextNull()
                emitLeaf(path, "null", declarationLine, result)
            }
            else -> reader.skipValue()
        }
    }

    /**
     * Reports the line each JSON key is declared on.
     *
     * Kept deliberately apart from the parse: Gson's reader supplies no line
     * number, and deriving one inside the parse would tie the key space to a
     * positioning defect.
     *
     * It does not search for a name. Searching finds *a* plausible occurrence,
     * and a plausible-but-wrong line is exactly what must not happen: for a
     * name whose document spelling is escaped, the search would sail past it
     * and claim the line of the next key that happens to share the name. This
     * walks key positions instead. The reader reports names in document order
     * and one per `"…":` position, so each call consumes the next such position
     * and answers only for the name that position actually holds. When the
     * position's raw spelling is escaped — the one case where a document key
     * and a decoded name legitimately differ — it reports [LINE_NOT_FOUND],
     * the sentinel every consumer already handles, and stays aligned for the
     * keys that follow.
     */
    private class JsonDeclarationLocator(private val content: String) {
        private var index = 0
        private var line = 0

        fun lineOf(name: String): Int {
            while (index < content.length) {
                val c = content[index]
                if (c != '"') {
                    if (c == '\n') line++
                    index++
                    continue
                }
                val tokenLine = line
                val raw = readStringToken() ?: return LINE_NOT_FOUND
                // A string is a key only where a colon follows it; anything else
                // is a value, and values are skipped rather than counted.
                if (!colonFollows()) continue
                // An escaped spelling cannot be compared to the decoded name
                // without re-implementing the decoding Gson already did.
                return if (raw == name) tokenLine else LINE_NOT_FOUND
            }
            return LINE_NOT_FOUND
        }

        /**
         * Reads the string starting at the quote under [index], leaving [index]
         * past its closing quote. Returns the raw body, or null when unterminated.
         */
        private fun readStringToken(): String? {
            val start = index + 1
            var i = start
            var escaped = false
            while (i < content.length) {
                val c = content[i]
                if (c == '\n') line++
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> {
                        index = i + 1
                        return content.substring(start, i)
                    }
                }
                i++
            }
            index = content.length
            return null
        }

        /** Does not advance [index]: the skipped whitespace is counted by the caller's scan. */
        private fun colonFollows(): Boolean {
            var i = index
            while (i < content.length && content[i].isWhitespace()) i++
            return i < content.length && content[i] == ':'
        }
    }
}
