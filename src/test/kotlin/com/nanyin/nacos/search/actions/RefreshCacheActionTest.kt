package com.nanyin.nacos.search.actions

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.TestApplication
import com.nanyin.nacos.search.settings.NacosProjectSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@TestApplication
class RefreshCacheActionTest {

    @Test
    fun `cache refresh target comes from the invoking project session`() {
        val firstProject = projectSelecting("profile-a", "team-a")
        val secondProject = projectSelecting("profile-b", "team-b")

        assertEquals(RefreshSelection("profile-a", "team-a"), refreshSelection(firstProject))
        assertEquals(RefreshSelection("profile-b", "team-b"), refreshSelection(secondProject))
        assertNull(refreshSelection(null))
    }

    private fun projectSelecting(profileId: String, namespaceId: String): Project {
        val session = NacosProjectSession().apply { select(profileId, namespaceId) }
        return mock<Project>().also { project ->
            whenever(project.getService(NacosProjectSession::class.java)).thenReturn(session)
        }
    }
}
