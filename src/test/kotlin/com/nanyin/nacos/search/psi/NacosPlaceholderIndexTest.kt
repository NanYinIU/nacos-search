package com.nanyin.nacos.search.psi

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class NacosPlaceholderIndexTest {

    @Test
    fun `marker serialization writes data and round trips`() {
        val externalizer = NacosPlaceholderIndex().valueExternalizer
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                externalizer.save(output, PlaceholderMarker)
            }
        }.toByteArray()

        assertTrue(bytes.isNotEmpty(), "empty index values are normalized to null by the platform")

        val restored = DataInputStream(ByteArrayInputStream(bytes)).use(externalizer::read)
        assertSame(PlaceholderMarker, restored)
    }

    @Test
    fun `index version invalidates the previous empty marker format`() {
        assertEquals(2, NacosPlaceholderIndex().version)
    }

    @Test
    fun `extracts key from Value annotation`() {
        val keys = Indexer.extractPlaceholderKeys(
            """@org.springframework.beans.factory.annotation.Value("${'$'}{app.timeout}") private String t;"""
        )
        assertTrue(keys.contains("app.timeout"))
    }

    @Test
    fun `extracts key from NacosValue annotation`() {
        val keys = Indexer.extractPlaceholderKeys(
            """@NacosValue(value = "${'$'}{rpc.timeout}") private String t;"""
        )
        assertTrue(keys.contains("rpc.timeout"))
    }

    @Test
    fun `extracts key stripping default value`() {
        val keys = Indexer.extractPlaceholderKeys(
            """@Value("${'$'}{db.host:localhost}") private String h;"""
        )
        assertTrue(keys.contains("db.host"))
        assertFalse(keys.contains("db.host:localhost"))
    }

    @Test
    fun `extracts multiple keys from same annotation block`() {
        val keys = Indexer.extractPlaceholderKeys(
            """@Value("${'$'}{a.b}") @NacosValue("${'$'}{c.d}") class C {}"""
        )
        assertTrue(keys.contains("a.b"))
        assertTrue(keys.contains("c.d"))
    }

    @Test
    fun `ignores placeholder outside annotation`() {
        val keys = Indexer.extractPlaceholderKeys(
            """String s = "${'$'}{not.in.annotation}"; """
        )
        assertFalse(keys.contains("not.in.annotation"))
    }

    @Test
    fun `empty content returns empty`() {
        assertTrue(Indexer.extractPlaceholderKeys("").isEmpty())
    }

    @Test
    fun `handles 500 keys across 1000 snippets under 200ms`() {
        val sb = StringBuilder()
        for (i in 1..1000) {
            for (j in 1..5) {
                sb.append("""@Value("${'$'}{key.$i.$j}") private String f;""")
            }
            sb.append(" class C$i {}\n")
        }
        val text = sb.toString()
        val start = System.nanoTime()
        val keys = Indexer.extractPlaceholderKeys(text)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        println("BENCH: extracted ${keys.size} keys in ${elapsed}ms")
        assertTrue(keys.size >= 5000, "expected >=5000 keys, got ${keys.size}")
        assertTrue(elapsed < 200, "extraction took ${elapsed}ms, expected < 200ms")
    }
}
