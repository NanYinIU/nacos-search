package com.nanyin.nacos.search.psi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The differential oracle: the plugin's key set is asserted against the key set
 * the **runtime** resolves, over [ExtractionCorpus].
 *
 * This is what turns ADR-0055's authority — where the plugin and the runtime
 * would differ about what a placeholder resolves to, the runtime wins — from a
 * design principle into something that fails the build. The enumerated oracle in
 * [ConfigKeyExtractorTest] cannot do that job on its own: it was entirely green
 * while three whole classes of key were being invented or destroyed, because an
 * oracle made of cases we imagined cannot catch defects we did not imagine.
 *
 * ## Which oracle is in force
 *
 * [RuntimeKeyOracle.IN_FORCE] — the runtime's own `PropertySourceLoader`
 * implementations, running live on the test classpath. #170 permits a weaker
 * fallback, a corpus of key sets captured from the runtime once and checked in,
 * but only if the loaders cannot be made to run here. The spike recorded on that
 * issue established that they can, so the fallback is not built and the live
 * oracle is what these assertions are against. That is stated here, and asserted
 * by [the oracle in force is the runtime's own loaders], so a weaker oracle can
 * never quietly stand in for this one.
 *
 * ## The relation asserted
 *
 * Not raw set equality, and deliberately not equality-after-normalising both
 * sides — normalising is how a real difference gets masked. Two directions,
 * each with its own meaning:
 *
 * - **Nothing lost.** Every key the runtime resolves is in the plugin's key set,
 *   spelled identically. A miss here is navigation the plugin destroys.
 * - **Nothing invented.** Every key the plugin holds is one the runtime resolves,
 *   or its dot-form alias ([KeyForms]). A miss here is a marker promising a
 *   placeholder that fails at runtime.
 *
 * Together those say the key set equals the runtime's, up to the one widening the
 * plugin makes on purpose.
 *
 * ## Where it runs
 *
 * At [ConfigKeyExtractor.extract] — the same pure seam as the enumerated tests,
 * with no platform fixture. Keys only: the line index is derived separately from
 * the key set on purpose (a wrong line misplaces an icon, a wrong key set invents
 * or destroys navigation), so pinning it belongs with the enumerated cases.
 */
class ExtractionDifferentialTest {

    @Test
    fun `the oracle in force is the runtime's own loaders`() {
        // A canary per format, whose key set only a real loader knows: the
        // properties one joins a continuation line, the YAML one contributes both
        // documents of a stream and indexes a sequence, and the JSON one indexes
        // an array whose last element is a string. A stub, or the weaker
        // captured-corpus fallback standing in silently, answers none of them.
        assertEquals(
            RuntimeKeyOracle.Keys.Loaded(setOf("urls", "next")),
            RuntimeKeyOracle.keysOf("urls=a,\\\n  b\nnext=1\n", RuntimeConfigFormat.PROPERTIES)
        )
        assertEquals(
            RuntimeKeyOracle.Keys.Loaded(setOf("l[0].n", "l[1].n", "d")),
            RuntimeKeyOracle.keysOf("l:\n  - n: x\n  - n: y\n---\nd: 2\n", RuntimeConfigFormat.YAML)
        )
        assertEquals(
            RuntimeKeyOracle.Keys.Loaded(setOf("a[0]", "b")),
            RuntimeKeyOracle.keysOf("""{"a":["x"],"b":1}""", RuntimeConfigFormat.JSON)
        )
    }

    @Test
    fun `every known defect this oracle found names the issue tracking it`() {
        // A defect recorded here is one this oracle caught and the slice that ran
        // it did not fix. Pinning it keeps the exact difference on record — the
        // day it is fixed, the entry's declaration stops matching and the test
        // says so. What must not happen is a defect parked here anonymously,
        // indistinguishable from an intended difference, so each one has to name
        // where it is tracked.
        val defects = ExtractionCorpus.entries
            .filter { it.divergence?.kind == ExtractionCorpus.Kind.KNOWN_DEFECT }
        for (entry in defects) {
            val why = entry.divergence?.why.orEmpty()
            assertTrue(
                Regex("""#\d+""").containsMatchIn(why),
                "${entry.name}: a KNOWN_DEFECT divergence must name its tracking issue in " +
                    "`why`, otherwise it is indistinguishable from an intended difference " +
                    "and nothing will ever close it. Got: $why"
            )
        }
    }

    @TestFactory
    fun `the plugin's key set equals the runtime's over the shared corpus`(): List<DynamicTest> =
        ExtractionCorpus.entries.map { entry ->
            DynamicTest.dynamicTest(entry.name) { assertAgreesWithRuntime(entry) }
        }

    private fun assertAgreesWithRuntime(entry: ExtractionCorpus.Entry) {
        val plugin = pluginKeys(entry.body, entry.format, entry.name)
        val runtime = RuntimeKeyOracle.keysOf(entry.body, entry.format)
        val declared = entry.divergence

        assertEquals(
            declared?.runtimeRefuses ?: false,
            runtime is RuntimeKeyOracle.Keys.Refused,
            "${entry.name}: whether the runtime loads this body is part of what the " +
                "corpus states. The oracle answered $runtime.${because(declared)}"
        )

        // A refusal says nothing about the body's key space, so it contributes no
        // expectation beyond the one just asserted.
        val resolved = (runtime as? RuntimeKeyOracle.Keys.Loaded)?.names ?: emptySet()
        val permitted = resolved.flatMap(KeyForms::bothForms).toSet()

        assertEquals(
            declared?.onlyRuntime ?: emptySet<String>(),
            resolved - plugin,
            "${entry.name}: the runtime resolves keys the plugin does not, so a " +
                "placeholder that works at runtime has no marker. Oracle: " +
                "${RuntimeKeyOracle.IN_FORCE}.${because(declared)}"
        )
        assertEquals(
            declared?.onlyPlugin ?: emptySet<String>(),
            plugin - permitted,
            "${entry.name}: the plugin holds keys the runtime does not resolve, so a " +
                "marker promises a placeholder that fails at runtime. Oracle: " +
                "${RuntimeKeyOracle.IN_FORCE}.${because(declared)}"
        )
    }

    private fun because(divergence: ExtractionCorpus.Divergence?): String =
        divergence?.let { " The corpus declares this difference as ${it.kind}: ${it.why}." } ?: ""
}
