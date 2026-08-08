package com.nanyin.nacos.search.psi

/**
 * The three formats the plugin parses, and so the three any oracle can judge it
 * on. Named once because every suite here encodes the same set — the corpus, the
 * generator's renderers, the runtime oracle's loaders — and a fourth parser
 * arriving should not have to be discovered three times.
 */
val PARSED_FORMATS: List<RuntimeConfigFormat> = listOf(
    RuntimeConfigFormat.PROPERTIES,
    RuntimeConfigFormat.YAML,
    RuntimeConfigFormat.JSON
)

/**
 * The plugin's key set at the pure seam, for a body it is supposed to parse.
 *
 * A refusal is raised rather than folded into an empty set. For the three formats
 * above a refusal is itself the defect, and answering `emptySet()` would hand
 * every oracle here a free pass — the same confusion [KeyExtraction] is a closed
 * set to prevent.
 *
 * [label] names the case, so a failure says which body it was without the caller
 * having to repeat itself.
 */
fun pluginKeys(body: String, format: RuntimeConfigFormat, label: String): Set<String> =
    when (val extraction = ConfigKeyExtractor.extract(body, format)) {
        is KeyExtraction.Extracted -> extraction.keys.keys
        is KeyExtraction.NotExtractable -> throw AssertionError(
            "$label: expected a parse under $format, got $extraction"
        )
    }

/**
 * The two spellings one indexed leaf has.
 *
 * The runtime resolves an indexed leaf under the bracket form alone — Spring's
 * relaxed binding accepts `list[0].n`, and that is the single key its property
 * sources hold. The plugin emits the dot form beside it so a `${list.0.n}`
 * placeholder resolves too, which is a deliberate widening and the one respect in
 * which its key set is allowed to exceed the runtime's.
 *
 * Naming it once here keeps that allowance a stated rule rather than a habit
 * spread across two test suites.
 *
 * The allowance has one blind spot, stated so it is not mistaken for coverage: a
 * phantom key whose last segment is an integer is indistinguishable from the dot
 * alias of a sequence index, so the differential relation permits it. A YAML
 * mapping key `0:` is exactly that case — the runtime resolves `[0]` and the
 * plugin's `0` is a key nothing resolves, but it looks like `dotForm("[0]")`.
 * Widening the alias to non-integers would be worse: it would permit far more
 * than the plugin actually emits.
 */
object KeyForms {

    private val BRACKET_INDEX = Regex("""\[(\d+)]""")

    /** `a[0].b` → `a.0.b`; a leading index loses its separator: `[0]` → `0`. */
    fun dotForm(key: String): String =
        BRACKET_INDEX.replace(key) { ".${it.groupValues[1]}" }.removePrefix(".")

    /** Both spellings of [key] — what the plugin is permitted to hold for it. */
    fun bothForms(key: String): Set<String> = setOf(key, dotForm(key))
}
