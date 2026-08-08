package com.nanyin.nacos.search.psi

import com.nanyin.nacos.search.models.NacosConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
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
        // The bridge flattens both to "no keys" for now, but the reason has to
        // survive: the terminal resolution state that replaces the flattening
        // explains the two differently, because the user's next action differs.
        assertEquals(
            KeyExtraction.NotExtractable(KeyExtraction.Reason.KNOWN_NON_PARSING_FORMAT),
            ConfigKeyExtractor.extract("k=v", RuntimeFormatDecision.forDataId("notes.txt"))
        )
        assertEquals(
            KeyExtraction.NotExtractable(KeyExtraction.Reason.FORMAT_UNDETERMINED),
            ConfigKeyExtractor.extract("k=v", RuntimeFormatDecision.forDataId("service-config"))
        )
    }
}
