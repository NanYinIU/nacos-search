package com.nanyin.nacos.search.models

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NamespaceFilterTest {

    private val public = NamespaceInfo("", "public", "Public namespace")
    private val dev = NamespaceInfo("dev-uuid", "Development", "dev env", configCount = 12)
    private val prod = NamespaceInfo("prod-uuid-1234", "Production", "prod env")
    private val payment = NamespaceInfo("pay-ns-9", "payment-svc", "payment service")
    private val all = listOf(dev, public, payment, prod)

    @Test
    fun `empty query matches everything`() {
        assertEquals(4, NamespaceFilter.filter(all, "").size)
        assertEquals(4, NamespaceFilter.filter(all, "   ").size)
    }

    @Test
    fun `matches against name id and description`() {
        // public is always retained and pinned first, so each non-public match
        // is accompanied by the public namespace at the head of the result.
        assertEquals(listOf(public, payment), NamespaceFilter.filter(all, "payment-svc"))
        assertEquals(listOf(public, prod), NamespaceFilter.filter(all, "prod-uuid"))
        assertEquals(listOf(public, dev), NamespaceFilter.filter(all, "dev env"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(listOf(public, payment), NamespaceFilter.filter(all, "PAYMENT"))
        assertEquals(listOf(public, dev), NamespaceFilter.filter(all, "DEVELOPMENT"))
    }

    @Test
    fun `public namespace is always a match`() {
        val result = NamespaceFilter.filter(all, "payment")
        // payment matches by name; public still appears even though it does not match "payment"
        assertEquals(2, result.size)
        assertEquals(public, result.first())
        assertEquals(payment, result.last())
    }

    @Test
    fun `public namespace is pinned to the top regardless of query`() {
        val topDev = NamespaceFilter.filter(all, "dev")
        assertEquals(public, topDev.first())
        // filtered list also keeps public first when query is empty
        assertEquals(public, NamespaceFilter.filter(all, "").first())
    }

    @Test
    fun `no match returns only public`() {
        val result = NamespaceFilter.filter(all, "zzz-nope")
        assertEquals(1, result.size)
        assertEquals(public, result.first())
    }

    @Test
    fun `matches predicate mirrors filter inclusion`() {
        assertTrue(NamespaceFilter.matches(dev, "dev"))
        assertTrue(NamespaceFilter.matches(public, "anything-still-matches"))
        assertFalse(NamespaceFilter.matches(prod, "payment"))
    }
}
