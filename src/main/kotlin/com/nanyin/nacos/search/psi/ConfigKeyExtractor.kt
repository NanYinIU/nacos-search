package com.nanyin.nacos.search.psi

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

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

    private val yamlKeyValue = Regex("""^(\s*)([A-Za-z_][\w.\-]*)\s*:\s*(.*)$""")
    private val yamlListItem = Regex("""^(\s*)-\s+(.*)$""")

    private sealed class YamlSeg {
        abstract val indent: Int
        data class Key(override val indent: Int, val name: String) : YamlSeg()
        data class Index(override val indent: Int, val n: Int) : YamlSeg()
    }

    /**
    * Minimal YAML mapping reader: maintains an indentation stack so that
    * arbitrarily deep nesting flattens to dot-paths
    * (`sys:` / `  audit:` / `    switch: true` -> `sys.audit.switch`).
    * Sequence items (`- id: 1`) are indexed and emitted in both
    * `parent.0.child` and `parent[0].child` forms (plan 7.3).
    */
    private fun extractYaml(content: String): Map<String, KeyLocation> {
        val result = LinkedHashMap<String, KeyLocation>()
        val path = ArrayDeque<YamlSeg>()
        // Per-indent running index for sequence items at that indentation.
        val listIndex = HashMap<Int, Int>()
        content.lineSequence().forEachIndexed { index, rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed

            val listItem = yamlListItem.matchEntire(rawLine)
            if (listItem != null) {
                handleYamlListItem(listItem, index, path, listIndex, result)
                return@forEachIndexed
            }

            val match = yamlKeyValue.matchEntire(rawLine) ?: return@forEachIndexed
            val indent = match.groupValues[1].length
            val key = match.groupValues[2]
            val rawValue = match.groupValues[3].trim().trimEnd(',')

            popYaml(path, indent)
            // A mapping key at this indent closes any same-level sequence.
            listIndex.remove(indent)

            if (rawValue.isEmpty() || rawValue == "|") {
                // Mapping parent: push onto path, no leaf produced here.
                path.addLast(YamlSeg.Key(indent, key))
                return@forEachIndexed
            }
            emitYamlLeaf(path, key, rawValue, index, result)
        }
        return result
    }

    private fun handleYamlListItem(
        match: MatchResult,
        lineIndex: Int,
        path: ArrayDeque<YamlSeg>,
        listIndex: HashMap<Int, Int>,
        result: LinkedHashMap<String, KeyLocation>
    ) {
        val dashIndent = match.groupValues[1].length
        popYaml(path, dashIndent)
        // Deeper sequences are finished once we return to a shallower dash.
        listIndex.keys.filter { it > dashIndent }.forEach { listIndex.remove(it) }
        val n = listIndex.getOrPut(dashIndent) { 0 }
        listIndex[dashIndent] = n + 1
        // The index segment carries the list indent so subsequent siblings pop it.
        path.addLast(YamlSeg.Index(dashIndent, n))

        val rest = match.groupValues[2].trim()
        val inline = parseInlineMapEntry(rest)
        if (inline != null) {
            val (childKey, childValue) = inline
            if (childValue != null) {
                emitYamlLeaf(path, childKey, childValue, lineIndex, result)
            } else {
                // "- child:" opens a nested mapping under this index.
                path.addLast(YamlSeg.Key(dashIndent + 1, childKey))
                listIndex.remove(dashIndent)
            }
        }
        // A bare scalar item ("- bare") produces no keyed location.
    }

    /** Parses "id: 1" -> (id, 1); "id:" -> (id, null); returns null when not a map entry. */
    private fun parseInlineMapEntry(rest: String): Pair<String, String?>? {
        val colon = rest.indexOf(':')
        if (colon <= 0) return null
        val key = rest.substring(0, colon).trim()
        if (!key.matches(Regex("[A-Za-z_][\\w.\\-]*"))) return null
        val value = rest.substring(colon + 1).trim().trimEnd(',').ifEmpty { null }
        return key to value
    }

    private fun popYaml(path: ArrayDeque<YamlSeg>, indent: Int) {
        while (path.isNotEmpty() && path.last().indent >= indent) {
            path.removeLast()
        }
    }

    /**
     * Emits a leaf key. When the path contains index segments, both the
     * bracket form (`tabs[0].id`) and the dot form (`tabs.0.id`) are stored
     * so either placeholder style resolves.
     */
    private fun emitYamlLeaf(
        path: ArrayDeque<YamlSeg>,
        key: String,
        rawValue: String,
        lineIndex: Int,
        result: LinkedHashMap<String, KeyLocation>
    ) {
        val bracket = yamlBracketKey(path, key)
        result[bracket] = KeyLocation(bracket, lineIndex, stripYamlValue(rawValue))
        val dot = yamlDotKey(path, key)
        if (dot != bracket) {
            result[dot] = KeyLocation(dot, lineIndex, stripYamlValue(rawValue))
        }
    }

    private fun yamlBracketKey(path: ArrayDeque<YamlSeg>, key: String): String {
        val sb = StringBuilder()
        for (seg in path) {
            when (seg) {
                is YamlSeg.Key -> {
                    if (sb.isNotEmpty() && !sb.endsWith("]")) sb.append(".")
                    sb.append(seg.name)
                }
                is YamlSeg.Index -> sb.append("[").append(seg.n).append("]")
            }
        }
        return if (sb.isEmpty()) key else "$sb.$key"
    }

    private fun yamlDotKey(path: ArrayDeque<YamlSeg>, key: String): String {
        val sb = StringBuilder()
        for (seg in path) {
            when (seg) {
                is YamlSeg.Key -> {
                    if (sb.isNotEmpty()) sb.append(".")
                    sb.append(seg.name)
                }
                is YamlSeg.Index -> {
                    if (sb.isNotEmpty()) sb.append(".")
                    sb.append(seg.n)
                }
            }
        }
        return if (sb.isEmpty()) key else "$sb.$key"
    }

    private fun stripYamlValue(value: String): String {
        var v = value.trim()
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length - 1)
        }
        return v
    }

    // ----- json -------------------------------------------------------------

    /**
     * Deliberately not shared with [YamlSeg] and its key rendering, which look
     * like the same thing and are not: `yamlBracketKey` omits the separator
     * after an index (`items[0]name.first`), a shape Spring's relaxed binding
     * can never match. Unifying them now would either carry that defect into
     * JSON or fix YAML's key space as a side effect of a JSON change. The YAML
     * reader is replaced wholesale by snakeyaml (#166); the two converge then.
     */
    private sealed class JsonSeg {
        data class Name(val name: String) : JsonSeg()
        data class Index(val n: Int) : JsonSeg()
    }

    /**
     * Reads the key space out of a JSON body with Gson's streaming reader.
     *
     * Structure comes from the parser, which is the whole point: the previous
     * hand-written scanner lost every key declared after an array whose last
     * element was a string, and never descended into arrays at all.
     *
     * Nesting flattens to dot paths, and array elements are addressed by index
     * in both the bracket and the dot form (`list[0].n` and `list.0.n`), the
     * same pair the YAML reader emits, so either placeholder style resolves.
     * A container contributes no key of its own — a runtime property source
     * flattens to leaves, and `${list}` resolves against none of them.
     *
     * A syntax error ends the read and keeps the keys already parsed: a partial
     * key space is what the scanner it replaces would have produced, and it is
     * strictly better than none for navigation.
     */
    private fun extractJson(content: String): Map<String, KeyLocation> {
        val result = LinkedHashMap<String, KeyLocation>()
        val locator = JsonDeclarationLocator(content)
        val path = ArrayDeque<JsonSeg>()
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
        path: ArrayDeque<JsonSeg>,
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
                    path.addLast(JsonSeg.Name(name))
                    readJsonValue(reader, locator, path, line, result)
                    path.removeLast()
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var index = 0
                while (reader.hasNext()) {
                    path.addLast(JsonSeg.Index(index))
                    // An element declares no name of its own, so it keeps the
                    // line of the key that opened the array.
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
                emitJsonLeaf(path, reader.nextString(), declarationLine, result)
            JsonToken.BOOLEAN ->
                emitJsonLeaf(path, reader.nextBoolean().toString(), declarationLine, result)
            JsonToken.NULL -> {
                reader.nextNull()
                emitJsonLeaf(path, "null", declarationLine, result)
            }
            else -> reader.skipValue()
        }
    }

    private fun emitJsonLeaf(
        path: ArrayDeque<JsonSeg>,
        value: String,
        declarationLine: Int,
        result: LinkedHashMap<String, KeyLocation>
    ) {
        // A scalar at the document root belongs to no key.
        if (path.isEmpty()) return
        val bracket = jsonKey(path, bracketIndexes = true)
        result[bracket] = KeyLocation(bracket, declarationLine, value)
        val dot = jsonKey(path, bracketIndexes = false)
        if (dot != bracket) {
            result[dot] = KeyLocation(dot, declarationLine, value)
        }
    }

    private fun jsonKey(path: ArrayDeque<JsonSeg>, bracketIndexes: Boolean): String {
        val sb = StringBuilder()
        for (seg in path) {
            when (seg) {
                is JsonSeg.Name -> {
                    if (sb.isNotEmpty()) sb.append(".")
                    sb.append(seg.name)
                }
                is JsonSeg.Index -> {
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
