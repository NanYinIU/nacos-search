package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublishControllerTest {

    @Test
    fun `preflight detects deleted target`() = runBlocking {
        val controller = PublishController(ScriptedPublishGateway(
            preflightResult = Result.success(null)
        ))
        val session = v1EditSession(draftContent = "new content")

        val result = controller.publish(session)

        assertEquals(PublishState.TargetDeleted, result.state)
        assertTrue(result.isDirty)
    }

    @Test
    fun `preflight detects remote conflict when content differs`() = runBlocking {
        val controller = PublishController(ScriptedPublishGateway(
            preflightResult = Result.success(NacosConfiguration(
                dataId = "app.yaml", group = "G",
                content = "changed by someone else", md5 = "different-md5"
            ))
        ))
        val session = v1EditSession(baselineMd5 = "base-md5", draftContent = "new content")

        val result = controller.publish(session)

        assertInstanceOf<PublishState.RemoteConflict>(result.state)
        assertEquals("changed by someone else", (result.state as PublishState.RemoteConflict).remoteContent)
        assertTrue(result.isDirty)
    }

    @Test
    fun `V1 CAS conflict maps to WriteConflict and retains draft`() = runBlocking {
        val gateway = ScriptedPublishGateway(
            preflightResult = Result.success(baseDetail()),
            publishResult = Result.success(PublishOutcome.CasConflict)
        )
        val controller = PublishController(gateway)
        val session = v1EditSession(baselineMd5 = "base-md5", draftContent = "new content")

        val result = controller.publish(session)

        assertTrue(result.state is PublishState.RemoteConflict)
        assertTrue(result.isDirty)
    }

    @Test
    fun `successful V1 write and matching read-back reaches verified`() = runBlocking {
        val gateway = ScriptedPublishGateway(
            preflightResult = Result.success(baseDetail()),
            publishResult = Result.success(PublishOutcome.Written("true")),
            readBackResult = Result.success(NacosConfiguration(
                dataId = "app.yaml", group = "G", content = "new content", md5 = "new-md5", type = "yaml"
            ))
        )
        val controller = PublishController(gateway)
        val session = v1EditSession(baselineMd5 = "base-md5", draftContent = "new content")

        val result = controller.publish(session)

        assertEquals(PublishState.Verified, result.state)
        assertTrue(!result.isDirty)
    }

    @Test
    fun `read-back equal to baseline proves command not visible not write never applied`() = runBlocking {
        val gateway = ScriptedPublishGateway(
            preflightResult = Result.success(baseDetail()),
            publishResult = Result.success(PublishOutcome.Written("true")),
            readBackResult = Result.success(baseDetail())
        )
        val controller = PublishController(gateway)
        val session = v1EditSession(baselineMd5 = "base-md5", baselineContent = "original", draftContent = "new content")

        val result = controller.publish(session)

        assertEquals(PublishState.Dirty, result.state)
        assertTrue(result.isDirty)
    }

    @Test
    fun `read-back returning third value is a conflict`() = runBlocking {
        val gateway = ScriptedPublishGateway(
            preflightResult = Result.success(baseDetail()),
            publishResult = Result.success(PublishOutcome.Written("true")),
            readBackResult = Result.success(NacosConfiguration(
                dataId = "app.yaml", group = "G", content = "third party wrote this", md5 = "third", type = "yaml"
            ))
        )
        val controller = PublishController(gateway)
        val session = v1EditSession(baselineMd5 = "base-md5", draftContent = "new content")

        val result = controller.publish(session)

        assertInstanceOf<PublishState.RemoteConflict>(result.state)
        assertTrue(result.isDirty)
    }

    @Test
    fun `read-back returning null means deleted target conflict`() = runBlocking {
        val gateway = ScriptedPublishGateway(
            preflightResult = Result.success(baseDetail()),
            publishResult = Result.success(PublishOutcome.Written("true")),
            readBackResult = Result.success(null)
        )
        val controller = PublishController(gateway)
        val session = v1EditSession(baselineMd5 = "base-md5", draftContent = "new content")

        val result = controller.publish(session)

        assertEquals(PublishState.TargetDeleted, result.state)
        assertTrue(result.isDirty)
    }

    @Test
    fun `read-back failure enters server-state-unknown`() = runBlocking {
        val gateway = ScriptedPublishGateway(
            preflightResult = Result.success(baseDetail()),
            publishResult = Result.success(PublishOutcome.Written("true")),
            readBackResult = Result.failure(RemoteOperationError.Server(500))
        )
        val controller = PublishController(gateway)
        val session = v1EditSession(baselineMd5 = "base-md5", draftContent = "new content")

        val result = controller.publish(session)

        assertEquals(PublishState.ServerStateUnknown, result.state)
        assertTrue(result.isDirty)
    }

    @Test
    fun `publish failure after send enters server-state-unknown`() = runBlocking {
        val gateway = ScriptedPublishGateway(
            preflightResult = Result.success(baseDetail()),
            publishResult = Result.failure(RemoteOperationError.Connection(RuntimeException("disconnected")))
        )
        val controller = PublishController(gateway)
        val session = v1EditSession(baselineMd5 = "base-md5", draftContent = "new content")

        val result = controller.publish(session)

        assertEquals(PublishState.ServerStateUnknown, result.state)
        assertTrue(result.isDirty)
    }

    @Test
    fun `permission denied during write retains draft but does not enter unknown`() = runBlocking {
        val gateway = ScriptedPublishGateway(
            preflightResult = Result.success(baseDetail()),
            publishResult = Result.failure(RemoteOperationError.Authorization(403))
        )
        val controller = PublishController(gateway)
        val session = v1EditSession(baselineMd5 = "base-md5", draftContent = "new content")

        val result = controller.publish(session)

        assertEquals(PublishState.PermissionDenied, result.state)
        assertTrue(result.isDirty)
    }

    @Test
    fun `encrypted config is read-only`() = runBlocking {
        val gateway = ScriptedPublishGateway(
            preflightResult = Result.success(NacosConfiguration(
                dataId = "app.yaml", group = "G", content = "encrypted content",
                md5 = "base-md5", type = "yaml", encryptedDataKey = "enc-key"
            ))
        )
        val controller = PublishController(gateway)
        val session = v1EditSession(baselineMd5 = "base-md5", draftContent = "new content")

        val result = controller.publish(session)

        assertInstanceOf<PublishState.ReadOnly>(result.state)
        assertTrue(result.isDirty)
    }

    @Test
    fun `read-back with matching content but lost type is not verified`() = runBlocking {
        // Spec §16.3 step 10: VERIFIED requires metadata equality, not just
        // content equality. Content matches but type was lost by the server.
        val base = NacosConfiguration(
            dataId = "app.yaml", group = "G", content = "original",
            md5 = "base-md5", type = "yaml", appName = "myapp",
            desc = "description", configTags = "tag1,tag2"
        )
        val gateway = ScriptedPublishGateway(
            preflightResult = Result.success(base),
            publishResult = Result.success(PublishOutcome.Written("true")),
            readBackResult = Result.success(NacosConfiguration(
                dataId = "app.yaml", group = "G", content = "new content",
                md5 = "new-md5", type = null,
                appName = "myapp", desc = "description", configTags = "tag1,tag2"
            ))
        )
        val controller = PublishController(gateway)
        val session = v1EditSession(baselineMd5 = "base-md5", draftContent = "new content")

        val result = controller.publish(session)

        assertNotEquals(PublishState.Verified, result.state)
        assertTrue(result.isDirty)
    }

    @Test
    fun `read-back with matching content and all metadata is verified`() = runBlocking {
        val base = NacosConfiguration(
            dataId = "app.yaml", group = "G", content = "original",
            md5 = "base-md5", type = "yaml", appName = "myapp",
            desc = "description", configTags = "tag1,tag2"
        )
        val gateway = ScriptedPublishGateway(
            preflightResult = Result.success(base),
            publishResult = Result.success(PublishOutcome.Written("true")),
            readBackResult = Result.success(NacosConfiguration(
                dataId = "app.yaml", group = "G", content = "new content",
                md5 = "new-md5", type = "yaml", appName = "myapp",
                desc = "description", configTags = "tag1,tag2"
            ))
        )
        val controller = PublishController(gateway)
        val session = v1EditSession(baselineMd5 = "base-md5", draftContent = "new content")

        val result = controller.publish(session)

        assertEquals(PublishState.Verified, result.state)
        assertTrue(!result.isDirty)
    }

    // ---- helpers ----

    private fun baseDetail() = NacosConfiguration(
        dataId = "app.yaml", group = "G", content = "original", md5 = "base-md5", type = "yaml"
    )

    private fun v1EditSession(
        baselineMd5: String = "base-md5",
        baselineContent: String = "original",
        draftContent: String = "edited"
    ): EditSession {
        val endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow()
        val context = NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = "p", accessRevision = 1,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.ANONYMOUS, principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = 1, accessRevision = 1,
            resolvedGeneration = NacosApiGeneration.V1
        )
        return EditSession(
            target = OperationTarget(context, "public"),
            dataId = "app.yaml", group = "G", namespaceId = "public",
            baselineContent = baselineContent, baselineMd5 = baselineMd5,
            baselineType = "yaml", baselineAppName = null,
            baselineDesc = null, baselineConfigTags = null,
            draftContent = draftContent
        )
    }
}

private inline fun <reified T> assertInstanceOf(value: Any?) {
    org.junit.jupiter.api.Assertions.assertInstanceOf(T::class.java, value)
}

class ScriptedPublishGateway(
    val preflightResult: Result<NacosConfiguration?> = Result.success(null),
    val publishResult: Result<PublishOutcome> = Result.success(PublishOutcome.Written("true")),
    val readBackResult: Result<NacosConfiguration?> = Result.success(null)
) : PublishGateway {
    override suspend fun preflight(session: EditSession): Result<NacosConfiguration?> = preflightResult
    override suspend fun write(session: EditSession, command: PublishCommand): Result<PublishOutcome> = publishResult
    override suspend fun readBack(session: EditSession): Result<NacosConfiguration?> = readBackResult
}
