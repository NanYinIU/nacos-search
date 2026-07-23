package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.NacosServerConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsBlueDotTest {

    private val local = NacosServerConfig(id = "s_local", displayName = "本地 Local", serverUrl = "http://localhost:8848")
    private val qa = NacosServerConfig(id = "s_qa", displayName = "QA", serverUrl = "http://47.95.169.10:8848")
    private val servers = listOf(local, qa)

    @Test
    fun `prefers project tool-window selection over stale app-wide active`() {
        assertEquals(
            "s_qa",
            resolveSettingsBlueDotId(
                servers = servers,
                projectProfileId = "s_qa",
                activeServerId = "s_local"
            )
        )
    }

    @Test
    fun `falls back to app-wide active when project selection is missing`() {
        assertEquals(
            "s_local",
            resolveSettingsBlueDotId(
                servers = servers,
                projectProfileId = null,
                activeServerId = "s_local"
            )
        )
    }

    @Test
    fun `ignores project selection that is no longer in the server list`() {
        assertEquals(
            "s_local",
            resolveSettingsBlueDotId(
                servers = servers,
                projectProfileId = "ghost",
                activeServerId = "s_local"
            )
        )
    }

    @Test
    fun `falls back to first server when nothing matches`() {
        assertEquals(
            "s_local",
            resolveSettingsBlueDotId(
                servers = servers,
                projectProfileId = "ghost",
                activeServerId = "also-gone"
            )
        )
    }
}
