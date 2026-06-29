package com.nanyin.nacos.search.psi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaceholderParserTest {

    @Test
    fun `simple placeholder without default`() {
        val p = PlaceholderParser.parse("\${app.name}")
        assertEquals("app.name", p?.key)
        assertNull(p?.default)
    }

    @Test
    fun `placeholder with default`() {
        val p = PlaceholderParser.parse("\${timeout:3000}")
        assertEquals("timeout", p?.key)
        assertEquals("3000", p?.default)
    }

    @Test
    fun `default may contain colons`() {
        val p = PlaceholderParser.parse("\${url:http://host:8080/path}")
        assertEquals("url", p?.key)
        assertEquals("http://host:8080/path", p?.default)
    }

    @Test
    fun `empty key returns null`() {
        assertNull(PlaceholderParser.parse("\${}"))
        assertNull(PlaceholderParser.parse("\${:x}"))
    }

    @Test
    fun `bare literal returns null`() {
        assertNull(PlaceholderParser.parse("123"))
        assertNull(PlaceholderParser.parse("plain text"))
    }

    @Test
    fun `null and blank return null`() {
        assertNull(PlaceholderParser.parse(null))
        assertNull(PlaceholderParser.parse(""))
        assertNull(PlaceholderParser.parse("   "))
    }

    @Test
    fun `placeholder embedded in mixed literal takes first`() {
        val p = PlaceholderParser.parse("prefix-\${db.host}-suffix")
        assertEquals("db.host", p?.key)
    }

    @Test
    fun `unclosed placeholder returns null`() {
        assertNull(PlaceholderParser.parse("\${oops"))
    }

    @Test
    fun `containsPlaceholder helper`() {
        assertTrue(PlaceholderParser.containsPlaceholder("\${x}"))
        assertFalse(PlaceholderParser.containsPlaceholder("nope"))
    }
}
