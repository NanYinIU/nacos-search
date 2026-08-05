package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.models.CacheAge
import com.nanyin.nacos.search.models.CacheConfidence
import com.nanyin.nacos.search.models.DataSource
import com.nanyin.nacos.search.models.DatasetCompleteness
import com.nanyin.nacos.search.models.DatasetConfirmation
import com.nanyin.nacos.search.models.NacosConfiguration
import com.nanyin.nacos.search.services.CacheService
import com.nanyin.nacos.search.services.operations.EditBinding
import com.nanyin.nacos.search.services.operations.EditSession
import com.nanyin.nacos.search.services.operations.EditStart
import com.nanyin.nacos.search.services.operations.PublishDiff
import com.nanyin.nacos.search.services.operations.PublishNamedTarget
import com.nanyin.nacos.search.services.operations.PublishResult
import com.nanyin.nacos.search.services.operations.PublishState
import com.nanyin.nacos.search.services.operations.WriteIntent
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.ConfigurationRequired
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives every member of the detail view's closed [DetailViewState] set through
 * [DetailPresentation] (issue #81). No Swing, no platform fixture.
 */
class DetailPresentationTest {

    private val message: (String, Array<out Any>) -> String = { key, params ->
        if (params.isEmpty()) key else "$key(${params.joinToString()})"
    }

    @Test
    fun `empty and loading are distinct closed members`() {
        assertEquals(DetailViewState.Empty, DetailPresentation.empty())
        assertEquals(DetailViewState.Loading, DetailPresentation.loading())
    }

    @Test
    fun `body carries cache age dimensions without resolving wording`() {
        val confidence = DetailPresentation.confidenceFromCache(
            CacheService.DetailFreshness.DEEP_STALE,
            fetchedAtMillis = 42L
        )
        val state = DetailPresentation.body(config(), confidence, overlay = DetailOverlay.Refreshing)

        assertInstanceOf(DetailViewState.Body::class.java, state)
        assertEquals(DataSource.CACHE, confidence.source)
        assertEquals(DatasetConfirmation.UNCONFIRMED, confidence.confirmation)
        assertEquals(CacheAge.DEEP_STALE, confidence.age)
        assertEquals(DetailOverlay.Refreshing, state.overlay)
        assertEquals(
            "config.detail.cache.refreshing",
            DetailCopy.overlayMessage(state.overlay, message)
        )
    }

    @Test
    fun `write-intent-disabled maps from EditStart WritesWithheld`() {
        val state = DetailPresentation.fromEditStart(
            EditStart.WritesWithheld(WriteIntent.Cause.NOT_OPTED_IN)
        )
        assertInstanceOf(DetailViewState.WriteIntentDisabled::class.java, state)
        assertEquals(
            WriteIntent.Cause.NOT_OPTED_IN,
            (state as DetailViewState.WriteIntentDisabled).cause
        )
        assertEquals(
            "config.detail.publish.writes.disabled",
            DetailCopy.writeIntentMessage(state.cause, message)
        )
    }

    @Test
    fun `configuration-required maps from typed ConfigurationRequired`() {
        val state = DetailPresentation.fromFailure(
            ConfigurationRequired(listOf("Select a Nacos environment profile")),
            fallbackMessage = "ignored"
        )
        assertInstanceOf(DetailViewState.ConfigurationRequired::class.java, state)
        assertEquals(
            "Select a Nacos environment profile",
            (state as DetailViewState.ConfigurationRequired).detail
        )
    }

    @Test
    fun `awaiting confirmation carries the diff and named target`() {
        val diff = PublishDiff("base", "draft")
        val target = namedTarget()
        val state = DetailPresentation.fromPublishState(
            PublishState.AwaitingConfirmation(diff, target)
        )
        assertInstanceOf(DetailViewState.AwaitingConfirmation::class.java, state)
        val awaiting = state as DetailViewState.AwaitingConfirmation
        assertEquals(diff, awaiting.diff)
        assertEquals(target, awaiting.namedTarget)
    }

    @Test
    fun `publishing and verifying are first-class states`() {
        assertEquals(
            DetailViewState.Publishing,
            DetailPresentation.fromPublishState(PublishState.Publishing)
        )
        assertEquals(
            DetailViewState.Verifying,
            DetailPresentation.fromPublishState(PublishState.Verifying)
        )
    }

    @Test
    fun `verified carries the verified detail`() {
        val verified = config(content = "published")
        val state = DetailPresentation.fromPublishResult(
            PublishResult(PublishState.Verified, isDirty = false, verifiedDetail = verified),
            draftContent = "draft"
        )
        assertInstanceOf(DetailViewState.Verified::class.java, state)
        assertEquals(verified, (state as DetailViewState.Verified).configuration)
    }

    @Test
    fun `conflict carries remote and draft content`() {
        val state = DetailPresentation.fromPublishState(
            PublishState.RemoteConflict("remote", "md5"),
            draftContent = "draft"
        )
        assertInstanceOf(DetailViewState.Conflict::class.java, state)
        val conflict = state as DetailViewState.Conflict
        assertEquals("remote", conflict.remoteContent)
        assertEquals("draft", conflict.draftContent)
    }

    @Test
    fun `server-state-unknown is a closed member`() {
        assertEquals(
            DetailViewState.ServerStateUnknown,
            DetailPresentation.fromPublishState(PublishState.ServerStateUnknown)
        )
    }

    @Test
    fun `failed maps load errors without a catch-all`() {
        val state = DetailPresentation.fromFailure(
            RuntimeException("boom"),
            fallbackMessage = "fallback"
        )
        assertInstanceOf(DetailViewState.Failed::class.java, state)
        val failed = state as DetailViewState.Failed
        assertEquals(DetailPresentation.LOAD_FAILED_TITLE_KEY, failed.titleKey)
        assertEquals("boom", failed.detail)
        assertEquals(
            "config.detail.failed: boom",
            DetailCopy.failedBody(failed, message)
        )
    }

    @Test
    fun `publish TargetDeleted PermissionDenied ReadOnly Dirty map to Failed not a catch-all`() {
        assertInstanceOf(
            DetailViewState.Failed::class.java,
            DetailPresentation.fromPublishState(PublishState.TargetDeleted)
        )
        assertInstanceOf(
            DetailViewState.Failed::class.java,
            DetailPresentation.fromPublishState(PublishState.PermissionDenied)
        )
        assertInstanceOf(
            DetailViewState.Failed::class.java,
            DetailPresentation.fromPublishState(PublishState.ReadOnly("reason"))
        )
        assertInstanceOf(
            DetailViewState.Failed::class.java,
            DetailPresentation.fromPublishState(PublishState.Dirty)
        )
    }

    @Test
    fun `stale is a closed member`() {
        assertEquals(DetailViewState.Stale, DetailPresentation.stale())
    }

    @Test
    fun `every closed state is constructible and distinct`() {
        val states: List<DetailViewState> = listOf(
            DetailViewState.Empty,
            DetailViewState.Loading,
            DetailViewState.Body(config(), CacheConfidence.remoteConfirmed(1L, DatasetCompleteness.COMPLETE)),
            DetailViewState.WriteIntentDisabled(WriteIntent.Cause.NO_PROFILE_SELECTED),
            DetailViewState.ConfigurationRequired("need config"),
            DetailViewState.AwaitingConfirmation(PublishDiff("a", "b"), namedTarget()),
            DetailViewState.Publishing,
            DetailViewState.Verifying,
            DetailViewState.Verified(config()),
            DetailViewState.Conflict("remote", "draft"),
            DetailViewState.ServerStateUnknown,
            DetailViewState.Failed(titleKey = "t", detail = "d"),
            DetailViewState.Stale
        )
        assertEquals(13, states.map { it::class }.toSet().size)
    }

    @Test
    fun `fromEditStart Started yields an editing Body`() {
        val session = EditSession(
            binding = EditBinding.of(
                profileId = "p1",
                identity = identity(),
                namespaceId = "dev",
                dataId = "app.yaml",
                group = "G"
            ),
            baselineContent = "base",
            baselineMd5 = "md5",
            baselineType = "yaml",
            baselineAppName = null,
            baselineDesc = null,
            baselineConfigTags = null,
            draftContent = "base",
            writeIntent = WriteIntent.Granted
        )
        val state = DetailPresentation.fromEditStart(EditStart.Started(session))
        assertInstanceOf(DetailViewState.Body::class.java, state)
        val body = state as DetailViewState.Body
        assertTrue(body.editing)
        assertEquals("app.yaml", body.configuration.dataId)
    }

    @Test
    fun `authoritative not-found keeps the cached body with Deleted overlay`() {
        val body = DetailPresentation.fromAuthoritativeNotFound(
            config(),
            DetailPresentation.confidenceFromCache(CacheService.DetailFreshness.DEEP_STALE)
        )
        assertEquals(DetailOverlay.Deleted, body.overlay)
        assertEquals(DatasetConfirmation.REFRESH_FAILED, body.confidence?.confirmation)
        assertNull(DetailCopy.overlayMessage(DetailOverlay.None, message))
        assertEquals("config.detail.cache.deleted", DetailCopy.overlayMessage(body.overlay, message))
    }

    private fun config(content: String = "k=v") = NacosConfiguration(
        dataId = "app.yaml",
        group = "G",
        tenantId = "dev",
        content = content,
        type = "yaml"
    )

    private fun namedTarget() = PublishNamedTarget(
        profileId = "p1",
        profileDisplayName = "Local",
        endpoint = "https://nacos.example",
        namespaceId = "dev",
        dataId = "app.yaml",
        group = "G"
    )

    private fun identity(): AccessIdentity {
        val endpoint = com.nanyin.nacos.search.models.CanonicalNacosEndpoint
            .parse("https://nacos.example").getOrThrow()
        return AccessIdentity.ofProfile(
            profileId = "p1",
            accessRevision = 1,
            canonicalEndpoint = endpoint.value,
            resolvedGeneration = NacosApiGeneration.V1,
            authMode = AuthMode.ANONYMOUS,
            principal = "<anonymous>"
        )
    }
}
