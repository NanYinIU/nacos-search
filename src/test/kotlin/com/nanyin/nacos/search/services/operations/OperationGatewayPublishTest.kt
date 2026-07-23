package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.settings.AuthMode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class OperationGatewayPublishTest {

    @Test
    fun `gateway publish routes to the adapter for the target generation`() = runBlocking {
        val adapter = RecordingPublishAdapter()
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))
        val target = v1Target()
        val command = PublishCommand("app.yaml", "G", "content", "yaml", "public", casMd5 = "m1")

        val outcome = gateway.publish(target, command).getOrThrow()

        assertEquals(PublishOutcome.Written("ok"), outcome)
        assertEquals(1, adapter.publishCalls)
        assertEquals(command, adapter.lastCommand)
    }

    @Test
    fun `gateway publish returns Unsupported when no adapter exists`() = runBlocking {
        val gateway = OperationGateway(emptyMap())
        val error = gateway.publish(
            v1Target(),
            PublishCommand("a", "G", "c", "text", "public")
        ).exceptionOrNull()

        assertInstanceOf(RemoteOperationError.Unsupported::class.java, error)
    }

    @Test
    fun `PublishGateway preflight write and readBack use the session target`() = runBlocking {
        val adapter = RecordingPublishAdapter()
        val gateway = OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))
        val publishGateway = OperationGatewayPublishGateway(gateway)
        val session = EditSession(
            target = v1Target(),
            dataId = "app.yaml",
            group = "G",
            namespaceId = "public",
            baselineContent = "old",
            baselineMd5 = "m1",
            baselineType = "yaml",
            baselineAppName = null,
            baselineDesc = null,
            baselineConfigTags = null,
            draftContent = "new"
        )

        val preflight = publishGateway.preflight(session).getOrThrow()
        assertEquals("old", preflight?.content)

        val write = publishGateway.write(session, session.toCommand(preflight!!)).getOrThrow()
        assertEquals(PublishOutcome.Written("ok"), write)

        val readBack = publishGateway.readBack(session).getOrThrow()
        assertEquals("old", readBack?.content)
        assertEquals(1, adapter.publishCalls)
        assertEquals(2, adapter.detailCalls)
    }

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

    private class RecordingPublishAdapter : ProtocolAdapter {
        var publishCalls = 0
        var detailCalls = 0
        var lastCommand: PublishCommand? = null

        override suspend fun probe(target: OperationTarget) = Result.success(Unit)

        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, 1, 0, emptyList()))

        override suspend fun readDetail(target: OperationTarget, coordinate: ConfigurationCoordinate): Result<NacosConfiguration?> {
            detailCalls++
            return Result.success(
                NacosConfiguration(
                    dataId = coordinate.dataId,
                    group = coordinate.group,
                    content = "old",
                    type = "yaml",
                    md5 = "m1",
                    tenantId = target.namespaceId
                )
            )
        }

        override suspend fun publish(target: OperationTarget, command: PublishCommand): Result<PublishOutcome> {
            publishCalls++
            lastCommand = command
            return Result.success(PublishOutcome.Written("ok"))
        }
    }
}
