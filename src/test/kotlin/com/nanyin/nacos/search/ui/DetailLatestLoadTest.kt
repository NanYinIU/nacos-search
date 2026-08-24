package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.operations.DetailReadResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
 * Driven through [DetailController] / [PresentationGate] / [DetailLatestLoad]
 * — the same seams the detail panel uses — with a controllable deferred so
 * the race is deterministic. No Swing fixture.
 */
class DetailLatestLoadTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `admitLoad does not reject a newer selection while already loading`() {
        val controller = controller()
        val first = controller.admitLoad()
        assertTrue(controller.isLoading)

        val second = controller.admitLoad()
        assertTrue(controller.isLoading)
        assertFalse(controller.stillOwns(first))
        assertTrue(controller.stillOwns(second))
    }

    @Test
    fun `finishing a superseded load cannot clear loading owned by a newer one`() {
        val controller = controller()
        val first = controller.admitLoad()
        val second = controller.admitLoad()

        controller.finishLoad(first)
        assertTrue(controller.isLoading)
        assertTrue(controller.stillOwns(second))

        controller.finishLoad(second)
        assertFalse(controller.isLoading)
    }

    @Test
    fun `selecting B while A is parked starts B confirmation before A completes`() = runBlocking {
        val harness = Harness()
        val aHold = CompletableDeferred<Unit>()
        val bHold = CompletableDeferred<Unit>()

        harness.select(config("a.yaml", "a-remote"), hold = aHold)
        harness.awaitStarted("a.yaml")
        assertTrue(harness.controller.isLoading)

        harness.select(config("b.yaml", "b-remote"), hold = bHold)
        harness.awaitStarted("b.yaml")

        assertEquals(listOf("a.yaml", "b.yaml"), harness.started.toList())
        assertTrue(harness.controller.isLoading, "B owns loading as soon as it is admitted")
        assertEquals(DetailViewState.Loading, harness.states.last())
    }

    @Test
    fun `uncached B finishes as B body after A is cancelled still parked`() = runBlocking {
        val harness = Harness()
        val aHold = CompletableDeferred<Unit>()
        val bHold = CompletableDeferred<Unit>()

        harness.select(config("a.yaml", "a-remote"), hold = aHold)
        harness.awaitStarted("a.yaml")
        harness.select(config("b.yaml", "b-remote"), hold = bHold)
        harness.awaitStarted("b.yaml")

        aHold.complete(Unit)
        delay(50)

        assertTrue(harness.controller.isLoading, "A cleanup must not clear B's loading")
        assertFalse(
            harness.states.any { it is DetailViewState.Body && it.configuration.dataId == "a.yaml" },
            "A must not repaint once B is selected"
        )
        assertEquals(DetailViewState.Loading, harness.states.last())

        bHold.complete(Unit)
        harness.awaitBody("b.yaml")

        val body = harness.states.last() as DetailViewState.Body
        assertEquals("b-remote", body.configuration.content)
        assertFalse(harness.controller.isLoading)
    }

    @Test
    fun `cached B paints immediately and still confirms while A is in flight`() = runBlocking {
        val harness = Harness()
        val aHold = CompletableDeferred<Unit>()
        val bHold = CompletableDeferred<Unit>()
        val cachedB = cached(config("b.yaml", "# cached-b"))

        harness.select(config("a.yaml", "a-remote"), hold = aHold)
        harness.awaitStarted("a.yaml")
        harness.select(config("b.yaml", "b-remote"), cached = cachedB, hold = bHold)
        harness.awaitStarted("b.yaml")

        val afterSelectB = harness.states.last()
        assertTrue(afterSelectB is DetailViewState.Body)
        assertEquals("# cached-b", (afterSelectB as DetailViewState.Body).configuration.content)
        assertTrue(harness.controller.isLoading, "planned confirmation for B is still in flight")

        aHold.complete(Unit)
        delay(50)

        assertTrue(harness.controller.isLoading)
        assertEquals("# cached-b", (harness.states.last() as DetailViewState.Body).configuration.content)

        bHold.complete(Unit)
        harness.awaitBody("b.yaml", content = "b-remote")
        assertEquals("b-remote", (harness.states.last() as DetailViewState.Body).configuration.content)
        assertFalse(harness.controller.isLoading)
    }

    @Test
    fun `A completing after B has finished cannot clear B or restore loading`() = runBlocking {
        val harness = Harness()
        val aHold = CompletableDeferred<Unit>()

        harness.select(config("a.yaml", "a-remote"), hold = aHold)
        harness.awaitStarted("a.yaml")
        harness.select(config("b.yaml", "b-remote"))
        harness.awaitBody("b.yaml")

        assertFalse(harness.controller.isLoading)
        assertEquals("b-remote", (harness.states.last() as DetailViewState.Body).configuration.content)

        aHold.complete(Unit)
        delay(50)

        assertFalse(harness.controller.isLoading)
        assertEquals("b-remote", (harness.states.last() as DetailViewState.Body).configuration.content)
        assertEquals(
            0,
            harness.states.count { it is DetailViewState.Body && it.configuration.dataId == "a.yaml" }
        )
    }

    private inner class Harness {
        var selected: PresentedCoordinate? = null
        val gate = PresentationGate({ 0L }, { selected })
        val controller = DetailController(gate)
        val loader = DetailLatestLoad(controller, scope)
        val states = CopyOnWriteArrayList<DetailViewState>()
        val started = CopyOnWriteArrayList<String>()
        private val observations = AtomicLong(0)

        fun select(
            configuration: NacosConfiguration,
            cached: CacheService.CachedConfiguration? = null,
            hold: CompletableDeferred<Unit>? = null
        ) {
            selected = PresentedCoordinate.of(configuration)
            val issued = PresentedResult(0L, selected)
            val plan = controller.planSelection(cached)
            plan.immediate?.let { states += it }
            if (!plan.shouldLoad) return
            val retainedConfidence = plan.immediate?.confidence
            loader.replace(
                keepCachedVisible = plan.keepCachedVisible,
                confirm = {
                    started += configuration.dataId
                    hold?.await()
                    DetailReadResult.Present(
                        configuration = configuration,
                        observation = observations.incrementAndGet(),
                        servedFromCache = false
                    )
                },
                present = { result ->
                    controller.present(result, issued, retainedConfidence)
                },
                onLoadingChanged = { },
                onPresented = { states += it },
                mapFailure = { error ->
                    if (!gate.admitAndRecord(issued)) null
                    else DetailPresentation.fromFailure(error, error.message ?: "Unknown error")
                }
            )
        }

        suspend fun awaitStarted(dataId: String) {
            withTimeout(2_000) {
                while (dataId !in started) delay(10)
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

    private fun controller() = DetailController(PresentationGate({ 0L }) { null })

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
