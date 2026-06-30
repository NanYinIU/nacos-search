package com.nanyin.nacos.search.psi

import com.nanyin.nacos.search.models.NacosConfiguration

/**
 * Extracts property keys and their line offset / value from a Nacos
 * configuration's [content], keyed by the configuration's type.
 *
 * Pure, side-effect-free — unit tested directly with synthetic content.
 *
 * Supported types (mirrors [NacosConfiguration.getConfigType]):
 *  - properties: line-based `key=value`, `#`/`!` comments skipped
 *  - yaml/yml:   `key: value` with one level of nested mapping via dot paths
 *  - json:       top-level `"key": value` pairs
 *
 * Unknown types return an empty map (they cannot contribute key targets).
 */
object ConfigKeyExtractor {

    /**
     * Location of a key inside a configuration: the 0-based line index and the
     * resolved value (string form). The line index is used to position the
     * caret / gutter marker; the value is shown in navigation hints.
     */
    data class KeyLocation(val key: String, val lineIndex: Int, val value: String)

    /**
     * Extracts every recognized key for [config], keyed by the key string.
     * When the same key repeats within a single configuration the last
     * occurrence wins (matches properties/yaml override semantics).
     */
    fun extract(config: NacosConfiguration): Map<String, KeyLocation> {
        val content = config.content ?: return emptyMap()
        if (content.isBlank()) return emptyMap()
        return when (config.getConfigType()) {
            "properties" -> extractProperties(content)
            "yaml", "yml" -> extractYaml(content)
            "json" -> extractJson(content)
            else -> emptyMap()
        }
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
     * Reads `"key": value` pairs from a (possibly nested) JSON object and flattens
     * nesting to dot-paths (`"sys": {"audit": {"switch": true}}` -> `sys.audit.switch`).
     * Uses a character-level scanner so it handles minified (single-line) JSON as well
     * as pretty-printed input; the previous line-regex parser silently returned an empty
     * map for the default `JSON.stringify` output.
     */
    private fun extractJson(content: String): Map<String, KeyLocation> {
        val result = LinkedHashMap<String, KeyLocation>()
        val path = ArrayDeque<String>()
        val chars = content.toCharArray()
        var i = 0
        var line = 0
        val n = chars.size

        fun skipWs() {
            while (i < n) {
                when (chars[i]) {
                    ' ', '\t', '\r' -> i++
                    '\n' -> { i++; line++ }
                    else -> return
                }
            }
        }

        fun readString(): String {
            // assumes chars[i] == '"'
            val sb = StringBuilder()
            i++ // opening quote
            while (i < n && chars[i] != '"') {
                if (chars[i] == '\\' && i + 1 < n) { sb.append(chars[i]); sb.append(chars[i + 1]); i += 2; continue }
                if (chars[i] == '\n') line++
                sb.append(chars[i]); i++
            }
            if (i < n) i++ // closing quote
            return sb.toString()
        }

        // Skip a single JSON value (string, number, keyword, array, object) starting at i,
        // tracking object/array nesting so commas and braces inside it are ignored.
        fun skipValue(): String {
            skipWs()
            if (i >= n) return ""
            val start = i
            when (chars[i]) {
                '"' -> { val s = readString(); return unquoteJson("\"" + s + "\"") }
                '[', '{' -> {
                    val open = chars[i]
                    val close = if (open == '[') ']' else '}'
                    var depth = 0
                    while (i < n) {
                        val c = chars[i]
                        when {
                            c == '"' -> readString().also { /* advanced */ }
                            c == open -> depth++
                            c == close -> { depth--; if (depth == 0) { i++; return content.substring(start, i).trim() } }
                        }
                        if (c == '\n') line++
                        i++
                    }
                    return content.substring(start, i).trim()
                }
                else -> {
                    // bare token (number / true / false / null) until delimiter
                    while (i < n) {
                        val c = chars[i]
                        if (c == ',' || c == '}' || c == ']' || c == '\n' || c == ' ' || c == '\t' || c == '\r') break
                        i++
                    }
                    return content.substring(start, i).trim()
                }
            }
        }

        // The parser only tracks object structure (array elements are skipped as opaque
        // values), which is sufficient for the config-key use case.
        while (i < n) {
            val c = chars[i]
            when {
                c == '\n' -> { line++; i++ }
                c == '{' || c == ',' || c == ':' || c == '[' || c == ']' || c == ' ' || c == '\t' || c == '\r' -> i++
                c == '}' -> { if (path.isNotEmpty()) path.removeLast(); i++ }
                c == '"' -> {
                    val key = readString()
                    skipWs()
                    if (i < n && chars[i] == ':') {
                        i++
                        skipWs()
                        if (i < n && chars[i] == '{') {
                            // nested object: record nothing for the value, descend into it
                            path.addLast(key)
                            i++ // consume opening brace
                        } else {
                            val value = skipValue()
                            val fullKey = if (path.isEmpty()) key else path.joinToString(".") + "." + key
                            result[fullKey] = KeyLocation(fullKey, line, value)
                        }
                    } else {
                        // a bare string not acting as a key; skip its value portion
                    }
                }
                else -> i++
            }
        }
        return result
    }

    private fun unquoteJson(value: String): String {
        if ((value.startsWith("\"") && value.endsWith("\"") && value.length >= 2)) {
            return value.substring(1, value.length - 1)
        }
        return value
    }
}
