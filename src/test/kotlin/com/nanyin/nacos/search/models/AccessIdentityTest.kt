package com.nanyin.nacos.search.models

import com.nanyin.nacos.search.settings.AuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessIdentityTest {

    @Test
    fun `same environment auth mode and username yields equal identity`() {
        val a = AccessIdentity.of("http://nacos:8848", AuthMode.TOKEN, "admin")
        val b = AccessIdentity.of("http://nacos:8848/", AuthMode.TOKEN, "admin")
        assertEquals(a, b)
    }

    @Test
    fun `different server yields different identity`() {
        val a = AccessIdentity.of("http://one:8848", AuthMode.TOKEN, "admin")
        val b = AccessIdentity.of("http://two:8848", AuthMode.TOKEN, "admin")
        assertFalse(a == b)
    }

    @Test
    fun `different auth mode yields different identity`() {
        val a = AccessIdentity.of("http://nacos:8848", AuthMode.TOKEN, "admin")
        val b = AccessIdentity.of("http://nacos:8848", AuthMode.BASIC, "admin")
        assertFalse(a == b)
    }

    @Test
    fun `different username yields different identity`() {
        val a = AccessIdentity.of("http://nacos:8848", AuthMode.TOKEN, "admin")
        val b = AccessIdentity.of("http://nacos:8848", AuthMode.TOKEN, "user")
        assertFalse(a == b)
    }

    @Test
    fun `blank username maps to anonymous`() {
        val a = AccessIdentity.of("http://nacos:8848", AuthMode.BASIC, "")
        assertEquals("<anonymous>", a.principal)
    }

    @Test
    fun `blank username and non-blank username are different identities`() {
        val anon = AccessIdentity.of("http://nacos:8848", AuthMode.BASIC, "")
        val named = AccessIdentity.of("http://nacos:8848", AuthMode.BASIC, "admin")
        assertFalse(anon == named)
    }

    @Test
    fun `blank server url maps to default`() {
        val id = AccessIdentity.of("", AuthMode.TOKEN, "admin")
        assertEquals("<default>", id.serverId)
    }
}
