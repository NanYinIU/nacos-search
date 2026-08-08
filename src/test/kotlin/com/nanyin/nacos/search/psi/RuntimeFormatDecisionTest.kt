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

    // ---- row 1: a reference-site declaration decides, above every suffix row ----

    @Test
    fun `a declared type decides the format`() {
        assertEquals(
            RuntimeConfigFormat.PROPERTIES,
            RuntimeFormatDecision.forReference("app", "PROPERTIES")
        )
        assertEquals(RuntimeConfigFormat.YAML, RuntimeFormatDecision.forReference("app", "YAML"))
        assertEquals(RuntimeConfigFormat.JSON, RuntimeFormatDecision.forReference("app", "JSON"))
        assertEquals(RuntimeConfigFormat.XML, RuntimeFormatDecision.forReference("app", "XML"))
    }

    @Test
    fun `a declared type outranks the suffix`() {
        assertEquals(
            RuntimeConfigFormat.JSON,
            RuntimeFormatDecision.forReference("app.yaml", "JSON")
        )
        assertEquals(
            RuntimeConfigFormat.YAML,
            RuntimeFormatDecision.forReference("app.properties", "YAML")
        )
    }

    @Test
    fun `a declared type rescues a data id with no recognizable suffix`() {
        // The whole reason a developer writes one: without it this data id is
        // undetermined and extracts nothing.
        assertEquals(
            RuntimeConfigFormat.UNDETERMINED,
            RuntimeFormatDecision.forDataId("service-config")
        )
        assertEquals(
            RuntimeConfigFormat.JSON,
            RuntimeFormatDecision.forReference("service-config", "JSON")
        )
    }

    @Test
    fun `a declared non-parsing type falls through rather than refusing`() {
        // Only the four formats a runtime parses are read off a declaration.
        // Honouring a declared TEXT would be a refusal nothing has shown the
        // runtime makes — `nacos-spring-context` registers no parser for it and
        // is understood to fall back to properties — and this table may not be
        // more conservative than the runtime outside its one recorded departure
        // (ADR-0055). Falling through can only widen what resolves.
        assertEquals(
            RuntimeConfigFormat.PROPERTIES,
            RuntimeFormatDecision.forReference("app.properties", "TEXT")
        )
        assertEquals(RuntimeConfigFormat.JSON, RuntimeFormatDecision.forReference("app.json", "HTML"))
        assertEquals(RuntimeConfigFormat.YAML, RuntimeFormatDecision.forReference("app.yaml", "TOML"))
        // Where the suffix says nothing either, the last row still governs.
        assertEquals(
            RuntimeConfigFormat.UNDETERMINED,
            RuntimeFormatDecision.forReference("service-config", "TEXT")
        )
    }

    @Test
    fun `an unset or absent declaration leaves the suffix rows in charge`() {
        // `ConfigType.UNSET` is the annotation attribute's own default, so it is
        // what an unwritten declaration reads as.
        assertEquals(RuntimeConfigFormat.YAML, RuntimeFormatDecision.forReference("app.yaml", "UNSET"))
        assertEquals(RuntimeConfigFormat.YAML, RuntimeFormatDecision.forReference("app.yaml", null))
        assertEquals(RuntimeConfigFormat.YAML, RuntimeFormatDecision.forReference("app.yaml", ""))
        assertEquals(
            RuntimeConfigFormat.UNDETERMINED,
            RuntimeFormatDecision.forReference("service-config", "UNSET")
        )
    }

    @Test
    fun `a declaration naming no ConfigType is read as no declaration`() {
        // PSI hands back whatever is written, and the plugin is not the Java
        // compiler. Falling through to the suffix is the harmless answer; taking
        // it as a refusal would cost navigation the suffix already earns.
        assertEquals(RuntimeConfigFormat.YAML, RuntimeFormatDecision.forReference("app.yaml", "INI"))
        assertEquals(
            RuntimeConfigFormat.UNDETERMINED,
            RuntimeFormatDecision.forReference("service-config", "INI")
        )
    }

    @Test
    fun `the declaration is matched however the enum constant is spelled`() {
        // The extractor reads the constant's own name (`JSON`), while
        // `ConfigType`'s value is the lower-cased word; both must land on the
        // same row.
        assertEquals(RuntimeConfigFormat.JSON, RuntimeFormatDecision.forReference("app", "json"))
        assertEquals(RuntimeConfigFormat.JSON, RuntimeFormatDecision.forReference("app", " Json "))
    }

    // ---- row 2: a parseable suffix decides ----

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

    // ---- row 3: a known non-parsing suffix ----

    @Test
    fun `a known non-parsing suffix names the format that parses nothing`() {
        assertEquals(RuntimeConfigFormat.TEXT, RuntimeFormatDecision.forDataId("notes.txt"))
        assertEquals(RuntimeConfigFormat.TEXT, RuntimeFormatDecision.forDataId("notes.text"))
        assertEquals(RuntimeConfigFormat.HTML, RuntimeFormatDecision.forDataId("page.html"))
        assertEquals(RuntimeConfigFormat.HTML, RuntimeFormatDecision.forDataId("page.htm"))
        assertEquals(RuntimeConfigFormat.TOML, RuntimeFormatDecision.forDataId("app.toml"))
    }

    // ---- row 4: no recognizable suffix ----

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
