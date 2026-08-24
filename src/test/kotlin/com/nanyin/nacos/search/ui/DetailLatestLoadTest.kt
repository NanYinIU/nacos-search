package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.operations.DetailReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Latest-wins 配置详情 confirmation (issue #247): a newer selection must start
 * even while an older one is in flight, and the older job must not paint or
 * clear loading once it has been replaced.
 *
 * Driven through [DetailLatestLoad] (Job + loading + replace) and the pure
 * [DetailController] / [PresentationGate] seams the panel uses. Holds park
 * with a controllable deferred so the race is deterministic. No Swing fixture.
 */
class DetailLatestLoadTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `selecting B while A is parked starts B confirmation before A completes`() = runBlocking {
        val harness = Harness()
        val aHold = CompletableDeferred<Unit>()
        val bHold = CompletableDeferred<Unit>()

        harness.select(config("a.yaml", "a-remote"), hold = aHold)
        harness.awaitStarted("a.yaml")
        assertTrue(harness.loader.isLoading)

        harness.select(config("b.yaml", "b-remote"), hold = bHold)
        harness.awaitStarted("b.yaml")

        assertEquals(listOf("a.yaml", "b.yaml"), harness.started.toList())
        assertTrue(harness.loader.isLoading, "B owns loading as soon as it is admitted")
        assertEquals(DetailViewState.Loading, harness.states.last())
        assertFalse("a.yaml" in harness.completed)
    }

    @Test
    fun `uncached B finishes as B body after a late A result`() = runBlocking {
        val harness = Harness()
        val aHold = CompletableDeferred<Unit>()
        val bHold = CompletableDeferred<Unit>()

        harness.select(config("a.yaml", "a-remote"), hold = aHold, surviveCancel = true)
        harness.awaitStarted("a.yaml")
        harness.select(config("b.yaml", "b-remote"), hold = bHold)
        harness.awaitStarted("b.yaml")

        aHold.complete(Unit)
        harness.awaitCompleted("a.yaml")
        delay(50)

        assertTrue(harness.loader.isLoading, "A cleanup must not clear B's loading")
        assertFalse(
            harness.states.any { it is DetailViewState.Body && it.configuration.dataId == "a.yaml" },
            "A must not repaint once B is selected"
        )
        assertFalse(
            harness.states.any { it is DetailViewState.Stale },
            "A's late result must not reach onPresented"
        )
        assertEquals(DetailViewState.Loading, harness.states.last())

        bHold.complete(Unit)
        harness.awaitBody("b.yaml")

        val body = harness.states.last() as DetailViewState.Body
        assertEquals("b-remote", body.configuration.content)
        assertFalse(harness.loader.isLoading)
    }

    @Test
    fun `cached B paints immediately and still confirms while A is in flight`() = runBlocking {
        val harness = Harness()
        val aHold = CompletableDeferred<Unit>()
        val bHold = CompletableDeferred<Unit>()
        val cachedB = cached(config("b.yaml", "# cached-b"))

        harness.select(config("a.yaml", "a-remote"), hold = aHold, surviveCancel = true)
        harness.awaitStarted("a.yaml")
        harness.select(config("b.yaml", "b-remote"), cached = cachedB, hold = bHold)
        harness.awaitStarted("b.yaml")

        val afterSelectB = harness.states.last()
        assertTrue(afterSelectB is DetailViewState.Body)
        assertEquals("# cached-b", (afterSelectB as DetailViewState.Body).configuration.content)
        assertTrue(harness.loader.isLoading, "planned confirmation for B is still in flight")

        aHold.complete(Unit)
        harness.awaitCompleted("a.yaml")
        delay(50)

        assertTrue(harness.loader.isLoading)
        assertEquals("# cached-b", (harness.states.last() as DetailViewState.Body).configuration.content)
        assertFalse(harness.states.any { it is DetailViewState.Stale })

        bHold.complete(Unit)
        harness.awaitBody("b.yaml", content = "b-remote")
        assertEquals("b-remote", (harness.states.last() as DetailViewState.Body).configuration.content)
        assertFalse(harness.loader.isLoading)
    }

    @Test
    fun `A completing after B has finished cannot clear B or restore loading`() = runBlocking {
        val harness = Harness()
        val aHold = CompletableDeferred<Unit>()

        harness.select(config("a.yaml", "a-remote"), hold = aHold, surviveCancel = true)
        harness.awaitStarted("a.yaml")
        harness.select(config("b.yaml", "b-remote"))
        harness.awaitBody("b.yaml")

        assertFalse(harness.loader.isLoading)
        assertEquals("b-remote", (harness.states.last() as DetailViewState.Body).configuration.content)

        aHold.complete(Unit)
        harness.awaitCompleted("a.yaml")
        delay(50)

        assertFalse(harness.loader.isLoading)
        assertEquals("b-remote", (harness.states.last() as DetailViewState.Body).configuration.content)
        assertEquals(
            0,
            harness.states.count { it is DetailViewState.Body && it.configuration.dataId == "a.yaml" }
        )
        assertFalse(harness.states.any { it is DetailViewState.Stale })
    }

    @Test
    fun `unpaintable failure still releases loading when this job owns it`() = runBlocking {
        var epoch = 0L
        val harness = Harness(epoch = { epoch })
        val hold = CompletableDeferred<Unit>()

        harness.select(config("a.yaml", "a-remote"), hold = hold, failAfterHold = true)
        harness.awaitStarted("a.yaml")
        assertTrue(harness.loader.isLoading)

        epoch = 1L
        hold.complete(Unit)
        harness.awaitCompleted("a.yaml")
        withTimeout(2_000) {
            while (harness.loader.isLoading) delay(10)
        }

        assertFalse(harness.loader.isLoading, "gate-rejected failure must still release loading")
        assertEquals(DetailViewState.Loading, harness.states.last())
        assertFalse(harness.states.any { it is DetailViewState.Failed })
        assertFalse(harness.states.any { it is DetailViewState.Stale })
    }

    private inner class Harness(
        private val epoch: () -> Long = { 0L }
    ) {
        var selected: PresentedCoordinate? = null
        val gate = PresentationGate(epoch) { selected }
        val controller = DetailController(gate)
        val loader = DetailLatestLoad(scope)
        val states = CopyOnWriteArrayList<DetailViewState>()
        val started = CopyOnWriteArrayList<String>()
        val completed = CopyOnWriteArrayList<String>()
        private val observations = AtomicLong(0)

        fun select(
            configuration: NacosConfiguration,
            cached: CacheService.CachedConfiguration? = null,
            hold: CompletableDeferred<Unit>? = null,
            surviveCancel: Boolean = false,
            failAfterHold: Boolean = false
        ) {
            selected = PresentedCoordinate.of(configuration)
            val issued = PresentedResult(epoch(), selected)
            val plan = controller.planSelection(cached)
            plan.immediate?.let { states += it }
            if (!plan.shouldLoad) return
            val retainedConfidence = plan.immediate?.confidence
            loader.replace(
                keepCachedVisible = plan.keepCachedVisible,
                run = {
                    started += configuration.dataId
                    try {
                        if (hold != null) {
                            if (surviveCancel) withContext(NonCancellable) { hold.await() }
                            else hold.await()
                        }
                        completed += configuration.dataId
                        if (failAfterHold) {
                            throw RuntimeException("confirmation failed")
                        }
                        val result = DetailReadResult.Present(
                            configuration = configuration,
                            observation = observations.incrementAndGet(),
                            servedFromCache = false
                        )
                        controller.present(result, issued, retainedConfidence)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (configuration.dataId !in completed) {
                            completed += configuration.dataId
                        }
                        if (!gate.admitAndRecord(issued)) null
                        else DetailPresentation.fromFailure(error, error.message ?: "Unknown error")
                    }
                },
                onLoadingChanged = { },
                onPresented = { states += it }
            )
        }

        suspend fun awaitStarted(dataId: String) {
            withTimeout(2_000) {
                while (dataId !in started) delay(10)
            }
        }

        suspend fun awaitCompleted(dataId: String) {
            withTimeout(2_000) {
                while (dataId !in completed) delay(10)
            }
        }

        suspend fun awaitBody(dataId: String, content: String? = null) {
            withTimeout(2_000) {
                while (true) {
                    val body = states.lastOrNull() as? DetailViewState.Body
                    if (body?.configuration?.dataId == dataId &&
                        (content == null || body.configuration.content == content)
                    ) {
                        return@withTimeout
                    }
                    delay(10)
                }
            }
        }
    }

    private fun config(dataId: String, content: String) = NacosConfiguration(
        dataId = dataId,
        group = "G",
        tenantId = "dev",
        content = content,
        type = "yaml"
    )

    private fun cached(configuration: NacosConfiguration) = CacheService.CachedConfiguration(
        configuration = configuration,
        freshness = CacheService.DetailFreshness.FRESH,
        freshUntilMillis = 1L,
        deepStaleAtMillis = 2L
    )
}
