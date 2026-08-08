package com.nanyin.nacos.search.psi

import com.nanyin.nacos.search.models.NacosConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The transitional caller behaviour: the format still comes from the
 * configuration's declared type, and a refusal still reads as "no keys".
 * Both are replaced later (#166); this pins what must not move meanwhile.
 */
class DeclaredTypeKeyExtractionTest {

    private fun config(dataId: String, content: String, type: String?) =
        NacosConfiguration(dataId = dataId, group = "g", tenantId = null, content = content, type = type)

    @Test
    fun `the declared type decides the format`() {
        val keys = keysUnderDeclaredType(config("whatever", """{"k":"v"}""", "json"))
        assertEquals("v", keys["k"]?.value)
    }

    @Test
    fun `the data id suffix still decides when no type is declared`() {
        val keys = keysUnderDeclaredType(config("app.properties", "k=v", null))
        assertEquals("v", keys["k"]?.value)
    }

    @Test
    fun `a refusal reads as no keys for now`() {
        assertTrue(keysUnderDeclaredType(config("notes.txt", "k=v", "text")).isEmpty())
        assertTrue(keysUnderDeclaredType(config("beans.xml", "<a>1</a>", "xml")).isEmpty())
    }

    @Test
    fun `a data id with no recognizable suffix and no type contributes no keys`() {
        // getConfigType() answers "text" here, so this arrives as a known
        // non-parsing format rather than an undetermined one. The 运行时格式
        // decision table separates the two later; the observable result — no
        // keys — is the same either way.
        assertTrue(keysUnderDeclaredType(config("service-config", "k=v", null)).isEmpty())
    }
}
