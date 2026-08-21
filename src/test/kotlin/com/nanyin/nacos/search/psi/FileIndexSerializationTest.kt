package com.nanyin.nacos.search.psi

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.KeyDescriptor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

@TestApplication
class FileIndexSerializationTest {

    @Test
    fun `placeholder-key and declared-source indexes keep distinct names versions and key spaces`() {
        assertEquals("nacos.placeholder.keys", NacosPlaceholderIndex.INDEX_ID.name)
        assertEquals("nacos.declared.sources", NacosDeclaredSourceIndex.INDEX_ID.name)
        assertNotEquals(NacosPlaceholderIndex.INDEX_ID, NacosDeclaredSourceIndex.INDEX_ID)
        assertEquals(2, NacosPlaceholderIndex().version)
        assertEquals(1, NacosDeclaredSourceIndex().version)
    }

    @Test
    fun `string key descriptors preserve equality hash-code and round-trip`() {
        val placeholder = NacosPlaceholderIndex().keyDescriptor
        val declared = NacosDeclaredSourceIndex().keyDescriptor
        val key = "app.config.timeout"

        assertSame(placeholder, declared)
        assertTrue(placeholder.isEqual(key, key))
        assertFalse(placeholder.isEqual(key, "other.key"))
        assertEquals(key.hashCode(), placeholder.getHashCode(key))

        val placeholderBytes = writeKey(placeholder, key)
        val declaredBytes = writeKey(declared, key)
        assertArrayEquals(placeholderBytes, declaredBytes)
        assertEquals(key, readKey(placeholder, placeholderBytes))
        assertEquals(key, readKey(declared, declaredBytes))
    }

    @Test
    fun `marker externalizers write the same non-empty payload and round-trip`() {
        val placeholderBytes = writeMarker(
            NacosPlaceholderIndex().valueExternalizer,
            PlaceholderMarker
        )
        val declaredBytes = writeMarker(
            NacosDeclaredSourceIndex().valueExternalizer,
            DeclaredSourceMarker
        )

        assertTrue(placeholderBytes.isNotEmpty())
        assertArrayEquals(placeholderBytes, declaredBytes)
        assertEquals(PlaceholderMarker, readMarker(NacosPlaceholderIndex().valueExternalizer, placeholderBytes))
        assertEquals(DeclaredSourceMarker, readMarker(NacosDeclaredSourceIndex().valueExternalizer, declaredBytes))
    }

    private fun writeKey(descriptor: KeyDescriptor<String>, value: String): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output -> descriptor.save(output, value) }
        }.toByteArray()

    private fun readKey(descriptor: KeyDescriptor<String>, bytes: ByteArray): String =
        DataInputStream(ByteArrayInputStream(bytes)).use(descriptor::read)

    private fun <T : Any> writeMarker(externalizer: DataExternalizer<T>, value: T): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output -> externalizer.save(output, value) }
        }.toByteArray()

    private fun <T : Any> readMarker(externalizer: DataExternalizer<T>, bytes: ByteArray): T =
        DataInputStream(ByteArrayInputStream(bytes)).use(externalizer::read)
}
