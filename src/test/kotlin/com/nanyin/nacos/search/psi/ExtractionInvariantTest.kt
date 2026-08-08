package com.nanyin.nacos.search.psi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.random.Random

/**
 * Two invariants over generated bodies ([GeneratedBody]).
 *
 * The enumerated oracle asserts about members of a class of defect; an invariant
 * asserts about the class. That distinction is not academic here — the enumerated
 * class was entirely green while three whole classes of key were being invented
 * or destroyed, because a case list can only hold the cases somebody imagined.
 *
 * - **Origin.** Every extracted key names a leaf the body declares. This kills the
 *   phantom-key class outright rather than testing for its known members: a
 *   block scalar's body, a properties continuation, a comment and a quoted value
 *   that reads like a mapping entry all fail it the same way, including the ones
 *   nobody has thought of yet.
 * - **Position independence.** The number of keys extracted does not depend on
 *   where in the document a value sits. This kills the truncation class — JSON
 *   losing every key declared after an array whose last element was a string was
 *   exactly a key count that depended on position.
 *
 * Both run at [ConfigKeyExtractor.extract], the same pure seam as the enumerated
 * and differential oracles, with no platform fixture.
 *
 * Seeds are fixed and enumerated rather than drawn from a clock, so a failure
 * reproduces from the test name alone.
 */
class ExtractionInvariantTest {

    private val formats = PARSED_FORMATS

    private val seeds = 1..40

    // ---- the invariants ----

    @TestFactory
    fun `every extracted key names a leaf the body declares`(): List<DynamicTest> =
        cases().map { (label, case) ->
            DynamicTest.dynamicTest(label) {
                val extracted = extract(case)
                val withoutOrigin = extracted - case.declaredKeys
                assertTrue(
                    withoutOrigin.isEmpty(),
                    "$label: ${withoutOrigin.size} extracted key(s) name no leaf this body " +
                        "declares, so nothing in it can be navigated to — they were read out " +
                        "of a value rather than out of a declaration.\n" +
                        "  without an origin: ${withoutOrigin.sorted()}\n" +
                        "  declared:          ${case.declaredKeys.sorted()}\n" +
                        "  body:\n${indented(case.body)}"
                )
            }
        }

    @TestFactory
    fun `the extracted key count does not depend on where a value sits`(): List<DynamicTest> =
        seeds.map { seed ->
            DynamicTest.dynamicTest("seed $seed") {
                val random = Random(seed)
                // Enough entries that a truncating parser loses a different number
                // of them depending on which one truncates.
                val original = GeneratedBody.structure(random, entries = 5)
                for (format in formats) {
                    val baseline = extract(GeneratedBody.case(format, original))
                    repeat(PERMUTATIONS) { attempt ->
                        // Reordered at every depth: a value that swallows what
                        // follows it does so wherever it sits, so testing only the
                        // root would leave the truncation class unexercised below
                        // depth zero.
                        val shuffled = GeneratedBody.shuffled(random, original) as GeneratedBody.Structure.Mapping
                        val case = GeneratedBody.case(format, shuffled)
                        assertEquals(
                            baseline.size,
                            extract(case).size,
                            "$format seed $seed permutation $attempt: reordering the same " +
                                "entries changed how many keys came out, so some key's " +
                                "existence depends on what precedes it in the document — a " +
                                "value earlier in the body is swallowing the keys after it.\n" +
                                "  body:\n${indented(case.body)}"
                        )
                    }
                }
            }
        }

    // ---- the generator itself, so neither invariant can pass vacuously ----

    @Test
    fun `a declared name can never be spelled by a value`() {
        // The origin invariant rests on this: if a value contained `n3`, a key
        // read out of that value could name a declared leaf by accident and the
        // invariant would wave it through.
        for (name in GeneratedBody.NAME_ALPHABET) {
            for (value in GeneratedBody.VALUE_POOL) {
                assertTrue(
                    !value.contains(name),
                    "the value '$value' contains the declarable name '$name', so a phantom " +
                        "key read out of it could pass for a declared one"
                )
            }
        }
    }

    @Test
    fun `the generated bodies carry the constructs a phantom key comes from`() {
        // A generator that only ever emitted `k: v` would satisfy both invariants
        // against a parser that leaks block scalars. These are the constructs the
        // invariants need to be exercised by, so their presence is asserted rather
        // than assumed of the seeds.
        val bodies = cases().map { it.second }
        assertTrue(
            bodies.any { it.format == RuntimeConfigFormat.YAML && it.body.contains(": |\n") },
            "no generated YAML body contains a block scalar"
        )
        assertTrue(
            bodies.any { it.format == RuntimeConfigFormat.PROPERTIES && it.body.contains("\\\n") },
            "no generated properties body contains a continuation line"
        )
        assertTrue(
            bodies.any { it.format == RuntimeConfigFormat.JSON && it.body.contains("""["""") },
            "no generated JSON body contains an array whose element is a string"
        )
        assertTrue(
            bodies.any { it.body.contains("phantom") },
            "no generated body contains a value that reads like a mapping entry"
        )
        // Nesting inside a sequence is the shape whose indexes used to collapse.
        assertTrue(
            bodies.any { case -> GeneratedBody.leafPaths(case.structure).any { it.first.contains("].") } },
            "no generated body nests a mapping inside a sequence"
        )
    }

    @Test
    fun `a generated body declares the keys the plugin extracts from it`() {
        // The origin invariant is one-directional by design — it asks whether an
        // extracted key has somewhere to point, not whether every declaration was
        // found. Without this, a parser that extracted nothing at all would
        // satisfy it perfectly.
        val cases = cases()
        assertTrue(cases.isNotEmpty(), "the generator produced no cases")
        for ((label, case) in cases) {
            assertTrue(
                extract(case).isNotEmpty(),
                "$label: extracted no keys at all, so the origin invariant holds vacuously.\n" +
                    "  body:\n${indented(case.body)}"
            )
        }
    }

    // ---- helpers ----

    private fun cases(): List<Pair<String, GeneratedBody.Case>> =
        seeds.flatMap { seed ->
            // One structure per seed rendered under all three formats, so a
            // difference between them is the format's and not the shape's.
            val structure = GeneratedBody.structure(Random(seed))
            formats.map { format -> "$format seed $seed" to GeneratedBody.case(format, structure) }
        }

    private fun extract(case: GeneratedBody.Case): Set<String> =
        pluginKeys(case.body, case.format, "generated body")

    private fun indented(body: String) = body.lineSequence().joinToString("\n") { "    | $it" }

    private companion object {
        const val PERMUTATIONS = 6
    }
}
