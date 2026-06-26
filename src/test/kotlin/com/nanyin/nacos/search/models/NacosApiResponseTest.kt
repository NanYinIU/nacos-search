package com.nanyin.nacos.search.models

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class NacosApiResponseTest {

    @Test
    fun `test NacosApiResponse creation`() {
        val response = NacosApiResponse(
            code = 0,
            message = "success",
            data = listOf("item1", "item2")
        )

        assertEquals(0, response.code)
        assertEquals("success", response.message)
        assertEquals(listOf("item1", "item2"), response.data)
    }

    @Test
    fun `test isSuccess with code zero`() {
        val response = NacosApiResponse(
            code = 0,
            message = "success",
            data = null
        )

        assertTrue(response.isSuccess())
    }

    @Test
    fun `test isSuccess with non zero code`() {
        val response = NacosApiResponse(
            code = 500,
            message = "error",
            data = null
        )

        assertFalse(response.isSuccess())
    }

    @Test
    fun `test getErrorMessage when success`() {
        val response = NacosApiResponse(
            code = 0,
            message = "success",
            data = null
        )

        assertEquals("", response.getErrorMessage())
    }

    @Test
    fun `test getErrorMessage when failed`() {
        val response = NacosApiResponse(
            code = 404,
            message = "Not found",
            data = null
        )

        assertEquals("Error 404: Not found", response.getErrorMessage())
    }

    @Test
    fun `test NacosApiResponse with null data`() {
        val response = NacosApiResponse<String>(
            code = 0,
            message = "success",
            data = null
        )

        assertNull(response.data)
        assertTrue(response.isSuccess())
    }
}
