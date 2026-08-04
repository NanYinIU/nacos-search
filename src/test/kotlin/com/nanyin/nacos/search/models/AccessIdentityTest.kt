package com.nanyin.nacos.search.models

import com.nanyin.nacos.search.settings.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccessIdentityTest {

    @Test
    fun `same profile fields yield equal identity`() {
        val a = testIdentity("http://nacos:8848", "admin", AuthMode.TOKEN)
        val b = testIdentity("http://nacos:8848/", "admin", AuthMode.TOKEN)
        assertEquals(a, b)
    }

    @Test
    fun `different endpoint yields different identity`() {
        val a = testIdentity("http://one:8848", "admin", AuthMode.TOKEN)
        val b = testIdentity("http://two:8848", "admin", AuthMode.TOKEN)
        assertFalse(a == b)
    }

    @Test
    fun `different auth mode yields different identity`() {
        val a = testIdentity("http://nacos:8848", "admin", AuthMode.TOKEN)
        val b = testIdentity("http://nacos:8848", "admin", AuthMode.BASIC)
        assertFalse(a == b)
    }

    @Test
    fun `different username yields different identity`() {
        val a = testIdentity("http://nacos:8848", "admin", AuthMode.TOKEN)
        val b = testIdentity("http://nacos:8848", "user", AuthMode.TOKEN)
        assertFalse(a == b)
    }

    @Test
    fun `blank username maps to anonymous`() {
        val a = testIdentity("http://nacos:8848", "", AuthMode.BASIC)
        assertEquals("<anonymous>", a.principal)
    }

    @Test
    fun `blank username and non-blank username are different identities`() {
        val anon = testIdentity("http://nacos:8848", "", AuthMode.BASIC)
        val named = testIdentity("http://nacos:8848", "admin", AuthMode.BASIC)
        assertFalse(anon == named)
    }

    @Test
    fun `blank endpoint maps to invalid`() {
        val id = testIdentity("", "admin", AuthMode.TOKEN)
        assertEquals("<invalid>", id.canonicalEndpoint)
    }

    @Test
    fun `ofProfile carries real access revision and generation`() {
        val id = AccessIdentity.ofProfile(
            profileId = "prof-1",
            accessRevision = 7,
            canonicalEndpoint = "http://nacos:8848",
            resolvedGeneration = NacosApiGeneration.V3,
            authMode = AuthMode.TOKEN,
            principal = "admin"
        )
        assertEquals(7L, id.accessRevision)
        assertEquals(NacosApiGeneration.V3, id.resolvedGeneration)
        assertEquals("prof-1", id.profileId)
    }

    @Test
    fun `different access revision yields different identity`() {
        val a = testIdentity(accessRevision = 1)
        val b = testIdentity(accessRevision = 2)
        assertFalse(a == b)
    }

    @Test
    fun `different resolved generation yields different identity`() {
        val a = testIdentity(generation = NacosApiGeneration.V1)
        val b = testIdentity(generation = NacosApiGeneration.V3)
        assertFalse(a == b)
    }
}
