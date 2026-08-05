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
        val outcome = controller(gateway).loadPage(v1Target(), query(), sessionEpoch = 0)
        assertEquals(HistoryBrowserController.Outcome.Empty, outcome)
    }

    @Test
    fun `maps Authorization to PermissionDenied`() = runBlocking {
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to StubHistoryAdapter(error = RemoteOperationError.Authorization(403)))
        )
        val outcome = controller(gateway).loadPage(v1Target(), query(), sessionEpoch = 0)
        assertEquals(HistoryBrowserController.Outcome.PermissionDenied, outcome)
    }

    @Test
    fun `maps CapabilityUnsupported to Unsupported`() = runBlocking {
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to NonHistoryAdapter()))
        val outcome = controller(gateway).loadPage(v1Target(), query(), sessionEpoch = 0)
        assertEquals(HistoryBrowserController.Outcome.Unsupported, outcome)
    }

    @Test
    fun `maps success page to Body`() = runBlocking {
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to StubHistoryAdapter()))
        val outcome = controller(gateway).loadPage(v1Target(), query(), sessionEpoch = 0)
        assertInstanceOf(HistoryBrowserController.Outcome.Body::class.java, outcome)
        val body = outcome as HistoryBrowserController.Outcome.Body
        assertEquals(1, body.page.items.size)
    }

    @Test
    fun `drops stale results when the session epoch advances mid-load`() = runBlocking {
        val epoch = AtomicLong(0)
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to StubHistoryAdapter(onList = {
            epoch.set(1)
        })))
        val outcome = controller(gateway) { epoch.get() }
            .loadPage(v1Target(), query(), sessionEpoch = 0)
        assertEquals(HistoryBrowserController.Outcome.Stale, outcome)
    }

    @Test
    fun `drops a detail read that started before one already painted`() = runBlocking {
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to StubHistoryAdapter()))
        val diffPresentation = gate()
        val controller = HistoryBrowserController(gateway, gate(), diffPresentation)

        assertTrue(controller.loadDetail(v1Target(), coordinate(), "1", sessionEpoch = 0).isSuccess)

        // A later-started read paints; the earlier one must not replace it.
        assertTrue(
            diffPresentation.admitAndRecord(
                PresentedResult(0, presentedCoordinate(), observation = Long.MAX_VALUE)
            )
        )
        assertTrue(controller.loadDetail(v1Target(), coordinate(), "1", sessionEpoch = 0).isFailure)
    }

    @Test
    fun `a detail read is not ranked against the revision list's own reads`() = runBlocking {
        // The revision list and the diff are separate presentation slots, so a
        // page reload cannot discard a diff read that started before it.
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to StubHistoryAdapter()))
        val controller = controller(gateway)

        assertTrue(controller.loadDetail(v1Target(), coordinate(), "1", sessionEpoch = 0).isSuccess)
        assertInstanceOf(
            HistoryBrowserController.Outcome.Body::class.java,
            controller.loadPage(v1Target(), query(), sessionEpoch = 0)
        )
        assertTrue(controller.loadDetail(v1Target(), coordinate(), "1", sessionEpoch = 0).isSuccess)
    }

    @Test
    fun `a revision row that omits its coordinate does not discard the read`() = runBlocking {
        // The judged coordinate comes from what the dialog is browsing, never
        // from the server's response, which may omit the data id and group.
        val gateway = OperationGateway(
            mapOf(NacosApiGeneration.V1 to StubHistoryAdapter(blankEntryCoordinate = true))
        )
        val controller = controller(gateway)

        assertInstanceOf(
            HistoryBrowserController.Outcome.Body::class.java,
            controller.loadPage(v1Target(), query(), sessionEpoch = 0)
        )
        assertTrue(controller.loadDetail(v1Target(), coordinate(), "1", sessionEpoch = 0).isSuccess)
    }

    @Test
    fun `selectedEntries returns only existing indices`() {
        val controller = controller(OperationGateway(emptyMap()))
        val entries = listOf(
            HistoryEntry("1", "a", "G", null, "yaml", "m", 1L, "PUBLISH"),
            HistoryEntry("2", "a", "G", null, "yaml", "m", 2L, "PUBLISH")
        )
        assertEquals(listOf(entries[1]), controller.selectedEntries(entries, intArrayOf(1)))
        assertTrue(controller.selectedEntries(entries, intArrayOf(9)).isEmpty())
    }

    private fun controller(
        gateway: OperationGateway,
        currentEpoch: () -> Long = { 0L }
    ) = HistoryBrowserController(gateway, gate(currentEpoch), gate(currentEpoch))

    private fun gate(currentEpoch: () -> Long = { 0L }) =
        PresentationGate(currentEpoch) { presentedCoordinate() }

    private fun presentedCoordinate() = PresentedCoordinate.of("public", "app.yaml", "G")

    private fun coordinate() = ConfigurationCoordinate("app.yaml", "G")

    private fun query() = HistoryQuery(coordinate())

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
        private val onList: (() -> Unit)? = null,
        private val blankEntryCoordinate: Boolean = false
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
            val dataId = if (blankEntryCoordinate) "" else "app.yaml"
            val group = if (blankEntryCoordinate) "" else "G"
            return Result.success(
                HistoryPage(1, 1, 1, listOf(HistoryEntry("1", dataId, group, null, "yaml", "m1", 1000L, "PUBLISH")))
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
