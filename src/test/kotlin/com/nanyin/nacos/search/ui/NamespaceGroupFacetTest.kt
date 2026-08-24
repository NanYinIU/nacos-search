package com.nanyin.nacos.search.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NamespaceGroupFacetTest {

    @Test
    fun `an empty hit page does not wipe groups already seen in the Namespace`() {
        val facet = NamespaceGroupFacet()
        facet.absorb("ns-a", listOf("DEFAULT_GROUP", "PROD_GROUP"))

        assertEquals(
            listOf("DEFAULT_GROUP", "PROD_GROUP"),
            facet.absorb("ns-a", emptyList())
        )
    }

    @Test
    fun `switching Namespace starts a new group set`() {
        val facet = NamespaceGroupFacet()
        facet.absorb("ns-a", listOf("DEFAULT_GROUP", "PROD_GROUP"))

        assertEquals(listOf("OTHER_GROUP"), facet.absorb("ns-b", listOf("OTHER_GROUP")))
    }
}
