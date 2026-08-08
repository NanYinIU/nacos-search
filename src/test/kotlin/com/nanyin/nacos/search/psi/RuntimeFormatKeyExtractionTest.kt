package com.nanyin.nacos.search.psi

import com.nanyin.nacos.search.models.NacosConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What every caller of key extraction observes: the keys a configuration
 * contributes when read under the 运行时格式 its data id decides.
 *
 * The declared type takes no part in any case here. The other half of this
 * bridge is still transitional — a refusal reads as "no keys" until it reaches
 * its own terminal resolution state (#166).
 */
class RuntimeFormatKeyExtractionTest {

    private fun config(dataId: String, content: String, type: String?) =
        NacosConfiguration(dataId = dataId, group = "g", tenantId = null, content = content, type = type)

    @Test
    fun `the data id suffix decides the format`() {
        val keys = keysUnderRuntimeFormat(config("app.properties", "k=v", null))
        assertEquals("v", keys["k"]?.value)
    }

    @Test
    fun `a declared non-parsing type does not stop a parseable suffix`() {
        val keys = keysUnderRuntimeFormat(config("app.json", """{"k":"v"}""", "text"))
        assertEquals("v", keys["k"]?.value)
    }

    @Test
    fun `a declared type never decides the format`() {
        // Declared JSON, but nothing about the data id says the runtime will
        // read it as JSON — so it is not extracted, body notwithstanding.
        assertTrue(keysUnderRuntimeFormat(config("service-config", """{"k":"v"}""", "json")).isEmpty())
        // ...nor can a declared type contradict a suffix: this is read as YAML,
        // under which a JSON object is a flow mapping — the keys it yields are
        // YAML's, not the ones the declared type would have produced.
        assertEquals(
            setOf("k"),
            keysUnderRuntimeFormat(config("app.yaml", """{"k":"v"}""", "properties")).keys
        )
    }

    @Test
    fun `one configuration answers the display question and the extraction question differently`() {
        // The whole point of the split, asserted where both answers are in
        // scope: this badge reads TEXT and this body extracts as properties,
        // and neither answer is wrong (ADR-0055).
        val config = config("app.properties", "k=v", "text")

        assertEquals("text", config.declaredFormat())
        assertEquals("v", keysUnderRuntimeFormat(config)["k"]?.value)
    }

    @Test
    fun `an absent declared type is not an obstacle`() {
        val keys = keysUnderRuntimeFormat(config("app.yaml", "server:\n  port: 8080\n", null))
        assertEquals("8080", keys["server.port"]?.value)
    }

    @Test
    fun `a known non-parsing suffix contributes no keys`() {
        assertTrue(keysUnderRuntimeFormat(config("notes.txt", "k=v", "properties")).isEmpty())
        assertTrue(keysUnderRuntimeFormat(config("app.toml", "k=v", null)).isEmpty())
    }

    @Test
    fun `a data id with no recognizable suffix contributes no keys`() {
        assertTrue(keysUnderRuntimeFormat(config("service-config", "k=v", "properties")).isEmpty())
    }

    @Test
    fun `the two refusals stay distinguishable at the extraction seam`() {
        // The reason has to survive the flattening above, because the terminal
        // resolution state explains the two differently: the user's next action
        // differs even where the outcome does not.
        assertEquals(
            KeyExtraction.NotExtractable(KeyExtraction.Reason.KNOWN_NON_PARSING_FORMAT),
            ConfigKeyExtractor.extract("k=v", RuntimeFormatDecision.forDataId("notes.txt"))
        )
        assertEquals(
            KeyExtraction.NotExtractable(KeyExtraction.Reason.FORMAT_UNDETERMINED),
            ConfigKeyExtractor.extract("k=v", RuntimeFormatDecision.forDataId("service-config"))
        )
    }

    @Test
    fun `the terminal state takes nothing away from a configuration that still extracts`() {
        // This is the seam the detail panel's own gutter markers read
        // (ConfigDetailPanel.applyKeyGutterMarkers), and it keeps answering
        // exactly what it answered before the terminal state existed: keys with
        // their lines for a format the plugin reads, and an empty map — which
        // that panel treats as "no lines to mark", never as an absence claim —
        // for one it does not (issue #172).
        val keys = keysUnderRuntimeFormat(config("app.yaml", "server:\n  port: 8080\n", "text"))
        assertEquals(setOf("server.port"), keys.keys)
        assertEquals(1, keys["server.port"]?.lineIndex)

        assertTrue(keysUnderRuntimeFormat(config("beans.xml", "<beans><a>1</a></beans>", "xml")).isEmpty())
    }

    // ── The refusal a caller holding only a data id can ask for (issue #172) ──

    @Test
    fun `a data id alone says why its format contributes no keys`() {
        assertEquals(KeyExtraction.Reason.KNOWN_NON_PARSING_FORMAT, runtimeFormatRefusal("notes.txt"))
        assertEquals(KeyExtraction.Reason.KNOWN_NON_PARSING_FORMAT, runtimeFormatRefusal("page.html"))
        assertEquals(KeyExtraction.Reason.KNOWN_NON_PARSING_FORMAT, runtimeFormatRefusal("app.toml"))
        assertEquals(KeyExtraction.Reason.PARSER_NOT_IMPLEMENTED, runtimeFormatRefusal("beans.xml"))
        assertEquals(KeyExtraction.Reason.FORMAT_UNDETERMINED, runtimeFormatRefusal("service-config"))
    }

    @Test
    fun `a data id whose format the plugin parses refuses nothing`() {
        assertNull(runtimeFormatRefusal("app.properties"))
        assertNull(runtimeFormatRefusal("app.yaml"))
        assertNull(runtimeFormatRefusal("app.yml"))
        assertNull(runtimeFormatRefusal("app.json"))
    }

    @Test
    fun `the refusal a data id gives is the one a body would have gotten`() {
        // Two callers ask the same question from opposite ends — one holding a
        // body, one holding only a data id — and the answers are read off one
        // table, so no format can be refused to one and parsed for the other.
        RuntimeConfigFormat.values().forEach { format ->
            val withBody = ConfigKeyExtractor.extract("k=v", format)
            val withoutBody = ConfigKeyExtractor.refusalFor(format)
            when (withBody) {
                is KeyExtraction.NotExtractable ->
                    assertEquals(withBody.reason, withoutBody, "$format")
                is KeyExtraction.Extracted ->
                    assertNull(withoutBody, "$format")
            }
        }
    }
}
