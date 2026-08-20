package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.ProfileIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsBlueDotTest {

    private val local = ProfileIntent(
        profileId = "s_local",
        displayName = "本地 Local",
        endpoint = "http://localhost:8848"
    )
    private val qa = ProfileIntent(
        profileId = "s_qa",
        displayName = "QA",
        endpoint = "http://47.95.169.10:8848"
    )
    private val intents = listOf(local, qa)

    @Test
    fun `prefers project tool-window selection over migration default`() {
        assertEquals(
            "s_qa",
            resolveSettingsBlueDotId(
                intents = intents,
                projectProfileId = "s_qa",
                migrationDefaultProfileId = "s_local"
            )
        )
    }

    @Test
    fun `falls back to migration default when project selection is missing`() {
        assertEquals(
            "s_local",
            resolveSettingsBlueDotId(
                intents = intents,
                projectProfileId = null,
                migrationDefaultProfileId = "s_local"
            )
        )
    }

    @Test
    fun `ignores project selection that is no longer in the server list`() {
        assertEquals(
            "s_local",
            resolveSettingsBlueDotId(
                intents = intents,
                projectProfileId = "ghost",
                migrationDefaultProfileId = "s_local"
            )
        )
    }

    @Test
    fun `falls back to first server when nothing matches`() {
        assertEquals(
            "s_local",
            resolveSettingsBlueDotId(
                intents = intents,
                projectProfileId = "ghost",
                migrationDefaultProfileId = "also-gone"
            )
        )
    }
}
