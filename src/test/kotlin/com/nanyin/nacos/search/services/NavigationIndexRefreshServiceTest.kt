package com.nanyin.nacos.search.services

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Coalesced daemon restarts for `@NacosValue` gutters (issue #193).
 *
 * Several marker-input changes — Namespace switch, environment switch, Find
 * Usages navigation — must share one restart window rather than each calling
 * [com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.restart] synchronously
 * (the source of resolved↔unresolved flicker).
 */
class NavigationIndexRefreshServiceTest {

    @Test
    fun `several requests inside one window produce a single restart`() {
        val scheduled = mutableListOf<() -> Unit>()
        val restarts = mutableListOf<Project>()
        val project = liveProject("p1")
        val service = NavigationIndexRefreshService(
            scheduleOnEdt = { action -> scheduled += action },
            restartDaemon = { restarts += it },
            openProjects = { emptyArray() }
        )

        service.requestGutterPass(project)
        service.requestGutterPass(project)
        service.requestGutterPass(project)

        assertEquals(1, scheduled.size, "only the first request schedules the EDT batch")
        assertTrue(restarts.isEmpty())

        scheduled.single().invoke()

        assertEquals(listOf(project), restarts)
    }

    @Test
    fun `requests for distinct projects restart each project once`() {
        val scheduled = mutableListOf<() -> Unit>()
        val restarts = mutableListOf<Project>()
        val first = liveProject("p1")
        val second = liveProject("p2")
        val service = NavigationIndexRefreshService(
            scheduleOnEdt = { action -> scheduled += action },
            restartDaemon = { restarts += it },
            openProjects = { emptyArray() }
        )

        service.requestGutterPass(first)
        service.requestGutterPass(second)
        scheduled.single().invoke()

        assertEquals(setOf(first, second), restarts.toSet())
        assertEquals(2, restarts.size)
    }

    @Test
    fun `a null project expands to every open project`() {
        val scheduled = mutableListOf<() -> Unit>()
        val restarts = mutableListOf<Project>()
        val open = arrayOf(liveProject("a"), liveProject("b"))
        val service = NavigationIndexRefreshService(
            scheduleOnEdt = { action -> scheduled += action },
            restartDaemon = { restarts += it },
            openProjects = { open }
        )

        service.requestGutterPass(null)
        scheduled.single().invoke()

        assertEquals(open.toList(), restarts)
    }

    @Test
    fun `disposed and default projects are skipped`() {
        val scheduled = mutableListOf<() -> Unit>()
        val restarts = mutableListOf<Project>()
        val live = liveProject("live")
        val disposed = mock<Project>().also {
            whenever(it.isDisposed).thenReturn(true)
            whenever(it.isDefault).thenReturn(false)
        }
        val defaultProject = mock<Project>().also {
            whenever(it.isDisposed).thenReturn(false)
            whenever(it.isDefault).thenReturn(true)
        }
        val service = NavigationIndexRefreshService(
            scheduleOnEdt = { action -> scheduled += action },
            restartDaemon = { restarts += it },
            openProjects = { emptyArray() }
        )

        service.requestGutterPass(live)
        service.requestGutterPass(disposed)
        service.requestGutterPass(defaultProject)
        scheduled.single().invoke()

        assertEquals(listOf(live), restarts)
    }

    @Test
    fun `a second wave after the batch flushes schedules a new restart`() {
        val scheduled = mutableListOf<() -> Unit>()
        val restarts = mutableListOf<Project>()
        val project = liveProject("p1")
        val service = NavigationIndexRefreshService(
            scheduleOnEdt = { action -> scheduled += action },
            restartDaemon = { restarts += it },
            openProjects = { emptyArray() }
        )

        service.requestGutterPass(project)
        scheduled.single().invoke()
        assertEquals(1, restarts.size)

        service.requestGutterPass(project)
        assertEquals(2, scheduled.size)
        scheduled[1].invoke()
        assertEquals(2, restarts.size)
    }

    @Test
    fun `requestGutterPass does not require a cache identity or remote read`() {
        // Compile-time contract: the marker-input entry takes only a Project.
        // Calling it must not touch CacheService / NacosApiService — the test
        // constructs the service with no application-level collaborators.
        val scheduled = mutableListOf<() -> Unit>()
        var restarted = false
        val project = liveProject("p1")
        val service = NavigationIndexRefreshService(
            scheduleOnEdt = { action -> scheduled += action },
            restartDaemon = { restarted = true },
            openProjects = { emptyArray() }
        )

        service.requestGutterPass(project)
        scheduled.single().invoke()

        assertTrue(restarted)
        // No exception, no identity argument, no network: re-analyze only.
        assertFalse(project.isDisposed)
    }

    private fun liveProject(name: String): Project = mock<Project>().also {
        whenever(it.isDisposed).thenReturn(false)
        whenever(it.isDefault).thenReturn(false)
        whenever(it.name).thenReturn(name)
        whenever(it.toString()).thenReturn("Project($name)")
    }
}
