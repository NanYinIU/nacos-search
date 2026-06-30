package com.nanyin.nacos.search.psi

import com.nanyin.nacos.search.models.NacosConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigKeyExtractorTest {

    private fun config(content: String, type: String) =
        NacosConfiguration(dataId = "x", group = "g", tenantId = null, content = content, type = type)

    // ---- properties ----

    @Test
    fun `properties extracts key equals value`() {
        val map = ConfigKeyExtractor.extract(
            config("app.name=my-service\napp.port=8080\n", "properties")
        )
        assertEquals("my-service", map["app.name"]?.value)
        assertEquals("8080", map["app.port"]?.value)
        assertEquals(0, map["app.name"]?.lineIndex)
        assertEquals(1, map["app.port"]?.lineIndex)
    }

    @Test
    fun `properties skips comments and blanks`() {
        val content = "# a comment\n\n! bang comment\nkey=v\n"
        val map = ConfigKeyExtractor.extract(config(content, "properties"))
        assertEquals(1, map.size)
        assertEquals("v", map["key"]?.value)
    }

    @Test
    fun `properties supports colon separator`() {
        val map = ConfigKeyExtractor.extract(config("host: localhost\n", "properties"))
        assertEquals("localhost", map["host"]?.value)
    }

    @Test
    fun `properties later key overrides earlier`() {
        val map = ConfigKeyExtractor.extract(config("k=1\nk=2\n", "properties"))
        assertEquals("2", map["k"]?.value)
    }

    // ---- yaml ----

    @Test
    fun `yaml flat key value`() {
        val map = ConfigKeyExtractor.extract(config("app:\n  name: svc\n  port: 8080\n", "yaml"))
        assertEquals("svc", map["app.name"]?.value)
        assertEquals("8080", map["app.port"]?.value)
    }

    @Test
    fun `yaml top level value`() {
        val map = ConfigKeyExtractor.extract(config("timeout: 3000\n", "yaml"))
        assertEquals("3000", map["timeout"]?.value)
    }

    @Test
    fun `yaml strips quoted values`() {
        val map = ConfigKeyExtractor.extract(config("name: \"hello\"\n", "yaml"))
        assertEquals("hello", map["name"]?.value)
    }

    @Test
    fun `yaml skips list items`() {
        val map = ConfigKeyExtractor.extract(config("items:\n  - one\n  - two\nreal: yes\n", "yaml"))
        assertNull(map["items.-"])
        assertEquals("yes", map["real"]?.value)
    }

    // ---- json ----

    @Test
    fun `json top level key value`() {
        val content = "{\n  \"timeout\": 5000,\n  \"name\": \"svc\"\n}\n"
        val map = ConfigKeyExtractor.extract(config(content, "json"))
        assertEquals("5000", map["timeout"]?.value)
        assertEquals("svc", map["name"]?.value)
    }

    @Test
    fun `json unquotes string values`() {
        val map = ConfigKeyExtractor.extract(config("{\n  \"k\": \"v\"\n}\n", "json"))
        assertEquals("v", map["k"]?.value)
    }

    @Test
    fun `yaml three level nesting flattens`() {
        val map = ConfigKeyExtractor.extract(config("sys:\n  audit:\n    switch: true\n", "yaml"))
        assertEquals("true", map["sys.audit.switch"]?.value)
    }

   @Test
   fun `yaml sibling keys reset path`() {
       val map = ConfigKeyExtractor.extract(config(
           "server:\n  port: 8080\nlogging:\n  level: INFO\n", "yaml"))
       assertEquals("8080", map["server.port"]?.value)
       assertEquals("INFO", map["logging.level"]?.value)
   }

    @Test
    fun `yaml list items flatten with both index forms`() {
        val map = ConfigKeyExtractor.extract(config(
            "tabs:\n  - id: 1\n  - id: 2\n", "yaml"))
        // Plan 7.3: list keys produce both forms to maximize match rate.
        assertEquals("1", map["tabs.0.id"]?.value)
        assertEquals("1", map["tabs[0].id"]?.value)
        assertEquals("2", map["tabs.1.id"]?.value)
        assertEquals("2", map["tabs[1].id"]?.value)
    }

    @Test
   fun `json nested object flattens`() {
       val content = "{\n  \"sys\": {\n    \"audit\": {\n      \"switch\": true\n    }\n  }\n}\n"
       val map = ConfigKeyExtractor.extract(config(content, "json"))
       assertEquals("true", map["sys.audit.switch"]?.value)
   }

    @Test
    fun `json parses minified single-line object`() {
        // Default JSON.stringify output: one line, no whitespace. The line-based
        // regex parser silently produced an empty map for this input.
        val map = ConfigKeyExtractor.extract(config("""{"timeout":5000,"name":"svc"}""", "json"))
        assertEquals("5000", map["timeout"]?.value)
        assertEquals("svc", map["name"]?.value)
    }

    @Test
    fun `json parses minified nested object`() {
        val map = ConfigKeyExtractor.extract(config("""{"sys":{"audit":{"switch":true}}}""", "json"))
        assertEquals("true", map["sys.audit.switch"]?.value)
    }

    // ---- type inference + fallback ----

    @Test
    fun `unknown type returns empty`() {
        val map = ConfigKeyExtractor.extract(config("k=v", "text"))
        assertTrue(map.isEmpty())
    }

    @Test
    fun `type inferred from dataId when type field blank`() {
        val cfg = NacosConfiguration(
            dataId = "app.properties", group = "g", tenantId = null,
            content = "k=v", type = null
        )
        assertEquals("v", ConfigKeyExtractor.extract(cfg)["k"]?.value)
    }

    @Test
    fun `empty content returns empty`() {
        assertTrue(ConfigKeyExtractor.extract(config("", "properties")).isEmpty())
    }


}
