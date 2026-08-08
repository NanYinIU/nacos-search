package com.nanyin.nacos.search.psi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The 运行时格式 decision table, one case per row.
 *
 * No content fixtures: the table's whole job is to answer from the data id, and
 * a body in the arrangement would let a future row start consulting one without
 * a test noticing.
 */
class RuntimeFormatDecisionTest {

    // ---- row 1: a parseable suffix decides ----

    @Test
    fun `a parseable suffix decides the format`() {
        assertEquals(RuntimeConfigFormat.PROPERTIES, RuntimeFormatDecision.forDataId("app.properties"))
        assertEquals(RuntimeConfigFormat.YAML, RuntimeFormatDecision.forDataId("app.yaml"))
        assertEquals(RuntimeConfigFormat.YAML, RuntimeFormatDecision.forDataId("app.yml"))
        assertEquals(RuntimeConfigFormat.JSON, RuntimeFormatDecision.forDataId("app.json"))
        assertEquals(RuntimeConfigFormat.XML, RuntimeFormatDecision.forDataId("beans.xml"))
    }

    @Test
    fun `only the last dot segment is the suffix`() {
        assertEquals(RuntimeConfigFormat.PROPERTIES, RuntimeFormatDecision.forDataId("klive.common.properties"))
    }

    // ---- row 2: a known non-parsing suffix ----

    @Test
    fun `a known non-parsing suffix names the format that parses nothing`() {
        assertEquals(RuntimeConfigFormat.TEXT, RuntimeFormatDecision.forDataId("notes.txt"))
        assertEquals(RuntimeConfigFormat.TEXT, RuntimeFormatDecision.forDataId("notes.text"))
        assertEquals(RuntimeConfigFormat.HTML, RuntimeFormatDecision.forDataId("page.html"))
        assertEquals(RuntimeConfigFormat.HTML, RuntimeFormatDecision.forDataId("page.htm"))
        assertEquals(RuntimeConfigFormat.TOML, RuntimeFormatDecision.forDataId("app.toml"))
    }

    // ---- row 3: no recognizable suffix ----

    @Test
    fun `a data id with no suffix at all is undetermined`() {
        assertEquals(RuntimeConfigFormat.UNDETERMINED, RuntimeFormatDecision.forDataId("service-config"))
    }

    @Test
    fun `a suffix no runtime recognizes is undetermined`() {
        assertEquals(RuntimeConfigFormat.UNDETERMINED, RuntimeFormatDecision.forDataId("app.conf"))
        assertEquals(RuntimeConfigFormat.UNDETERMINED, RuntimeFormatDecision.forDataId("com.example.service"))
    }

    @Test
    fun `an empty or trailing-dot data id is undetermined`() {
        assertEquals(RuntimeConfigFormat.UNDETERMINED, RuntimeFormatDecision.forDataId(""))
        assertEquals(RuntimeConfigFormat.UNDETERMINED, RuntimeFormatDecision.forDataId("app."))
    }

    @Test
    fun `the suffix is matched however the user cased it`() {
        // Both runtimes fold case before matching — Spring Cloud Alibaba with
        // `endsWithIgnoreCase`, `nacos-spring-context` by upper-casing the
        // suffix — so refusing these would refuse a body they both parse.
        assertEquals(RuntimeConfigFormat.YAML, RuntimeFormatDecision.forDataId("App.YAML"))
        assertEquals(RuntimeConfigFormat.PROPERTIES, RuntimeFormatDecision.forDataId("App.Properties"))
        assertEquals(RuntimeConfigFormat.TEXT, RuntimeFormatDecision.forDataId("NOTES.TXT"))
    }
}
