package com.nanyin.nacos.search.psi

import kotlin.random.Random

/**
 * A configuration body generated from a structure whose declared leaves are known
 * before anything parses it.
 *
 * The invariants in [ExtractionInvariantTest] need inputs nobody chose. A fixed
 * list of bodies can only ever assert about phantom keys we already thought of,
 * and the phantom-key class is precisely the one where our imagination had
 * already been shown to fall short: the enumerated tests were green while YAML
 * block scalars leaked their body text back into the key space.
 *
 * Two properties make the generated bodies useful rather than merely random.
 *
 * **The declarations are known.** [Structure] is the ground truth, and
 * [leafPaths] is exactly the set of keys the body declares. A key outside it has
 * no declaration to point at — it came from text read as a declaration, which is
 * what a phantom key is.
 *
 * **The values are adversarial.** Every scalar is drawn from [VALUE_POOL], whose
 * members look like declarations: `phantom: 1`, `also=2`, a shell script, a
 * nested JSON object, a multi-line body that becomes a YAML block scalar and a
 * properties continuation. A generator whose values were `v1`, `v2`, `v3` would
 * pass against a parser that leaked them, so it would prove nothing.
 *
 * The two are kept apart by construction: names come from [NAME_ALPHABET] and no
 * pool value contains any of them as a substring, so a key derived from value
 * text can never spell a declared one by accident. That disjointness is asserted
 * rather than assumed — see the generator-sanity test.
 *
 * Empty containers are deliberately not generated. They are the one place the
 * formats' key spaces legitimately differ (a YAML empty container is a leaf, a
 * JSON one is dropped), and a per-format ground truth would make these
 * invariants about that difference rather than about phantoms and positions.
 * [ExtractionCorpus] holds that case against the runtime instead.
 */
object GeneratedBody {

    /**
     * A tree of mappings, sequences and scalars — the shape both a YAML document
     * and a JSON document flatten to a key space from.
     */
    sealed interface Structure {
        data class Leaf(val text: String) : Structure
        data class Mapping(val entries: List<Pair<String, Structure>>) : Structure
        data class Sequence(val items: List<Structure>) : Structure
    }

    /**
     * Names a declared key may be spelled from. Short and closed so that
     * disjointness from [VALUE_POOL] is checkable rather than hopeful.
     */
    val NAME_ALPHABET: List<String> = (0..7).map { "n$it" }

    /**
     * Scalar values that look like declarations. Each is here because some parser
     * has read something of this shape as a key: a mapping entry, a properties
     * assignment, a JSON object, a sequence item, a comment, a URL with colons,
     * a flow sequence, and two multi-line bodies — which render as a YAML block
     * scalar and a properties continuation, the two constructs whose bodies used
     * to leak wholesale into the key space.
     */
    val VALUE_POOL: List<String> = listOf(
        "phantom: 1",
        "also=2",
        """{"deep": {"er": 3}}""",
        "- item",
        "first: 1\nsecond: 2",
        "#!/bin/sh\necho hi\nexport A=b",
        "hello # trailing",
        "jdbc:mysql://host:3306/db",
        """["a", "b"]""",
        "key = value",
        "v",
        "42",
        "true",
        ""
    )

    /** One generated case: the ground truth and its rendering under [format]. */
    data class Case(
        val format: RuntimeConfigFormat,
        val structure: Structure.Mapping,
        val body: String
    ) {
        /**
         * Every key this body declares, in the bracket form the runtime resolves,
         * plus the dot-form alias the plugin adds beside it ([KeyForms]).
         */
        val declaredKeys: Set<String> =
            leafPaths(structure).flatMap { KeyForms.bothForms(it.first) }.toSet()
    }

    /**
     * A root mapping of [entries] entries, nested up to [maxDepth].
     *
     * The root is always a mapping because that is what a configuration body is;
     * neither runtime loader accepts a document whose root is a scalar or a
     * sequence.
     */
    fun structure(random: Random, entries: Int = random.nextInt(2, 6), maxDepth: Int = 3): Structure.Mapping =
        mapping(random, entries, maxDepth)

    /** [structure] rendered under [format], paired with its ground truth. */
    fun case(format: RuntimeConfigFormat, structure: Structure.Mapping): Case =
        Case(format, structure, render(format, structure))

    fun render(format: RuntimeConfigFormat, structure: Structure.Mapping): String = when (format) {
        RuntimeConfigFormat.PROPERTIES -> renderProperties(structure)
        RuntimeConfigFormat.YAML -> StringBuilder().also { renderYamlMapping(structure, 0, it) }.toString()
        RuntimeConfigFormat.JSON -> renderJson(structure)
        else -> throw IllegalArgumentException("no renderer for $format")
    }

    /** Every declared leaf as `bracket path to text`, in document order. */
    fun leafPaths(structure: Structure): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        collectLeaves(structure, "", found)
        return found
    }

    /**
     * The same declarations, moved around: every mapping's entries and every
     * sequence's items reordered, at every depth.
     *
     * Reordering only the root would test the truncation class at depth zero and
     * nowhere else, and a value that swallows what follows it does so wherever it
     * sits — the JSON array whose last element was a string took out the rest of
     * *its own* object, however deeply nested that object was.
     *
     * The key set changes (a sequence's indexes move with its items), which is
     * why the invariant this feeds is about the key *count*.
     */
    fun shuffled(random: Random, structure: Structure): Structure = when (structure) {
        is Structure.Leaf -> structure
        is Structure.Mapping -> Structure.Mapping(
            structure.entries.shuffled(random).map { (name, child) -> name to shuffled(random, child) }
        )
        is Structure.Sequence -> Structure.Sequence(
            structure.items.shuffled(random).map { shuffled(random, it) }
        )
    }

    // ----- generation -------------------------------------------------------

    private fun mapping(random: Random, entries: Int, remainingDepth: Int): Structure.Mapping =
        // Names are drawn without replacement, so no mapping declares one twice.
        // A duplicate would make the loaders disagree for a reason that has
        // nothing to do with what is being asserted: Spring Boot's YAML loader
        // refuses a duplicate key outright, where properties and JSON let the
        // last one win.
        Structure.Mapping(
            NAME_ALPHABET.shuffled(random).take(entries).map { it to child(random, remainingDepth) }
        )

    private fun child(random: Random, remainingDepth: Int): Structure = when {
        remainingDepth <= 0 -> leaf(random)
        else -> when (random.nextInt(4)) {
            0 -> mapping(random, random.nextInt(1, 4), remainingDepth - 1)
            1 -> Structure.Sequence(
                (0 until random.nextInt(1, 4)).map { child(random, remainingDepth - 1) }
            )
            else -> leaf(random)
        }
    }

    private fun leaf(random: Random) = Structure.Leaf(VALUE_POOL[random.nextInt(VALUE_POOL.size)])

    private fun collectLeaves(
        structure: Structure,
        path: String,
        found: MutableList<Pair<String, String>>
    ) {
        when (structure) {
            is Structure.Leaf -> found.add(path to structure.text)
            is Structure.Mapping -> structure.entries.forEach { (name, child) ->
                collectLeaves(child, if (path.isEmpty()) name else "$path.$name", found)
            }
            is Structure.Sequence -> structure.items.forEachIndexed { index, item ->
                collectLeaves(item, "$path[$index]", found)
            }
        }
    }

    // ----- rendering --------------------------------------------------------

    private fun renderYamlMapping(mapping: Structure.Mapping, indent: Int, sb: StringBuilder) {
        val pad = " ".repeat(indent)
        for ((name, child) in mapping.entries) {
            sb.append(pad).append(name).append(":")
            renderYamlChild(child, indent, sb)
        }
    }

    private fun renderYamlSequence(sequence: Structure.Sequence, indent: Int, sb: StringBuilder) {
        val pad = " ".repeat(indent)
        for (item in sequence.items) {
            sb.append(pad).append("-")
            renderYamlChild(item, indent, sb)
        }
    }

    /** Renders the value part of an entry whose introducer is already written. */
    private fun renderYamlChild(child: Structure, indent: Int, sb: StringBuilder) {
        when (child) {
            is Structure.Leaf -> sb.append(yamlScalar(child.text, indent + 2)).append("\n")
            // A container opens on the next line, indented past its introducer.
            // A sequence element that opens a mapping this way is the shape the
            // scanner this replaced collapsed onto index zero.
            is Structure.Mapping -> {
                sb.append("\n")
                renderYamlMapping(child, indent + 2, sb)
            }
            is Structure.Sequence -> {
                sb.append("\n")
                renderYamlSequence(child, indent + 2, sb)
            }
        }
    }

    /**
     * A multi-line value becomes a literal block scalar, which is the whole
     * point of generating multi-line values: its body is one value however much
     * it reads like a key list. A single-line value is double-quoted, so a colon
     * or a hash in it cannot be mistaken for structure by anything except a
     * defect.
     */
    private fun yamlScalar(text: String, bodyIndent: Int): String =
        if (text.contains('\n')) {
            val pad = " ".repeat(bodyIndent)
            " |\n" + text.lineSequence().joinToString("\n") { pad + it }
        } else {
            " \"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

    private fun renderJson(structure: Structure): String = when (structure) {
        is Structure.Leaf -> jsonString(structure.text)
        is Structure.Mapping -> structure.entries.joinToString(",", "{", "}") { (name, child) ->
            "${jsonString(name)}:${renderJson(child)}"
        }
        is Structure.Sequence -> structure.items.joinToString(",", "[", "]") { renderJson(it) }
    }

    private fun jsonString(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    /**
     * Properties has no structure, so the tree is rendered as the flattened keys
     * the runtime resolves it to — the bracket form Spring's relaxed binding
     * accepts, written out longhand.
     *
     * A multi-line value becomes a continuation, the construct whose second line
     * a reader used to mistake for a declaration of its own.
     */
    private fun renderProperties(structure: Structure.Mapping): String =
        leafPaths(structure).joinToString("") { (path, text) ->
            "$path=${propertiesValue(text)}\n"
        }

    private fun propertiesValue(text: String): String =
        // Backslashes first: escaping them afterwards would consume the
        // continuation marker this then appends.
        text.replace("\\", "\\\\").replace("\n", "\\\n  ")
}
