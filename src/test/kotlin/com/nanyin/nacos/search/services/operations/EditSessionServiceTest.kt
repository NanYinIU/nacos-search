package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.CredentialSnapshot
import com.nanyin.nacos.search.settings.NacosOperationContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives the edit session through its public interface over a stub protocol
 * adapter beneath a real [OperationGateway] — no platform fixture, no
 * event-dispatch pumping, no reflection, no mocking framework.
 */
class EditSessionServiceTest {

    // ---- an edit session exists from the moment editing starts ----

    @Test
    fun `beginEdit creates a session bound to what was selected when it started`() = runBlocking {
        val environment = StubEditEnvironment()
        val service = service(environment)

        val session = started(service.beginEdit(configuration(), "dev", baselineContent = "original"))

        assertEquals("p1", session.binding.profileId)
        assertEquals(environment.identity, session.binding.identity)
        assertEquals("dev", session.binding.namespaceId)
        assertEquals("app.yaml", session.binding.dataId)
        assertEquals("G", session.binding.group)
        assertEquals("original", session.baselineContent)
        assertEquals("base-md5", session.baselineMd5)
        assertEquals(session, service.currentSession())
    }

    @Test
    fun `a session starts clean and turns dirty only when the draft differs`() = runBlocking {
        val service = service()
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        assertFalse(service.isDirty())

        service.updateDraft("original")
        assertFalse(service.isDirty())

        service.updateDraft("edited")
        assertTrue(service.isDirty())

        service.updateDraft("original")
        assertFalse(service.isDirty())
    }

    @Test
    fun `the binding names the profile, the identity, the namespace and the coordinate`() = runBlocking {
        // Naming every component is the point: a credential could not be added
        // to the binding without this expected value failing to compile.
        val environment = StubEditEnvironment(credential = "s3cret")
        val service = service(environment)

        val session = started(service.beginEdit(configuration(), "dev", baselineContent = "original"))

        assertEquals(
            EditBinding("p1", environment.identity, "dev", "app.yaml", "G"),
            session.binding
        )
    }

    @Test
    fun `the bound namespace is canonical, so public spells one way`() = runBlocking {
        val service = service()
        val session = started(service.beginEdit(configuration(tenantId = ""), "", baselineContent = "original"))
        assertEquals("public", session.binding.namespaceId)
    }

    // ---- write intent: one check, consulted by the edit action and the save path ----

    @Test
    fun `the edit action explains a withheld write intent instead of starting an edit`() = runBlocking {
        val service = service(StubEditEnvironment(selected = profile(writeIntent = false)))

        val start = service.beginEdit(configuration(), "dev", baselineContent = "original")

        val withheld = assertInstanceOf(EditStart.WritesWithheld::class.java, start)
        assertEquals(WriteIntent.Cause.NOT_OPTED_IN, withheld.cause)
        assertNull(service.currentSession())
    }

    @Test
    fun `an unselected profile withholds write intent for its own reason`() = runBlocking {
        val service = service(StubEditEnvironment(selected = null))

        val start = service.beginEdit(configuration(), "dev", baselineContent = "original")

        val withheld = assertInstanceOf(EditStart.WritesWithheld::class.java, start)
        assertEquals(WriteIntent.Cause.NO_PROFILE_SELECTED, withheld.cause)
    }

    @Test
    fun `the save path consults the same write intent the edit started under`() = runBlocking {
        // A session that carries a withheld write intent can only be built
        // directly; the save path must refuse it rather than trusting that
        // beginEdit already did.
        val adapter = RecordingAdapter()
        val environment = StubEditEnvironment()
        val service = service(environment, adapter)
        val session = started(service.beginEdit(configuration(), "dev", baselineContent = "original"))

        val result = PublishController(OperationGatewayPublishGateway(gateway(adapter))).publish(
            session.copy(
                draftContent = "edited",
                writeIntent = WriteIntent.Withheld(WriteIntent.Cause.NOT_OPTED_IN)
            ),
            target = environment.target()
        )

        assertInstanceOf(PublishState.ReadOnly::class.java, result.state)
        assertEquals(0, adapter.publishCalls)
    }

    @Test
    fun `revoking write intent while a draft is open blocks the save path`() = runBlocking {
        // ADR-0026's opt-in can be turned off in Settings while a draft is
        // open. Blocking that gesture is a later slice, so until then the save
        // path must fail closed rather than publish under a revoked opt-in.
        val adapter = RecordingAdapter()
        val environment = StubEditEnvironment()
        val service = service(environment, adapter)
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        service.updateDraft("edited")

        environment.profiles["p1"] = profile(writeIntent = false)

        val result = requireNotNull(service.publish())

        assertInstanceOf(PublishState.ReadOnly::class.java, result.state)
        assertEquals(0, adapter.publishCalls)
        assertTrue(service.isDirty())
    }

    @Test
    fun `beginEdit reports an environment whose target cannot be captured`() = runBlocking {
        val service = service(StubEditEnvironment(captureFailure = IllegalStateException("credentials incomplete")))

        val start = service.beginEdit(configuration(), "dev", baselineContent = "original")

        assertInstanceOf(EditStart.Unavailable::class.java, start)
        assertNull(service.currentSession())
    }

    // ---- selecting another configuration ----

    @Test
    fun `without a dirty draft selecting another configuration proceeds with no prompt`() = runBlocking {
        val service = service()
        assertEquals(DraftGuard.Proceed, service.guardRetarget("dev", "other.yaml", "G"))

        service.beginEdit(configuration(), "dev", baselineContent = "original")
        assertEquals(DraftGuard.Proceed, service.guardRetarget("dev", "other.yaml", "G"))
    }

    @Test
    fun `with a dirty draft selecting another configuration asks before discarding`() = runBlocking {
        val service = service()
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        service.updateDraft("edited")

        val guard = service.guardRetarget("dev", "other.yaml", "G")

        val confirm = assertInstanceOf(DraftGuard.ConfirmDiscard::class.java, guard)
        assertEquals("app.yaml", confirm.draft.dataId)
        assertEquals("G", confirm.draft.group)
        assertEquals("dev", confirm.draft.namespaceId)
    }

    @Test
    fun `the same data id in another namespace is still another configuration`() = runBlocking {
        val service = service()
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        service.updateDraft("edited")

        assertInstanceOf(
            DraftGuard.ConfirmDiscard::class.java,
            service.guardRetarget("staging", "app.yaml", "G")
        )
    }

    @Test
    fun `re-selecting the draft's own configuration is not a retarget`() = runBlocking {
        val service = service()
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        service.updateDraft("edited")

        assertEquals(DraftGuard.AlreadyEditing, service.guardRetarget("dev", "app.yaml", "G"))
    }

    @Test
    fun `clearing the selection with a dirty draft asks before discarding`() = runBlocking {
        val service = service()
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        service.updateDraft("edited")

        assertInstanceOf(DraftGuard.ConfirmDiscard::class.java, service.guardRetarget(null, null, null))
    }

    @Test
    fun `cancelling keeps the draft and discarding drops it`() = runBlocking {
        val service = service()
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        service.updateDraft("edited")

        // Cancelling is not calling discardDraft: asking the guard must leave
        // the draft exactly as it was.
        assertInstanceOf(DraftGuard.ConfirmDiscard::class.java, service.guardRetarget("dev", "other.yaml", "G"))
        assertTrue(service.isDirty())
        assertEquals("edited", service.currentSession()?.draftContent)

        service.discardDraft()

        assertNull(service.currentSession())
        assertFalse(service.isDirty())
        assertEquals(DraftGuard.Proceed, service.guardRetarget("dev", "other.yaml", "G"))
    }

    // ---- closing or destroying the tool-window content ----

    @Test
    fun `without a dirty draft destroying the content proceeds with no prompt`() = runBlocking {
        val service = service()
        assertEquals(DraftGuard.Proceed, service.guardDestroy())

        service.beginEdit(configuration(), "dev", baselineContent = "original")
        assertEquals(DraftGuard.Proceed, service.guardDestroy())
    }

    @Test
    fun `with a dirty draft destroying the content asks before discarding`() = runBlocking {
        val service = service()
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        service.updateDraft("edited")

        val confirm = assertInstanceOf(DraftGuard.ConfirmDiscard::class.java, service.guardDestroy())
        assertEquals("app.yaml", confirm.draft.dataId)

        service.discardDraft()
        assertEquals(DraftGuard.Proceed, service.guardDestroy())
    }

    // ---- the publish target comes from the binding, never the live selection ----

    @Test
    fun `publishing goes to the environment the draft was started against`() = runBlocking {
        val adapter = RecordingAdapter()
        val environment = StubEditEnvironment()
        val service = service(environment, adapter)
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        service.updateDraft("edited")

        // The user retargets the tool window after starting the draft.
        environment.selected = profile(id = "p2")

        val result = requireNotNull(service.publish())

        assertEquals(PublishState.Verified, result.state)
        assertTrue(environment.captures.all { it == "p1" to "dev" })
        assertEquals("dev", adapter.lastPublishTarget?.namespaceId)
    }

    @Test
    fun `publishing captures fresh credentials rather than reusing the ones the edit started with`() = runBlocking {
        val adapter = RecordingAdapter()
        val environment = StubEditEnvironment(credential = "at-edit-time")
        val service = service(environment, adapter)
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        service.updateDraft("edited")

        environment.credential = "at-publish-time"
        requireNotNull(service.publish())

        assertEquals("at-publish-time", adapter.lastPublishTarget?.context?.credential?.secret)
    }

    @Test
    fun `a verified publish clears the draft`() = runBlocking {
        val service = service(StubEditEnvironment(), RecordingAdapter())
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        service.updateDraft("edited")

        assertEquals(PublishState.Verified, requireNotNull(service.publish()).state)

        assertNull(service.currentSession())
        assertFalse(service.isDirty())
        assertEquals(DraftGuard.Proceed, service.guardDestroy())
    }

    @Test
    fun `an unverified publish keeps the draft`() = runBlocking {
        val adapter = RecordingAdapter(remoteContent = "someone else wrote this", remoteMd5 = "other-md5")
        val service = service(StubEditEnvironment(), adapter)
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        service.updateDraft("edited")

        assertInstanceOf(PublishState.RemoteConflict::class.java, requireNotNull(service.publish()).state)

        assertTrue(service.isDirty())
        assertEquals("edited", service.currentSession()?.draftContent)
    }

    @Test
    fun `publishing under a changed access identity is refused, and the draft is kept`() = runBlocking {
        val adapter = RecordingAdapter()
        val environment = StubEditEnvironment()
        val service = service(environment, adapter)
        service.beginEdit(configuration(), "dev", baselineContent = "original")
        service.updateDraft("edited")

        // The profile's access revision moved: the same profile id now names a
        // different access identity than the one the draft is bound to.
        environment.accessRevision = 2

        val result = requireNotNull(service.publish())

        assertInstanceOf(PublishState.ReadOnly::class.java, result.state)
        assertEquals(0, adapter.publishCalls)
        assertTrue(service.isDirty())
    }

    @Test
    fun `publishing without a draft reports no session rather than writing`() = runBlocking {
        val adapter = RecordingAdapter()
        val service = service(StubEditEnvironment(), adapter)

        assertNull(service.publish())
        assertEquals(0, adapter.publishCalls)
    }

    // ---- helpers ----

    private fun started(start: EditStart): EditSession =
        assertInstanceOf(EditStart.Started::class.java, start).session

    private fun service(
        environment: StubEditEnvironment = StubEditEnvironment(),
        adapter: ProtocolAdapter = RecordingAdapter()
    ) = EditSessionService({ gateway(adapter) }, environment)

    private fun gateway(adapter: ProtocolAdapter) =
        OperationGateway(mapOf(NacosApiGeneration.V1 to adapter))

    private fun configuration(tenantId: String? = "dev") = NacosConfiguration(
        dataId = "app.yaml",
        group = "G",
        tenantId = tenantId,
        content = "original",
        type = "yaml",
        md5 = "base-md5"
    )

    private fun profile(id: String = "p1", writeIntent: Boolean = true) = EnvironmentProfile(
        id = id,
        displayName = id,
        canonicalEndpoint = "https://nacos.example",
        writeIntent = writeIntent
    )

    /**
     * The project selection an edit starts against. Capturing a target is the
     * one place a credential is read, and it happens per operation — the
     * session never holds one.
     */
    private inner class StubEditEnvironment(
        /** What the tool window has selected now — a draft outlives changes to it. */
        var selected: EnvironmentProfile? = profile(),
        var credential: String = "",
        private val captureFailure: Throwable? = null,
        var accessRevision: Long = 1
    ) : EditEnvironment {
        /** Every profile this environment knows, by id, as the settings store holds them. */
        val profiles = mutableMapOf("p1" to profile())

        /** (profileId, namespaceId) of every capture this environment served. */
        val captures = mutableListOf<Pair<String, String>>()

        val identity: AccessIdentity get() = identityAt(accessRevision)

        override fun selectedProfile(): EnvironmentProfile? = selected

        override fun profile(profileId: String): EnvironmentProfile? = profiles[profileId]

        override suspend fun captureTarget(profileId: String, namespaceId: String): Result<OperationTarget> {
            captureFailure?.let { return Result.failure(it) }
            captures += profileId to namespaceId
            return Result.success(target(namespaceId))
        }

        fun target(namespaceId: String = "dev"): OperationTarget = OperationTarget(
            NacosOperationContext(
                identity = identityAt(accessRevision),
                endpoint = CanonicalNacosEndpoint.parse("https://nacos.example").getOrThrow(),
                credential = CredentialSnapshot(credential),
                authMode = AuthMode.ANONYMOUS,
                profileRevision = 1,
                accessRevision = accessRevision,
                resolvedGeneration = NacosApiGeneration.V1
            ),
            namespaceId
        )

        private fun identityAt(revision: Long) = AccessIdentity.ofProfile(
            profileId = "p1",
            accessRevision = revision,
            canonicalEndpoint = "https://nacos.example",
            resolvedGeneration = NacosApiGeneration.V1,
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        )
    }

    /** Serves the baseline on preflight and the command's content on read-back. */
    private class RecordingAdapter(
        private val remoteContent: String = "original",
        private val remoteMd5: String = "base-md5"
    ) : ProtocolAdapter {
        var publishCalls = 0
        var lastPublishTarget: OperationTarget? = null
        private var published: PublishCommand? = null

        override suspend fun probe(target: OperationTarget) = Result.success(Unit)

        override suspend fun listSummaries(target: OperationTarget, query: SummaryQuery) =
            Result.success(SummaryPage(0, 1, 0, emptyList()))

        override suspend fun readDetail(
            target: OperationTarget,
            coordinate: ConfigurationCoordinate
        ): Result<NacosConfiguration?> = Result.success(
            published?.let {
                NacosConfiguration(
                    dataId = it.dataId, group = it.group, tenantId = target.namespaceId,
                    content = it.content, type = it.type, md5 = "published-md5"
                )
            } ?: NacosConfiguration(
                dataId = coordinate.dataId, group = coordinate.group, tenantId = target.namespaceId,
                content = remoteContent, type = "yaml", md5 = remoteMd5
            )
        )

        override suspend fun publish(target: OperationTarget, command: PublishCommand): Result<PublishOutcome> {
            publishCalls++
            lastPublishTarget = target
            published = command
            return Result.success(PublishOutcome.Written("true"))
        }
    }
}
