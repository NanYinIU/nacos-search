package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.CanonicalNacosEndpoint
import com.nanyin.nacos.search.models.EnvironmentProfile
import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.models.NacosApiPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OperationContextSnapshotCacheTest {

    private fun profile(
        id: String,
        profileRevision: Long = 1,
        accessRevision: Long = 1
    ): EnvironmentProfile = EnvironmentProfile(
        id = id,
        displayName = id,
        canonicalEndpoint = "https://$id.example",
        apiPolicy = NacosApiPolicy.V1,
        authMode = AuthMode.ANONYMOUS,
        principal = "",
        profileRevision = profileRevision,
        accessRevision = accessRevision
    )

    private fun context(
        profileId: String,
        profileRevision: Long = 1,
        accessRevision: Long = 1
    ): NacosOperationContext {
        val endpoint = CanonicalNacosEndpoint.parse("https://$profileId.example").getOrThrow()
        return NacosOperationContext(
            identity = AccessIdentity.ofProfile(
                profileId = profileId,
                accessRevision = accessRevision,
                canonicalEndpoint = endpoint.value,
                resolvedGeneration = NacosApiGeneration.V1,
                authMode = AuthMode.ANONYMOUS,
                principal = "<anonymous>"
            ),
            endpoint = endpoint,
            credential = CredentialSnapshot(""),
            authMode = AuthMode.ANONYMOUS,
            profileRevision = profileRevision,
            accessRevision = accessRevision,
            resolvedGeneration = NacosApiGeneration.V1
        )
    }

    @Test
    fun `late capture for profile A does not overwrite a newer capture for profile B`() {
        val cache = OperationContextSnapshotCache()

        // Rapid switch A → B: both refreshes start; A finishes last.
        val epochA = cache.beginRefresh()
        val epochB = cache.beginRefresh()
        val contextA = context("env-a")
        val contextB = context("env-b")
        val profileA = profile("env-a")
        val profileB = profile("env-b")

        // B completes first and publishes against the current selection B.
        assertTrue(
            cache.publishIfCurrent(
                epochAtStart = epochB,
                capturedProfileId = "env-b",
                selectedProfileId = "env-b",
                selectedProfile = profileB,
                context = contextB
            )
        )
        assertEquals(contextB, cache.resolve("env-b", profileB))

        // A completes later — must not clobber B even if it still has A's context.
        assertFalse(
            cache.publishIfCurrent(
                epochAtStart = epochA,
                capturedProfileId = "env-a",
                selectedProfileId = "env-b",
                selectedProfile = profileB,
                context = contextA
            )
        )
        assertEquals(contextB, cache.resolve("env-b", profileB))
        assertNull(cache.resolve("env-a", profileA))
    }

    @Test
    fun `publish is rejected when selection moved away during capture`() {
        val cache = OperationContextSnapshotCache()
        val epoch = cache.beginRefresh()
        val contextA = context("env-a")

        // Started for A, but by publish time the user is on B.
        assertFalse(
            cache.publishIfCurrent(
                epochAtStart = epoch,
                capturedProfileId = "env-a",
                selectedProfileId = "env-b",
                selectedProfile = profile("env-b"),
                context = contextA
            )
        )
        assertNull(cache.currentSnapshot())
    }

    @Test
    fun `resolve rejects snapshot when profile revision advanced`() {
        val cache = OperationContextSnapshotCache()
        val epoch = cache.beginRefresh()
        val contextA = context("env-a", profileRevision = 1, accessRevision = 1)
        assertTrue(
            cache.publishIfCurrent(
                epochAtStart = epoch,
                capturedProfileId = "env-a",
                selectedProfileId = "env-a",
                selectedProfile = profile("env-a", profileRevision = 1, accessRevision = 1),
                context = contextA
            )
        )

        // Credential/endpoint edit bumps the profile revision while the
        // cached snapshot still names the old revision.
        assertNull(
            cache.resolve(
                "env-a",
                profile("env-a", profileRevision = 2, accessRevision = 1)
            )
        )
        // Access revision (e.g. password change) likewise invalidates.
        assertNull(
            cache.resolve(
                "env-a",
                profile("env-a", profileRevision = 1, accessRevision = 2)
            )
        )
    }

    @Test
    fun `invalidate drops the snapshot and rejects in-flight publishes`() {
        val cache = OperationContextSnapshotCache()
        val epoch = cache.beginRefresh()
        val contextA = context("env-a")
        assertTrue(
            cache.publishIfCurrent(
                epochAtStart = epoch,
                capturedProfileId = "env-a",
                selectedProfileId = "env-a",
                selectedProfile = profile("env-a"),
                context = contextA
            )
        )

        cache.invalidate()
        assertNull(cache.currentSnapshot())
        assertNull(cache.resolve("env-a", profile("env-a")))

        // In-flight capture that started before invalidate must not reinstall.
        assertFalse(
            cache.publishIfCurrent(
                epochAtStart = epoch,
                capturedProfileId = "env-a",
                selectedProfileId = "env-a",
                selectedProfile = profile("env-a"),
                context = contextA
            )
        )
        assertNull(cache.currentSnapshot())
    }
}
