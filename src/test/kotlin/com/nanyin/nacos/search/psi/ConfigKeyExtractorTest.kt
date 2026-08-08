package com.nanyin.nacos.search.psi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigKeyExtractorTest {

    /** Asserts a successful parse and returns its key set. */
    private fun keys(content: String, format: RuntimeConfigFormat): Map<String, ConfigKeyExtractor.KeyLocation> {
        val result = ConfigKeyExtractor.extract(content, format)
        assertTrue(result is KeyExtraction.Extracted, "expected a parse, got $result")
        return (result as KeyExtraction.Extracted).keys
    }

    private fun notExtractable(content: String, format: RuntimeConfigFormat): KeyExtraction.NotExtractable {
        val result = ConfigKeyExtractor.extract(content, format)
        assertTrue(result is KeyExtraction.NotExtractable, "expected a refusal, got $result")
        return result as KeyExtraction.NotExtractable
    }

    // ---- properties ----

    @Test
    fun `properties extracts key equals value`() {
        val map = keys("app.name=my-service\napp.port=8080\n", RuntimeConfigFormat.PROPERTIES)
        assertEquals("my-service", map["app.name"]?.value)
        assertEquals("8080", map["app.port"]?.value)
        assertEquals(0, map["app.name"]?.lineIndex)
        assertEquals(1, map["app.port"]?.lineIndex)
    }

    @Test
    fun `properties skips comments and blanks`() {
        val map = keys("# a comment\n\n! bang comment\nkey=v\n", RuntimeConfigFormat.PROPERTIES)
        assertEquals(1, map.size)
        assertEquals("v", map["key"]?.value)
    }

    @Test
    fun `properties supports colon separator`() {
        val map = keys("host: localhost\n", RuntimeConfigFormat.PROPERTIES)
        assertEquals("localhost", map["host"]?.value)
    }

    @Test
    fun `properties later key overrides earlier`() {
        val map = keys("k=1\nk=2\n", RuntimeConfigFormat.PROPERTIES)
        assertEquals("2", map["k"]?.value)
    }

    // ---- yaml ----

    @Test
    fun `yaml flat key value`() {
        val map = keys("app:\n  name: svc\n  port: 8080\n", RuntimeConfigFormat.YAML)
        assertEquals("svc", map["app.name"]?.value)
        assertEquals("8080", map["app.port"]?.value)
    }

    @Test
    fun `yaml top level value`() {
        val map = keys("timeout: 3000\n", RuntimeConfigFormat.YAML)
        assertEquals("3000", map["timeout"]?.value)
    }

    @Test
    fun `yaml strips quoted values`() {
        val map = keys("name: \"hello\"\n", RuntimeConfigFormat.YAML)
        assertEquals("hello", map["name"]?.value)
    }

    @Test
    fun `yaml skips list items`() {
        val map = keys("items:\n  - one\n  - two\nreal: yes\n", RuntimeConfigFormat.YAML)
        assertNull(map["items.-"])
        assertEquals("yes", map["real"]?.value)
    }

    @Test
    fun `yaml three level nesting flattens`() {
        val map = keys("sys:\n  audit:\n    switch: true\n", RuntimeConfigFormat.YAML)
        assertEquals("true", map["sys.audit.switch"]?.value)
    }

    @Test
    fun `yaml sibling keys reset path`() {
        val map = keys("server:\n  port: 8080\nlogging:\n  level: INFO\n", RuntimeConfigFormat.YAML)
        assertEquals("8080", map["server.port"]?.value)
        assertEquals("INFO", map["logging.level"]?.value)
    }

    @Test
    fun `yaml list items flatten with both index forms`() {
        val map = keys("tabs:\n  - id: 1\n  - id: 2\n", RuntimeConfigFormat.YAML)
        // Plan 7.3: list keys produce both forms to maximize match rate.
        assertEquals("1", map["tabs.0.id"]?.value)
        assertEquals("1", map["tabs[0].id"]?.value)
        assertEquals("2", map["tabs.1.id"]?.value)
        assertEquals("2", map["tabs[1].id"]?.value)
    }

    // ---- json ----

    @Test
    fun `json top level key value`() {
        val map = keys("{\n  \"timeout\": 5000,\n  \"name\": \"svc\"\n}\n", RuntimeConfigFormat.JSON)
        assertEquals("5000", map["timeout"]?.value)
        assertEquals("svc", map["name"]?.value)
    }

    @Test
    fun `json unquotes string values`() {
        val map = keys("{\n  \"k\": \"v\"\n}\n", RuntimeConfigFormat.JSON)
        assertEquals("v", map["k"]?.value)
    }

    @Test
    fun `json nested object flattens`() {
        val content = "{\n  \"sys\": {\n    \"audit\": {\n      \"switch\": true\n    }\n  }\n}\n"
        val map = keys(content, RuntimeConfigFormat.JSON)
        assertEquals("true", map["sys.audit.switch"]?.value)
    }

    @Test
    fun `json parses minified single-line object`() {
        // Default JSON.stringify output: one line, no whitespace.
        val map = keys("""{"timeout":5000,"name":"svc"}""", RuntimeConfigFormat.JSON)
        assertEquals("5000", map["timeout"]?.value)
        assertEquals("svc", map["name"]?.value)
    }

    @Test
    fun `json parses minified nested object`() {
        val map = keys("""{"sys":{"audit":{"switch":true}}}""", RuntimeConfigFormat.JSON)
        assertEquals("true", map["sys.audit.switch"]?.value)
    }

    @Test
    fun `json keeps keys declared after an array whose last element is a string`() {
        // The hand-written scanner mis-tracked the closing bracket once a string
        // ended an array and swallowed the whole remainder of the document.
        val map = keys("""{"a":["x"],"b":1}""", RuntimeConfigFormat.JSON)
        assertEquals("1", map["b"]?.value)
    }

    @Test
    fun `json array of objects contributes nested keys in both index forms`() {
        val map = keys("""{"list":[{"n":"first"},{"n":"second"}]}""", RuntimeConfigFormat.JSON)
        assertEquals("first", map["list[0].n"]?.value)
        assertEquals("first", map["list.0.n"]?.value)
        assertEquals("second", map["list[1].n"]?.value)
        assertEquals("second", map["list.1.n"]?.value)
    }

    @Test
    fun `json scalar array elements are addressable by index`() {
        val map = keys("""{"hosts":["a","b"]}""", RuntimeConfigFormat.JSON)
        assertEquals("a", map["hosts[0]"]?.value)
        assertEquals("a", map["hosts.0"]?.value)
        assertEquals("b", map["hosts[1]"]?.value)
        assertEquals("b", map["hosts.1"]?.value)
    }

    @Test
    fun `json decodes escapes in values`() {
        val map = keys("""{"name":"a\/b\tc"}""", RuntimeConfigFormat.JSON)
        assertEquals("a/b\tc", map["name"]?.value)
    }

    @Test
    fun `json decodes escapes in keys`() {
        // The document spells the key with a unicode escape. The key space must
        // hold the decoded `name`, which is what a `${name}` placeholder
        // resolves against.
        val map = keys("{\"na\\u006de\":\"v\"}", RuntimeConfigFormat.JSON)
        assertEquals("v", map["name"]?.value)
    }

    @Test
    fun `json reports the line the key is declared on not where its value ends`() {
        val content = "{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": 3\n}\n"
        val map = keys(content, RuntimeConfigFormat.JSON)
        assertEquals(1, map["a[0]"]?.lineIndex)
        assertEquals(1, map["a[1]"]?.lineIndex)
        assertEquals(5, map["b"]?.lineIndex)
    }

    @Test
    fun `json reports the not-found sentinel when the declaration line cannot be located`() {
        // The key set is authoritative and comes from the parser; the line comes
        // from a permissive locator, which cannot see through an escaped key.
        // A line it cannot find stays the not-found sentinel every consumer
        // already handles — it never becomes a wrong line.
        val map = keys("{\"na\\u006de\":\"v\"}", RuntimeConfigFormat.JSON)
        val location = map["name"]
        assertNotNull(location)
        assertEquals("v", location?.value)
        assertEquals(-1, location?.lineIndex)
    }

    @Test
    fun `json does not blame a later key for an escaped key it cannot read`() {
        // The locator must not sail past the escaped spelling and claim the
        // line of the next key that happens to share the decoded name. It
        // reports the sentinel for the one it cannot read and stays aligned
        // for everything after it.
        val content = "{\n  \"na\\u006de\": 1,\n  \"x\": {\"name\": 2}\n}"
        val map = keys(content, RuntimeConfigFormat.JSON)
        assertEquals("1", map["name"]?.value)
        assertEquals(-1, map["name"]?.lineIndex)
        assertEquals("2", map["x.name"]?.value)
        assertEquals(2, map["x.name"]?.lineIndex)
    }

    @Test
    fun `json does not let a string value claim a later key's line`() {
        val content = "{\n  \"a\": \"b\",\n  \"b\": 1\n}"
        val map = keys(content, RuntimeConfigFormat.JSON)
        assertEquals(1, map["a"]?.lineIndex)
        assertEquals(2, map["b"]?.lineIndex)
    }

    @Test
    fun `json locates keys past a value containing quotes and colons`() {
        val content = "{\n  \"a\": \"he said \\\"b\\\": no\",\n  \"b\": 1\n}"
        val map = keys(content, RuntimeConfigFormat.JSON)
        assertEquals(1, map["a"]?.lineIndex)
        assertEquals(2, map["b"]?.lineIndex)
    }

    @Test
    fun `json containers contribute no key of their own`() {
        // A runtime property source flattens to leaves, so `${a}` and `${b}`
        // resolve against nothing at runtime and must not appear here either.
        val map = keys("""{"a":[],"b":{},"c":1}""", RuntimeConfigFormat.JSON)
        assertNull(map["a"])
        assertNull(map["b"])
        assertEquals("1", map["c"]?.value)
    }

    @Test
    fun `json deep nesting inside arrays flattens`() {
        val map = keys("""{"a":{"b":[{"c":{"d":1}}]}}""", RuntimeConfigFormat.JSON)
        assertEquals("1", map["a.b[0].c.d"]?.value)
        assertEquals("1", map["a.b.0.c.d"]?.value)
    }

    @Test
    fun `json keeps the keys it read before a syntax error`() {
        val map = keys("""{"a":1,"b":}""", RuntimeConfigFormat.JSON)
        assertEquals("1", map["a"]?.value)
    }

    @Test
    fun `json empty object yields no keys`() {
        assertTrue(keys("{}", RuntimeConfigFormat.JSON).isEmpty())
    }

    // ---- closed result ----

    @Test
    fun `a known non-parsing format refuses rather than reporting no keys`() {
        val refusal = notExtractable("k=v", RuntimeConfigFormat.TEXT)
        assertEquals(KeyExtraction.Reason.KNOWN_NON_PARSING_FORMAT, refusal.reason)
    }

    @Test
    fun `html and toml refuse for the same reason as text`() {
        assertEquals(
            KeyExtraction.Reason.KNOWN_NON_PARSING_FORMAT,
            notExtractable("k=v", RuntimeConfigFormat.HTML).reason
        )
        assertEquals(
            KeyExtraction.Reason.KNOWN_NON_PARSING_FORMAT,
            notExtractable("k=v", RuntimeConfigFormat.TOML).reason
        )
    }

    @Test
    fun `an undetermined format refuses with its own reason`() {
        val refusal = notExtractable("k=v", RuntimeConfigFormat.UNDETERMINED)
        assertEquals(KeyExtraction.Reason.FORMAT_UNDETERMINED, refusal.reason)
    }

    @Test
    fun `xml refuses because no parser exists yet`() {
        val refusal = notExtractable("<a><b>1</b></a>", RuntimeConfigFormat.XML)
        assertEquals(KeyExtraction.Reason.PARSER_NOT_IMPLEMENTED, refusal.reason)
    }

    @Test
    fun `a refusal is decided by the format alone and does not depend on content`() {
        assertEquals(
            notExtractable("", RuntimeConfigFormat.TEXT).reason,
            notExtractable("anything at all", RuntimeConfigFormat.TEXT).reason
        )
    }

    @Test
    fun `empty content parses to no keys`() {
        assertTrue(keys("", RuntimeConfigFormat.PROPERTIES).isEmpty())
        assertTrue(keys("   \n\t\n", RuntimeConfigFormat.JSON).isEmpty())
    }

    @Test
    fun `null content parses to no keys`() {
        val result = ConfigKeyExtractor.extract(null, RuntimeConfigFormat.PROPERTIES)
        assertEquals(KeyExtraction.Extracted(emptyMap()), result)
    }

    // ---- format naming ----
    //
    // The format is still derived by the existing accessor in this slice; only
    // the mapping from its type name onto the closed set lives here.

    @Test
    fun `type names map onto the closed format set`() {
        assertEquals(RuntimeConfigFormat.PROPERTIES, RuntimeConfigFormat.ofTypeName("properties"))
        assertEquals(RuntimeConfigFormat.YAML, RuntimeConfigFormat.ofTypeName("yaml"))
        assertEquals(RuntimeConfigFormat.YAML, RuntimeConfigFormat.ofTypeName("yml"))
        assertEquals(RuntimeConfigFormat.JSON, RuntimeConfigFormat.ofTypeName("json"))
        assertEquals(RuntimeConfigFormat.XML, RuntimeConfigFormat.ofTypeName("xml"))
        assertEquals(RuntimeConfigFormat.TEXT, RuntimeConfigFormat.ofTypeName("text"))
        assertEquals(RuntimeConfigFormat.HTML, RuntimeConfigFormat.ofTypeName("html"))
        assertEquals(RuntimeConfigFormat.TOML, RuntimeConfigFormat.ofTypeName("toml"))
    }

    @Test
    fun `an unrecognized or absent type name is undetermined`() {
        assertEquals(RuntimeConfigFormat.UNDETERMINED, RuntimeConfigFormat.ofTypeName(null))
        assertEquals(RuntimeConfigFormat.UNDETERMINED, RuntimeConfigFormat.ofTypeName(""))
        assertEquals(RuntimeConfigFormat.UNDETERMINED, RuntimeConfigFormat.ofTypeName("protobuf"))
    }

    @Test
    fun `type names are matched case-insensitively and trimmed`() {
        assertEquals(RuntimeConfigFormat.JSON, RuntimeConfigFormat.ofTypeName(" JSON "))
        assertEquals(RuntimeConfigFormat.YAML, RuntimeConfigFormat.ofTypeName("Yml"))
    }
}
