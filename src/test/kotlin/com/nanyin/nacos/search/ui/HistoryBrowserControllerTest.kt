package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.operations.ConfigurationCoordinate
import com.nanyin.nacos.search.services.operations.HistoryCapability
import com.nanyin.nacos.search.services.operations.HistoryDetail
import com.nanyin.nacos.search.services.operations.HistoryEntry
import com.nanyin.nacos.search.services.operations.HistoryPage
import com.nanyin.nacos.search.services.operations.HistoryQuery
import com.nanyin.nacos.search.services.operations.OperationGateway
import com.nanyin.nacos.search.services.operations.OperationTarget
import com.nanyin.nacos.search.services.operations.ProtocolAdapter
import com.nanyin.nacos.search.services.operations.PublishCommand
import com.nanyin.nacos.search.services.operations.PublishOutcome
import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.services.operations.SummaryPage
import com.nanyin.nacos.search.services.operations.SummaryQuery
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

class HistoryBrowserControllerTest {

    @Test
    fun `maps empty page to Empty outcome`() = runBlocking {
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to StubHistoryAdapter(emptyPage = true)))
        val controller = HistoryBrowserController(gateway)
        val outcome = controller.loadPage(v1Target(), query(), expectedGeneration = 0)
        assertEquals(HistoryBrowserController.Outcome.Empty, outcome)
    }

    @Test
    fun `maps Authorization to PermissionDenied`() = runBlocking {
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to StubHistoryAdapter(error = RemoteOperationError.Authorization(403)))
        )
        val controller = HistoryBrowserController(gateway)
        val outcome = controller.loadPage(v1Target(), query(), expectedGeneration = 0)
        assertEquals(HistoryBrowserController.Outcome.PermissionDenied, outcome)
    }

    @Test
    fun `maps CapabilityUnsupported to Unsupported`() = runBlocking {
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to NonHistoryAdapter()))
        val controller = HistoryBrowserController(gateway)
        val outcome = controller.loadPage(v1Target(), query(), expectedGeneration = 0)
        assertEquals(HistoryBrowserController.Outcome.Unsupported, outcome)
    }

    @Test
    fun `maps success page to Body`() = runBlocking {
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to StubHistoryAdapter()))
        val controller = HistoryBrowserController(gateway)
        val outcome = controller.loadPage(v1Target(), query(), expectedGeneration = 0)
        assertInstanceOf(HistoryBrowserController.Outcome.Body::class.java, outcome)
        val body = outcome as HistoryBrowserController.Outcome.Body
        assertEquals(1, body.page.items.size)
    }

    @Test
    fun `drops stale results when generation advances mid-load`() = runBlocking {
        val generation = AtomicLong(0)
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to StubHistoryAdapter(onList = {
            generation.set(1)
        })))
        val controller = HistoryBrowserController(gateway) { generation.get() }
        val outcome = controller.loadPage(v1Target(), query(), expectedGeneration = 0)
        assertEquals(HistoryBrowserController.Outcome.Stale, outcome)
    }

    @Test
    fun `selectedEntries returns only existing indices`() {
        val controller = HistoryBrowserController(OperationGateway(emptyMap()))
        val entries = listOf(
            HistoryEntry("1", "a", "G", null, "yaml", "m", 1L, "PUBLISH"),
            HistoryEntry("2", "a", "G", null, "yaml", "m", 2L, "PUBLISH")
        )
        assertEquals(listOf(entries[1]), controller.selectedEntries(entries, intArrayOf(1)))
        assertTrue(controller.selectedEntries(entries, intArrayOf(9)).isEmpty())
    }

    private fun query() = HistoryQuery(ConfigurationCoordinate("app.yaml", "G"))

    private fun v1Target(): OperationTarget {
        val endpoint = com.nanyin.nacos.search.models.CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = com.nanyin.nacos.search.settings.NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "p1",
                accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = com.nanyin.nacos.search.settings.CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 1,
            accessRevision = 1,
            resolvedGeneration = NacosApiGeneration.V1
        )
        return OperationTarget(context, "public")
    }

    private class StubHistoryAdapter(
        private val emptyPage: Boolean = false,
        private val error: RemoteOperationError? = null,
        private val onList: (() -> Unit)? = null
    ) : ProtocolAdapter, HistoryCapability {
        override suspend fun probe(target: OperationTarget) = Result.success(Unit)
        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, 1, 0, emptyList()))
        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate): Result<NacosConfiguration?> =
            Result.success(null)
        override suspend fun publish(target: OperationTarget, command: PublishCommand): Result<PublishOutcome> =
            Result.success(PublishOutcome.Written("true"))
        override suspend fun listHistory(target: OperationTarget, query: HistoryQuery): Result<HistoryPage> {
            onList?.invoke()
            if (error != null) return Result.failure(error)
            if (emptyPage) return Result.success(HistoryPage(0, 1, 0, emptyList()))
            return Result.success(
                HistoryPage(1, 1, 1, listOf(HistoryEntry("1", "app.yaml", "G", null, "yaml", "m1", 1000L, "PUBLISH")))
            )
        }
        override suspend fun readHistoryDetail(target: OperationTarget, historyId: String): Result<HistoryDetail> =
            Result.success(HistoryDetail(historyId, "app.yaml", "G", null, "body", "yaml", "md5", 1000L, "PUBLISH"))
    }

    private class NonHistoryAdapter : ProtocolAdapter {
        override suspend fun probe(target: OperationTarget) = Result.success(Unit)
        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, 1, 0, emptyList()))
        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate): Result<NacosConfiguration?> =
            Result.success(null)
        override suspend fun publish(target: OperationTarget, command: PublishCommand): Result<PublishOutcome> =
            Result.success(PublishOutcome.Written("true"))
    }
}
