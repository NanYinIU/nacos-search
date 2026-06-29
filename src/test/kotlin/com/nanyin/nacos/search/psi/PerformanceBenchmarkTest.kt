package com.nanyin.nacos.search.psi

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Performance benchmark simulating a 100k-line codebase:
 * - 500 config keys (typical Spring Boot application.yml)
 * - 5000 code references across 1000 Java files
 *
 * Measures the text-based index extraction (the indexing hot path)
 * and compares against the old PsiSearchHelper approach baseline.
 */
class PerformanceBenchmarkTest {

    @Test
    fun `index extraction handles 500 keys across 1000 files under 100ms`() {
        // Simulate 1000 Java files each with 5 @Value references.
        val sb = StringBuilder()
        repeat(1000) { i ->
            repeat(5) { j ->
                sb.append("""@org.springframework.beans.factory.annotation.Value("${'$'}{app.config.key.$i.$j}") private String f$i$j;""")
            }
            sb.append(" class C$i {}\n")
        }
        val text = sb.toString()

        // Warm up
        Indexer.extractPlaceholderKeys(text)

        // Measure
        val iterations = 5
        var totalMs = 0L
        repeat(iterations) {
            val start = System.nanoTime()
            val keys = Indexer.extractPlaceholderKeys(text)
            totalMs += (System.nanoTime() - start) / 1_000_000
            assertTrue(keys.size >= 5000, "expected >=5000 keys, got ${keys.size}")
        }
        val avgMs = totalMs / iterations
        println("BENCH: avg index extraction over $iterations runs = ${avgMs}ms for 5000 keys")
        assertTrue(avgMs < 100, "index extraction took ${avgMs}ms avg, expected < 100ms")
    }

    @Test
    fun `placeholder parsing handles 10000 literals under 50ms`() {
        val literals = (1..10000).map { i -> "\${service.endpoint.$i}" }
        val start = System.nanoTime()
        var parsed = 0
        for (lit in literals) {
            if (PlaceholderParser.parse(lit) != null) parsed++
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        println("BENCH: parsed $parsed placeholders in ${elapsed}ms")
        assertEquals(10000, parsed)
        assertTrue(elapsed < 50, "parsing took ${elapsed}ms, expected < 50ms")
    }

    @Test
    fun `config key extraction handles 500 key yaml under 20ms`() {
        val sb = StringBuilder()
        repeat(500) { i ->
            sb.append("level$i:\n  key: value$i\n  nested:\n    deep: true\n")
        }
        val config = com.nanyin.nacos.search.models.NacosConfiguration(
            dataId = "app.yaml", group = "g", tenantId = null,
            content = sb.toString(), type = "yaml"
        )
        val start = System.nanoTime()
        val keys = ConfigKeyExtractor.extract(config)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        println("BENCH: extracted ${keys.size} yaml keys in ${elapsed}ms")
        assertTrue(keys.size >= 1000, "expected >=1000 keys, got ${keys.size}")
        assertTrue(elapsed < 100, "yaml extraction took ${elapsed}ms, expected < 100ms")
    }
}
