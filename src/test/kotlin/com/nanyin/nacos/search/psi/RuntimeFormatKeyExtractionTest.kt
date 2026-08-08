package com.nanyin.nacos.search.psi

import com.nanyin.nacos.search.models.NacosConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What every caller of key extraction observes: the keys a configuration
 * contributes when read under its 运行时格式 — decided by the type a reference
 * site declares about it, and otherwise by its data id.
 *
 * The configuration's own 声明格式 takes no part in any case here. The other
 * half of this bridge is still transitional — a refusal reads as "no keys"
 * until it reaches its own terminal resolution state (#166).
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
    fun `a configuration's own non-parsing declared type does not stop a parseable suffix`() {
        val keys = keysUnderRuntimeFormat(config("app.json", """{"k":"v"}""", "text"))
        assertEquals("v", keys["k"]?.value)
    }

    @Test
    fun `a configuration's own declared type never decides the format`() {
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
    fun `an absent declared type on the configuration is not an obstacle`() {
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

    // ---- what a reference site declares about the configuration it names ----

    @Test
    fun `a reference-site declaration decides the format for the configuration it names`() {
        val config = config("app.properties", "server:\n  port: 8080\n", null)
        val site = NacosCodeContext(dataId = "app.properties", declaredType = "YAML")

        // Read under the suffix this is two flat properties keys; under the
        // declaration it is one nested YAML key. Both readings are of the same
        // body, and only one of them is what the runtime will do (#173).
        assertEquals(setOf("server", "port"), keysUnderRuntimeFormat(config).keys)
        assertEquals(
            setOf("server.port"),
            keysUnderRuntimeFormat(config, site.runtimeFormatOverride()).keys
        )
    }

    @Test
    fun `a declaration rescues a data id with no recognizable suffix`() {
        val config = config("service-config", """{"k":"v"}""", null)
        val site = NacosCodeContext(dataId = "service-config", declaredType = "JSON")

        assertTrue(keysUnderRuntimeFormat(config).isEmpty())
        assertEquals("v", keysUnderRuntimeFormat(config, site.runtimeFormatOverride())["k"]?.value)
    }

    @Test
    fun `a declaration rescues an undetermined data id from the refusal itself`() {
        // Stated at the extraction seam rather than through the key set, because
        // the refusal is what reaches the terminal 格式不参与解析 state (#172):
        // a declared type must not arrive there at all.
        assertEquals(
            KeyExtraction.NotExtractable(KeyExtraction.Reason.FORMAT_UNDETERMINED),
            ConfigKeyExtractor.extract("""{"k":"v"}""", RuntimeFormatDecision.forDataId("service-config"))
        )
        val declared = ConfigKeyExtractor.extract(
            """{"k":"v"}""",
            RuntimeFormatDecision.forReference("service-config", "JSON")
        )
        assertEquals(
            KeyExtraction.Extracted::class.java,
            declared.javaClass,
            "a declared type must reach a parse, never a refusal"
        )
        assertEquals(setOf("k"), (declared as KeyExtraction.Extracted).keys.keys)
    }

    @Test
    fun `a declaration says nothing about a configuration it does not name`() {
        // Resolution still reaches other data ids by discovery when the declared
        // one has no hit; reading those under this site's declaration would be
        // extracting a configuration by rules nobody wrote for it.
        val other = config("other.properties", "server:\n  port: 8080\n", null)
        val site = NacosCodeContext(dataId = "app.properties", declaredType = "YAML")

        assertEquals(setOf("server", "port"), keysUnderRuntimeFormat(other, site.runtimeFormatOverride()).keys)
    }

    @Test
    fun `a declared type that agrees with the suffix raises no override`() {
        // Nothing to re-derive: the 派生 key 索引 already holds exactly these
        // definitions, so the per-reference path stays off the hot path.
        assertNull(NacosCodeContext(dataId = "app.json", declaredType = "JSON").runtimeFormatOverride())
        assertNull(NacosCodeContext(dataId = "app.json", declaredType = "UNSET").runtimeFormatOverride())
        assertNull(NacosCodeContext(dataId = "app.json").runtimeFormatOverride())
    }

    @Test
    fun `a declaration with no data id names no configuration to override`() {
        // The override is resolved against one configuration coordinate, and a
        // site with no data id supplies none.
        assertNull(NacosCodeContext(declaredType = "JSON").runtimeFormatOverride())
        assertNull(NacosCodeContext(dataId = "  ", declaredType = "JSON").runtimeFormatOverride())
    }

    @Test
    fun `a declaration that changes the format raises an override for that coordinate`() {
        assertEquals(
            ReferenceFormatOverride("app.properties", null, null, RuntimeConfigFormat.YAML),
            NacosCodeContext(dataId = "app.properties", declaredType = "YAML").runtimeFormatOverride()
        )
        assertEquals(
            ReferenceFormatOverride("service-config", "APP_GROUP", "dev", RuntimeConfigFormat.JSON),
            NacosCodeContext(
                dataId = "service-config",
                group = "APP_GROUP",
                namespaceId = "dev",
                declaredType = "JSON"
            ).runtimeFormatOverride()
        )
    }

    @Test
    fun `an override narrows to the group and Namespace the site declared`() {
        val site = NacosCodeContext(
            dataId = "app.properties",
            group = "APP_GROUP",
            namespaceId = "dev",
            declaredType = "YAML"
        ).runtimeFormatOverride()!!

        assertTrue(site.appliesTo(NacosConfiguration("app.properties", "APP_GROUP", "dev", "", null)))
        // Same data id, a group or Namespace this site said nothing about: the
        // index's reading of those stands.
        assertFalse(site.appliesTo(NacosConfiguration("app.properties", "OTHER_GROUP", "dev", "", null)))
        assertFalse(site.appliesTo(NacosConfiguration("app.properties", "APP_GROUP", "prod", "", null)))
        assertFalse(site.appliesTo(NacosConfiguration("other.properties", "APP_GROUP", "dev", "", null)))
    }

    @Test
    fun `an override the site left wide reaches every copy of that data id`() {
        // A site that declares no group or Namespace knows nothing that would
        // narrow it, and asserting less than it wrote would leave the copy it
        // meant read the way it is contradicting.
        val site = NacosCodeContext(dataId = "app.properties", declaredType = "YAML").runtimeFormatOverride()!!

        assertTrue(site.appliesTo(NacosConfiguration("app.properties", "APP_GROUP", "dev", "", null)))
        assertTrue(site.appliesTo(NacosConfiguration("app.properties", "OTHER_GROUP", "prod", "", null)))
    }

    @Test
    fun `a declared public Namespace matches the same configuration a blank one does`() {
        val declaresPublic = NacosCodeContext(
            dataId = "app.properties",
            namespaceId = "public",
            declaredType = "YAML"
        ).runtimeFormatOverride()!!

        assertTrue(declaresPublic.appliesTo(NacosConfiguration("app.properties", "g", null, "", null)))
        assertTrue(declaresPublic.appliesTo(NacosConfiguration("app.properties", "g", "public", "", null)))
        assertFalse(declaresPublic.appliesTo(NacosConfiguration("app.properties", "g", "dev", "", null)))
    }

    @Test
    fun `appliesTo matches coordinate Namespace when payload tenant is absent`() {
        // Issue #190: a site that names Namespace "dev" must re-derive a body
        // written under coordinate "dev" even when the response omits tenant.
        val site = ReferenceFormatOverride(
            dataId = "service-config",
            group = "DEFAULT_GROUP",
            namespaceId = "dev",
            format = RuntimeConfigFormat.YAML
        )
        val body = NacosConfiguration("service-config", "DEFAULT_GROUP", null, "k: v\n", "yaml")

        // Payload-only path cannot see the coordinate → miss (documented limit).
        assertFalse(site.appliesTo(body))
        // Coordinate path matches.
        assertTrue(site.appliesTo(body, coordinateNamespaceId = "dev"))
        assertFalse(site.appliesTo(body, coordinateNamespaceId = "prod"))

        // keysUnderRuntimeFormat must use the coordinate so the override format
        // is applied rather than the undetermined suffix refusal.
        assertEquals(
            "v",
            keysUnderRuntimeFormat(body, site, coordinateNamespaceId = "dev")["k"]?.value
        )
        assertTrue(keysUnderRuntimeFormat(body, site).isEmpty())
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
