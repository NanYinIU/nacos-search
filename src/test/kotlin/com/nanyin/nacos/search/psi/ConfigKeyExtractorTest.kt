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
        // List keys produce both forms so either placeholder style resolves.
        assertEquals("1", map["tabs.0.id"]?.value)
        assertEquals("1", map["tabs[0].id"]?.value)
        assertEquals("2", map["tabs.1.id"]?.value)
        assertEquals("2", map["tabs[1].id"]?.value)
    }

    @Test
    fun `yaml scalar sequence elements are addressable by index`() {
        val map = keys("items:\n  - one\n  - two\nreal: yes\n", RuntimeConfigFormat.YAML)
        assertEquals("one", map["items[0]"]?.value)
        assertEquals("one", map["items.0"]?.value)
        assertEquals("two", map["items[1]"]?.value)
        assertEquals("two", map["items.1"]?.value)
        assertEquals("yes", map["real"]?.value)
    }

    @Test
    fun `yaml contributes the keys of every document in a multi-document stream`() {
        // The ordinary Spring profile idiom. The indentation scanner merged the
        // documents and kept only the last, so the first one's keys vanished.
        val content = "spring:\n  profiles: dev\ndb:\n  url: dev-url\n---\nspring:\n  profiles: prod\nextra: true\n"
        val map = keys(content, RuntimeConfigFormat.YAML)
        assertEquals("dev-url", map["db.url"]?.value)
        assertEquals("true", map["extra"]?.value)
        // A repeated key still resolves to its last occurrence in the stream.
        assertEquals("prod", map["spring.profiles"]?.value)
    }

    @Test
    fun `yaml block scalars contribute no keys from their body in any chomping form`() {
        for (indicator in listOf("|", "|-", "|+", ">", ">-", ">+")) {
            val map = keys(
                "script: $indicator\n  phantom: 1\n  other: 2\nafter: x\n",
                RuntimeConfigFormat.YAML
            )
            assertNull(map["phantom"], "$indicator leaked a key from the block body")
            assertNull(map["other"], "$indicator leaked a key from the block body")
            assertNull(map["script.phantom"], "$indicator leaked a key from the block body")
            assertNotNull(map["script"], "$indicator lost the block's own key")
            assertEquals("x", map["after"]?.value, "$indicator lost the key after the block")
        }
    }

    @Test
    fun `yaml sequence elements that each open a nested mapping keep their own index`() {
        // The indentation scanner closed the running index when an element
        // opened a mapping, so every element collapsed onto index zero and the
        // last one overwrote the rest.
        val map = keys(
            "items:\n  - meta:\n      id: 1\n  - meta:\n      id: 2\n",
            RuntimeConfigFormat.YAML
        )
        assertEquals("1", map["items[0].meta.id"]?.value)
        assertEquals("1", map["items.0.meta.id"]?.value)
        assertEquals("2", map["items[1].meta.id"]?.value)
        assertEquals("2", map["items.1.meta.id"]?.value)
    }

    @Test
    fun `yaml attributes a sibling of a nested mapping to its sequence element`() {
        val map = keys(
            "items:\n  - name: first\n    nested:\n      x: 1\n    other: last\n",
            RuntimeConfigFormat.YAML
        )
        assertEquals("first", map["items[0].name"]?.value)
        assertEquals("1", map["items[0].nested.x"]?.value)
        assertEquals("last", map["items[0].other"]?.value)
        assertNull(map["items[0].nested.other"])
    }

    @Test
    fun `yaml bracket form separates the index from the segment that follows it`() {
        // `items[0]name.first` is a shape Spring's relaxed binding can never match.
        val map = keys("items:\n  - name:\n      first: a\n", RuntimeConfigFormat.YAML)
        assertEquals("a", map["items[0].name.first"]?.value)
        assertNull(map["items[0]name.first"])
    }

    @Test
    fun `yaml flow style contributes its nested keys`() {
        val map = keys("server: {port: 8080, host: localhost}\nhosts: [a, b]\n", RuntimeConfigFormat.YAML)
        assertEquals("8080", map["server.port"]?.value)
        assertEquals("localhost", map["server.host"]?.value)
        assertEquals("a", map["hosts[0]"]?.value)
        assertEquals("a", map["hosts.0"]?.value)
        assertEquals("b", map["hosts[1]"]?.value)
        assertEquals("b", map["hosts.1"]?.value)
    }

    @Test
    fun `yaml extracts quoted keys and keys beginning with a digit`() {
        // A purely numeric or boolean-ish key (`0:`, `on:`) is deliberately not
        // asserted here: those are not string keys, and the shape the runtime
        // gives them is a fidelity question for #166's differential oracle, not
        // something to pin to a guess.
        val content = "\"spring.datasource.url\": jdbc:h2:mem\n'single': q\n2fa: enabled\n"
        val map = keys(content, RuntimeConfigFormat.YAML)
        assertEquals("jdbc:h2:mem", map["spring.datasource.url"]?.value)
        assertEquals("q", map["single"]?.value)
        assertEquals("enabled", map["2fa"]?.value)
    }

    @Test
    fun `yaml keeps an anchored mapping's children beneath it`() {
        // The scanner read `&defaults` as the value of `defaults`, so the
        // children below it were attributed to the document root instead.
        val map = keys(
            "defaults: &defaults\n  timeout: 30\nservice: *defaults\n",
            RuntimeConfigFormat.YAML
        )
        assertEquals("30", map["defaults.timeout"]?.value)
        assertNull(map["timeout"])
        assertEquals("30", map["service.timeout"]?.value)
    }

    @Test
    fun `yaml splices a merge key into the mapping that declares it`() {
        // `<<` is not a key path segment: the runtime's constructor merges the
        // referenced mapping into this one, and the mapping's own entries win.
        val content = "defaults: &d\n  timeout: 30\n  retries: 3\nservice:\n  <<: *d\n  timeout: 60\n"
        val map = keys(content, RuntimeConfigFormat.YAML)
        assertEquals("3", map["service.retries"]?.value)
        assertEquals("60", map["service.timeout"]?.value)
        assertNull(map["service.<<.timeout"])
    }

    @Test
    fun `yaml merge is shallow so an own container discards the merged one`() {
        // The merge must be resolved before the walk, not by letting a later
        // leaf overwrite an earlier one: `service.db` is replaced whole, so the
        // merged `url` underneath it is gone rather than surviving as a key no
        // runtime produces.
        val content = "defaults: &d\n  db:\n    url: from-defaults\nservice:\n  <<: *d\n  db:\n    port: 5432\n"
        val map = keys(content, RuntimeConfigFormat.YAML)
        assertEquals("5432", map["service.db.port"]?.value)
        assertNull(map["service.db.url"])
        // The source mapping still contributes its own keys under its own path.
        assertEquals("from-defaults", map["defaults.db.url"]?.value)
    }

    @Test
    fun `yaml merges a sequence of sources with the earliest winning`() {
        val content = "a: &a\n  k: from-a\nb: &b\n  k: from-b\n  only-b: 1\nc:\n  <<: [*a, *b]\n"
        val map = keys(content, RuntimeConfigFormat.YAML)
        assertEquals("from-a", map["c.k"]?.value)
        assertEquals("1", map["c.only-b"]?.value)
    }

    @Test
    fun `yaml inline comments do not appear in values`() {
        val map = keys("app:\n  name: svc # the service name\n  port: 8080\n", RuntimeConfigFormat.YAML)
        assertEquals("svc", map["app.name"]?.value)
        assertEquals("8080", map["app.port"]?.value)
    }

    @Test
    fun `yaml containers contribute no key of their own`() {
        val map = keys("app:\n  name: svc\nempty: {}\nlist: []\n", RuntimeConfigFormat.YAML)
        assertNull(map["app"])
        assertNull(map["empty"])
        assertNull(map["list"])
        assertEquals("svc", map["app.name"]?.value)
    }

    @Test
    fun `yaml keeps a key whose value is null`() {
        // The runtime's flattening emits the key with an empty value, so a
        // `${enabled}` placeholder resolves against it.
        val map = keys("enabled:\nafter: 1\n", RuntimeConfigFormat.YAML)
        assertEquals("", map["enabled"]?.value)
        assertEquals("1", map["after"]?.value)
    }

    @Test
    fun `yaml lines come from the parser's node marks`() {
        val content = "app:\n  name: svc\nscript: |\n  not: a key\nafter: 1\n"
        val map = keys(content, RuntimeConfigFormat.YAML)
        assertEquals(1, map["app.name"]?.lineIndex)
        assertEquals(2, map["script"]?.lineIndex)
        // A multi-line block scalar must not drag the next key's line with it.
        assertEquals(4, map["after"]?.lineIndex)
    }

    @Test
    fun `yaml sequence elements carry their own line`() {
        val map = keys("items:\n  - one\n  - two\n", RuntimeConfigFormat.YAML)
        assertEquals(1, map["items[0]"]?.lineIndex)
        assertEquals(2, map["items[1]"]?.lineIndex)
    }

    @Test
    fun `yaml keeps the documents it composed before a malformed one`() {
        // Composing builds a whole document before yielding it, so a syntax
        // error costs that document — but not the ones already read. Unlike the
        // JSON reader, it cannot keep a half-document.
        val map = keys("a: 1\n---\nb: [unterminated\n", RuntimeConfigFormat.YAML)
        assertEquals("1", map["a"]?.value)
        assertNull(map["b[0]"])
    }

    @Test
    fun `yaml stops the stream at a malformed document`() {
        // The other direction, so the limit is pinned rather than assumed: the
        // error ends the read, so a document after the malformed one is never
        // composed.
        val map = keys("a: [unterminated\n---\nb: 1\n", RuntimeConfigFormat.YAML)
        assertTrue(map.isEmpty(), "expected no keys, got ${map.keys}")
    }

    @Test
    fun `yaml terminates on a recursive anchor`() {
        // A self-referencing anchor composes a cyclic node graph; walking it
        // must stop rather than overflow the stack.
        val map = keys("root: &r\n  child: *r\n  leaf: 1\n", RuntimeConfigFormat.YAML)
        assertEquals("1", map["root.leaf"]?.value)
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
