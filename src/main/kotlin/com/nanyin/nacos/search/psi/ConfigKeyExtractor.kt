package com.nanyin.nacos.search.psi

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.io.StringReader
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Properties
import java.util.regex.Pattern

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
        /**
         * A mapping entry, spelled the way the runtime's flattening sees it.
         *
         * [text] is what Spring's `asMap` put into the map it then flattens: the
         * key itself where it resolved to a `CharSequence`, and `[` + the
         * resolved value + `]` where it did not (#178).
         *
         * Holding the *spelling* rather than the two cases that produce it is
         * what makes a non-string key `0:` and a string key `"[0]":` one and the
         * same segment — which is what the runtime makes of them: one entry in
         * one map, the later declaration winning, subtree and all. Modelling
         * them apart kept a subtree the runtime discards and invented a second
         * spelling beside it.
         */
        data class MapKey(val text: String) : PathSeg() {
            /**
             * The runtime's own test — `key.startsWith("[")` — which decides two
             * things: this segment joins to the path ahead of it without a
             * separator, and the key it belongs to has no dot spelling at all
             * (see [emitLeaf]).
             *
             * Textual, not structural. `[abc]`, and even an unclosed `[`, take
             * this branch as surely as `[0]` does; narrowing it to something that
             * only matches an index would part company with the runtime for the
             * sake of looking tidier.
             */
            val isBracketed: Boolean get() = text.startsWith("[")
        }

        /**
         * A sequence position — the one segment with two spellings, `[0]` and
         * the `.0` alias the plugin widens to on purpose.
         */
        data class Index(val n: Int) : PathSeg()
    }

    /**
     * Records a leaf at [path] under both the bracket form (`list[0].n`) and
     * the dot form (`list.0.n`), so either placeholder style resolves.
     *
     * A container with contents contributes no key of its own: a runtime property
     * source flattens to leaves, and `${list}` resolves against none of them. An
     * *empty* container is a different matter and follows whichever runtime reads
     * the format — see the two empty-container branches in [YamlWalk.readNode].
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
        // The dot spelling is a widening made for sequence indexes, where the
        // runtime resolves the bracket form and `${list.0.n}` is the style a
        // developer is as likely to write. A bracketed mapping key is not that
        // case: no runtime resolves any dot spelling of one, so the alias would
        // promise a placeholder that fails. It is suppressed for the whole key
        // rather than for that one segment, because a half-aliased `a[0].0` is a
        // spelling nothing resolves either.
        if (path.any { it is PathSeg.MapKey && it.isBracketed }) return
        val dot = renderKey(path, bracketIndexes = false)
        if (dot != bracket) {
            result[dot] = KeyLocation(dot, declarationLine, value)
        }
    }

    private fun renderKey(path: ArrayDeque<PathSeg>, bracketIndexes: Boolean): String {
        val sb = StringBuilder()
        for (seg in path) {
            when (seg) {
                // The runtime's join, verbatim: a bracketed key is appended as
                // `path + key`, anything else as `path + '.' + key`.
                is PathSeg.MapKey -> {
                    if (sb.isNotEmpty() && !seg.isBracketed) sb.append(".")
                    sb.append(seg.text)
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

    /**
     * Reads the key space out of a properties body with the JDK's own loader.
     *
     * Structure comes from [Properties] because that is the loader both runtimes
     * use, so the two cannot disagree about properties syntax. The hand-written
     * reader it replaces got three things wrong: it read a continuation line on
     * its own and invented a key out of it, it decoded no escape it had not been
     * told about, and it split on the first `=` or `:` in the line even where
     * that one was escaped into the key.
     *
     * One behaviour change comes with it. `java.util.Properties` has no inline
     * comments, so a value containing whitespace followed by a hash keeps its
     * full text rather than being truncated. Following the loader here is the
     * point: the runtime reads these bodies with it.
     *
     * A malformed escape ends the load and keeps the keys read up to it — the
     * same partial key space a malformed JSON body yields, and strictly better
     * than none for navigation.
     */
    private fun extractProperties(content: String): Map<String, KeyLocation> {
        val loaded = Properties()
        try {
            loaded.load(StringReader(content))
        } catch (_: Exception) {
            // IllegalArgumentException for a malformed \uxxxx escape; the loader
            // keeps everything it put before giving out.
        }
        val result = LinkedHashMap<String, KeyLocation>()
        // Document order where the locator could establish it, so the key space
        // reads in the order the body declares it.
        for ((key, lineIndex) in propertiesDeclarationLines(content)) {
            val value = loaded.getProperty(key) ?: continue
            result[key] = KeyLocation(key, lineIndex, value)
        }
        for (key in loaded.stringPropertyNames()) {
            if (result.containsKey(key)) continue
            result[key] = KeyLocation(key, LINE_NOT_FOUND, loaded.getProperty(key).orEmpty())
        }
        return result
    }

    /**
     * Reports the line each properties key is declared on, best-effort.
     *
     * Kept deliberately apart from the parse: the loader supplies no line
     * number, and deriving one inside the parse would tie the key space to a
     * positioning defect.
     *
     * It does not search the text for a name — a name whose document spelling is
     * escaped (`café`) appears nowhere to search for. It reads each natural line
     * where that line declares its name, and decodes nothing itself: a spelling
     * that needs decoding goes to the loader that produced the key set (see
     * [declaredKeyOf]).
     *
     * Neither line of a continuation may be trusted to name a key. The second
     * one declares nothing — it is the tail of a value, however much it looks
     * like an assignment — and the first names a key only where a separator on
     * it ends the name, since otherwise the rest of the name is on the line
     * after. A key split that way keeps [LINE_NOT_FOUND] rather than being
     * blamed on a line that plausibly spells something else, and so does the
     * unrelated key that half happens to spell. Joining the halves would mean
     * re-implementing the loader's line rules, which is what this change exists
     * to stop doing.
     *
     * A key the locator names but the loader does not is dropped by the caller,
     * so nothing here can widen the key space — only place it. That is why it
     * is a map over the whole body rather than the stream [JsonDeclarationLocator]
     * is: the loader hands back an unordered set of names, not names in document
     * order, so there is no parse to walk in step with.
     */
    private fun propertiesDeclarationLines(content: String): Map<String, Int> {
        val declaredAt = LinkedHashMap<String, Int>()
        var continuing = false
        content.lineSequence().forEachIndexed { index, line ->
            val isContinuation = continuing
            continuing = endsWithOddBackslashRun(line)
            if (isContinuation) return@forEachIndexed

            val start = line.indexOfFirst { !isPropertiesSpace(it) }
            if (start < 0) return@forEachIndexed
            if (line[start] == '#' || line[start] == '!') {
                // A comment ends at its line terminator whatever it ends with.
                continuing = false
                return@forEachIndexed
            }

            // The trailing backslash announces the join with the next natural
            // line; it is not part of what this one declares.
            val declaration = if (continuing) line.dropLast(1) else line
            val key = declaredKeyOf(declaration, start, continuing) ?: return@forEachIndexed
            // The loader lets a later assignment win, so the line follows it.
            declaredAt[key] = index
        }
        return declaredAt
    }

    /**
     * The name [declaration] declares from [start], or null when this line
     * declares none the locator can be sure of.
     *
     * A spelling with no backslash ahead of the separator passes through the
     * loader's escape decoding unchanged, so it already is the name. One with a
     * backslash goes to the loader rather than being decoded here — this locator
     * re-implements none of the loader's decoding — and that path is rare enough
     * that the loader's per-call buffers stay off the key index rebuild.
     *
     * [continues] is what makes an unterminated name a refusal instead of a
     * name. `a\` followed by `b=1` declares `ab`, not `a`, and answering `a`
     * there would hand the line of half a name to whatever real key happens to
     * be spelled by it. The loader cannot help: given the half it decodes the
     * half.
     */
    private fun declaredKeyOf(declaration: String, start: Int, continues: Boolean): String? {
        var i = start
        while (i < declaration.length) {
            val c = declaration[i]
            if (c == '\\') return if (continues) null else decodedKeyOf(declaration)
            if (c == '=' || c == ':' || isPropertiesSpace(c)) return declaration.substring(start, i)
            i++
        }
        // Nothing on this line ended the name.
        return if (continues) null else declaration.substring(start, i)
    }

    /** The name as the loader spells it, for a line that escapes its own. */
    private fun decodedKeyOf(declaration: String): String? =
        try {
            Properties()
                .apply { load(StringReader(declaration)) }
                .stringPropertyNames()
                .singleOrNull()
        } catch (_: Exception) {
            null
        }

    /** What the loader skips ahead of a key, and also accepts as a separator. */
    private fun isPropertiesSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\u000C'

    private fun endsWithOddBackslashRun(line: String): Boolean {
        var count = 0
        var i = line.length - 1
        while (i >= 0 && line[i] == '\\') {
            count++
            i--
        }
        return count % 2 == 1
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
     * `composeAll`, not `load`: composing builds the node tree this walks, and
     * each node carries the mark this takes its line from — so YAML needs none
     * of the permissive locator JSON does. Every document contributes, because
     * a multi-document body is the ordinary Spring profile idiom and keeping
     * only the last one silently discards the first.
     *
     * ### One thing is constructed after all (#178)
     *
     * The original note here said composing constructs no Java object out of
     * the body at all. That is no longer quite true, and the exception was a
     * decision rather than a slip. Spring's flattening brackets any mapping key
     * that is not a `CharSequence` and spells it from the key's **resolved**
     * value, so `on:` resolves to `[true]` and `0x10:` to `[16]` — keys the
     * plugin cannot name from the document spelling alone. The two ways to know
     * the resolved value are to construct the key node, or to read its tag and
     * render the value ourselves; this constructs it, because a second
     * implementation of snakeyaml's scalar resolution is the mistake #168
     * removed and it would have to agree with the first about `1:30` being
     * ninety.
     *
     * What that costs is bounded to what it buys. Only mapping **key** scalars
     * are constructed — never a value, never a container — and only ever by a
     * [SafeConstructor], which is the family Spring's own loader extends. So no
     * body instantiates anything the runtime would not, which is the property
     * `composeAll` was chosen for. A key whose tag that constructor refuses
     * contributes nothing rather than falling back to its document spelling:
     * the runtime fails the whole load over such a key, and inventing a name
     * for it is how a marker comes to promise a placeholder that fails.
     *
     * Composing uses [RuntimeResolver] rather than snakeyaml's default for the
     * same fidelity reason — see there.
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
    private fun extractYaml(content: String): Map<String, KeyLocation> = YamlWalk().read(content)

    /**
     * One YAML body's walk: the state a single [extractYaml] call accumulates,
     * and the snakeyaml collaborators that produce it.
     *
     * A class rather than a parameter list because none of this may be shared.
     * [ConfigKeyExtractor] is an object, and its callers rebuild key indexes off
     * several threads; the constructor that resolves a mapping key caches every
     * node it has built, so hoisting it to a field would be a data race rather
     * than a saved allocation.
     */
    private class YamlWalk {

        private val result = LinkedHashMap<String, KeyLocation>()
        private val path = ArrayDeque<PathSeg>()
        private val onPath = Collections.newSetFromMap(IdentityHashMap<Node, Boolean>())
        private val options = LoaderOptions()

        /**
         * Resolves a mapping key exactly as the runtime's loader does.
         *
         * It is the same instance the [Yaml] below is built with, so there is no
         * second set of construction rules to drift from the first — composing
         * never invokes it, and this walk invokes it for nothing but a key.
         */
        private val keyConstructor = KeyConstructor(options)

        private val yaml: Yaml = DumperOptions().let { dumping ->
            // The dumping half is never used; it is the price of the overload
            // that accepts a resolver.
            Yaml(keyConstructor, Representer(dumping), dumping, options, RuntimeResolver())
        }

        fun read(content: String): Map<String, KeyLocation> {
            try {
                for (document in yaml.composeAll(StringReader(content))) {
                    readNode(document, LINE_NOT_FOUND)
                }
            } catch (_: Exception) {
                // A malformed body. snakeyaml reports it as a YAMLException, and
                // composeAll parses lazily, so `result` holds every document read
                // before the one that gave out.
            }
            return result
        }

        private fun readNode(node: Node, declarationLine: Int) {
            when (node) {
                // An *empty* container is a leaf, because Spring Boot's flattening
                // stops at one: `list: []` resolves `${list}`, and `a: {b: {}}`
                // resolves `${a.b}`. The value emitted is the runtime's own rendering
                // of it, which keeps an empty container tellable apart in a navigation
                // hint from a genuinely valueless key (`enabled:`), whose value is the
                // empty string.
                //
                // This is the one place the two formats' key spaces legitimately
                // differ, and the difference is not ours to smooth over — each format
                // follows the loader that reads it (ADR-0055). Spring Cloud Alibaba's JSON
                // loader drops an empty container instead of terminating on it, so
                // `{"a":[]}` contributes no `a`, and the JSON walk below deliberately
                // has no counterpart to these two branches. The differential oracle
                // holds both directions, so neither can be "tidied" into the other
                // without the build saying so.
                //
                // Emptiness is read off the node, not off the entries a merge resolves
                // to — a mapping whose every entry is a `<<` merge of empty mappings
                // looks non-empty here and contributes no key. That agrees with the
                // runtime, which drops such a mapping rather than terminating on it
                // (only a literal `{}` gets a key), so reading emptiness off the node
                // is not merely the cheap answer. The corpus pins it, because it is
                // the sort of thing that reads like a bug and gets "fixed".
                is MappingNode ->
                    if (node.value.isEmpty()) {
                        emitLeaf(path, "{}", declarationLine, result)
                    } else {
                        readMapping(node)
                    }
                is SequenceNode ->
                    if (node.value.isEmpty()) {
                        emitLeaf(path, "[]", declarationLine, result)
                    } else {
                        readSequence(node)
                    }
                // A block scalar's body arrives here as one value, which is the
                // whole reason it can no longer contribute keys of its own.
                is ScalarNode -> emitLeaf(path, node.value, declarationLine, result)
                else -> Unit
            }
        }

        private fun readMapping(node: MappingNode) {
            // An alias may reference a mapping that contains it, which composes a
            // cyclic node graph. That mapping's keys are already on the path, so
            // stopping here loses nothing and is what keeps the walk off the stack
            // limit.
            if (!onPath.add(node)) return
            for ((segment, entry) in entriesOf(node)) {
                path.addLast(segment)
                // The line is the key's own, so a value spanning many lines cannot
                // drag the key's line along with it.
                readNode(entry.valueNode, entry.declarationLine)
                path.removeLast()
            }
            onPath.remove(node)
        }

        private fun readSequence(node: SequenceNode) {
            // The same cyclic-anchor guard the mapping walk explains.
            if (!onPath.add(node)) return
            node.value.forEachIndexed { index, item ->
                path.addLast(PathSeg.Index(index))
                // An element declares no key, so it is its own declaration.
                readNode(item, item.startMark.line)
                path.removeLast()
            }
            onPath.remove(node)
        }

        /**
         * The entries a mapping contributes, keyed by the segment each one adds
         * to the path.
         *
         * A mapping's entries pass through **two** identities on their way to a
         * flattened key, and the plugin has to keep them apart because they can
         * disagree:
         *
         * 1. snakeyaml resolves `<<` against the **constructed key**. `0:` and
         *    `"0":` are `Integer 0` and `String "0"`, so both survive a merge.
         * 2. Spring's `asMap` then keys on the **text** — `[0]` and `0` here, but
         *    `0:` and `"[0]":` both spell `[0]` and collapse onto one entry.
         *
         * Collapsing at step 2 is what [PathSeg.MapKey] does, and doing it in one
         * pass — treating step 1 as if it deduped on the text too — got the
         * *winner* wrong: it made a mapping's own key beat a merged one that
         * collides with it only at step 2, where the runtime lets whichever sits
         * later win. Hence the ordered list, then the fold.
         *
         * The merge is resolved here rather than by walking `<<` as a path
         * segment, because a segment would invent a `service.<<.timeout` no
         * runtime produces and lose the `service.timeout` every runtime does. It
         * is resolved *before* the walk rather than by letting a later leaf
         * overwrite an earlier one, because YAML merge is shallow: a mapping's
         * own `db: {port: 1}` discards a merged `db` outright, where a per-leaf
         * overwrite would keep the merged `db.url` underneath it and invent a key.
         */
        private fun entriesOf(node: MappingNode): Map<PathSeg, MappingEntry> {
            val entries = LinkedHashMap<PathSeg, MappingEntry>()
            // Step 2, verbatim: last one to land on a text wins.
            for (entry in mergeResolved(node)) {
                entries[entry.segment] = entry
            }
            return entries
        }

        /**
         * Step 1: the mapping's entries in the order snakeyaml leaves them, once
         * `<<` is resolved.
         *
         * One rule does all of it, and it is about **slots** rather than about
         * own-versus-merged: an entry takes the position of the one it overrides.
         * snakeyaml walks the tuples in document order keeping a key-to-index
         * map, and where a key is already present it *replaces that index*
         * instead of appending. So a `<<` above an own key supplies the slot the
         * own key then fills, and a `<<` below one finds the key taken and skips
         * it — own beats merged in both directions, which is the familiar rule,
         * but the *position* is the merged entry's whenever the merge came first.
         *
         * A `LinkedHashMap` is that behaviour exactly: `put` on a present key
         * replaces the value and keeps the original position. Nothing here needs
         * to know which entries are the mapping's own, and an earlier attempt
         * that did — collecting the own keys in a pass up front and suppressing
         * merged ones against them — put the own entry at its own document
         * position instead, which flipped the winner for anything declared
         * between the `<<` and it.
         *
         * Position is invisible until two entries collapse onto one text at
         * step 2, and then it is the whole answer.
         */
        private fun mergeResolved(node: MappingNode): List<MappingEntry> {
            val ordered = LinkedHashMap<Any, MappingEntry>()
            for (tuple in node.value) {
                if (tuple.keyNode.tag != Tag.MERGE) {
                    resolveEntry(tuple)?.let { ordered[it.constructedKey] = it }
                    continue
                }
                for (source in yamlMergeSources(tuple.valueNode)) {
                    // A mapping that merges itself, directly or through a chain.
                    if (!onPath.add(source)) continue
                    for (merged in mergeResolved(source)) {
                        // `<<: *a` before `<<: *b`, and `*a` before `*b` within
                        // one sequence: the first source to claim a key keeps it.
                        if (merged.constructedKey !in ordered) {
                            ordered[merged.constructedKey] = merged
                        }
                    }
                    onPath.remove(source)
                }
            }
            return ordered.values.toList()
        }

        /** One entry resolved into both identities, or null where it has none. */
        private fun resolveEntry(tuple: NodeTuple): MappingEntry? {
            // A complex key (`? [a, b]`) names nothing a placeholder can spell.
            val keyNode = tuple.keyNode as? ScalarNode ?: return null
            val constructed = try {
                keyConstructor.constructKey(keyNode)
            } catch (_: Exception) {
                // A tag no SafeConstructor knows. Spring's loader uses one too,
                // so the runtime resolves nothing at all from this body.
                return null
            } ?: return null
            val segment = segmentFor(constructed) ?: return null
            return MappingEntry(constructed, segment, keyNode.startMark.line, tuple.valueNode)
        }

        /**
         * The path segment a constructed key contributes, or null where it
         * contributes none — which is also every case the runtime gives out on
         * rather than resolving a key of its own.
         *
         * Spring's `asMap` decides on the constructed key's type: a `CharSequence`
         * is a name and takes a separator, anything else is bracketed and spelled
         * by its `toString`. The refusals here and in [resolveEntry] follow the
         * runtime failing the whole load, so contributing nothing is the closest
         * the plugin can get while still keeping the rest of the body's keys
         * (#178). A null key is one of them: Spring spells a non-`CharSequence`
         * key with `key.toString()`, which for null throws inside Spring itself.
         */
        private fun segmentFor(constructed: Any): PathSeg.MapKey? {
            return when (constructed) {
                is CharSequence -> PathSeg.MapKey(constructed.toString())
                // `!!binary` constructs a `ByteArray`, whose `toString` is an
                // identity hash: a different spelling on every parse, matching
                // neither the runtime's own `[[B@…]` nor its previous self. It is
                // the one construction whose key is not a function of the body,
                // and letting it through would put a fresh phantom into the key
                // index on every rebuild and make this extraction impure. No
                // placeholder can spell it either way, so it contributes nothing
                // — the same answer as the two refusals around it.
                is ByteArray -> null
                // Otherwise `toString` is the runtime's own spelling, whatever it
                // renders: `[16]` for `0x10`, `[Infinity]` for `.inf`. The
                // brackets go on here rather than in the renderer because this is
                // where the runtime adds them, and because from here on such a
                // key is indistinguishable from one a user spelled that way —
                // which is exactly what the runtime does with it.
                else -> PathSeg.MapKey("[$constructed]")
            }
        }
    }

    /**
     * One entry of a mapping, resolved into both of the identities it travels
     * under: [constructedKey] is what snakeyaml's `<<` resolution dedupes on, and
     * [segment] is the text Spring's flattening keys on. They are not the same
     * relation — see [YamlWalk.entriesOf].
     */
    private class MappingEntry(
        val constructedKey: Any,
        val segment: PathSeg.MapKey,
        val declarationLine: Int,
        val valueNode: Node
    )

    /**
     * Reaches [SafeConstructor]'s own construction of one scalar node.
     *
     * The subclass exists only to widen `constructObject`'s visibility; it adds
     * no rule of its own, which is the point. `SafeConstructor` is the family
     * Spring Boot's YAML loader extends, so a tag it refuses is a tag the
     * runtime refuses, and a value it builds is the value the runtime spells the
     * key from.
     */
    private class KeyConstructor(options: LoaderOptions) : SafeConstructor(options) {
        fun constructKey(node: ScalarNode): Any? = constructObject(node)
    }

    /**
     * snakeyaml's resolver minus the implicit timestamp tag — the resolver
     * Spring Boot's YAML property source loader composes with.
     *
     * It matters only now that a mapping key is constructed. Under snakeyaml's
     * default, `2020-01-01:` resolves to `Tag.TIMESTAMP` and constructs to a
     * `Date`, which is not a `CharSequence`, so the key would be bracketed and
     * spelled from `Date.toString()` — a key the runtime never resolves, and one
     * whose text would depend on the reading JVM's time zone. Spring drops that
     * resolver, so such a key stays an ordinary string key; matching the runtime
     * means dropping it here too rather than deciding separately what a date
     * looks like.
     */
    private class RuntimeResolver : Resolver() {
        override fun addImplicitResolver(tag: Tag, regexp: Pattern, first: String?, limit: Int) {
            if (tag == Tag.TIMESTAMP) return
            super.addImplicitResolver(tag, regexp, first, limit)
        }
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
                    // A JSON key is always a string, so it is already the text
                    // its loader flattens — including the bracket join, which
                    // Spring Cloud Alibaba's loader applies exactly as Spring
                    // Boot's YAML one does. The corpus asserts each against its
                    // own loader, so a future divergence is caught rather than
                    // assumed away by the shared renderer.
                    path.addLast(PathSeg.MapKey(name))
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
