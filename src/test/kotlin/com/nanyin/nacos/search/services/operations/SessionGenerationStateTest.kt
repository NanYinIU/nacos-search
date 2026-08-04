package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosApiGeneration
import com.nanyin.nacos.search.settings.AuthMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

class SessionGenerationStateTest {

    @Test
    fun `resolved generation is reused for matching profile and revisions`() {
        val epoch = AtomicLong(1)
        val state = SessionGenerationState(currentEpoch = { epoch.get() })
        val capture = capture(sessionEpoch = 1)

        assertTrue(state.tryCommit(NacosApiGeneration.V3, capture, live(capture)))
        assertEquals(
            NacosApiGeneration.V3,
            state.resolvedIfCompatible("profile-a", profileRevision = 2, accessRevision = 3)
        )
    }

    @Test
    fun `profile revision mismatch refuses reuse and requires a new probe`() {
        val epoch = AtomicLong(1)
        val state = SessionGenerationState(currentEpoch = { epoch.get() })
        val capture = capture(sessionEpoch = 1, profileRevision = 2)
        assertTrue(state.tryCommit(NacosApiGeneration.V3, capture, live(capture)))

        assertNull(state.resolvedIfCompatible("profile-a", profileRevision = 99, accessRevision = 3))
    }

    @Test
    fun `access revision mismatch refuses reuse`() {
        val epoch = AtomicLong(1)
        val state = SessionGenerationState(currentEpoch = { epoch.get() })
        val capture = capture(sessionEpoch = 1)
        assertTrue(state.tryCommit(NacosApiGeneration.V3, capture, live(capture)))

        assertNull(state.resolvedIfCompatible("profile-a", profileRevision = 2, accessRevision = 99))
    }

    @Test
    fun `assigning a different generation advances the session epoch`() {
        val epoch = AtomicLong(5)
        var bumps = 0
        val state = SessionGenerationState(
            currentEpoch = { epoch.get() },
            onGenerationChanged = { epoch.incrementAndGet(); bumps++ }
        )
        val first = capture(sessionEpoch = 5)
        assertTrue(state.tryCommit(NacosApiGeneration.V3, first, live(first)))
        assertEquals(0, bumps)

        // Same epoch still; commit a different generation.
        val second = capture(sessionEpoch = 5)
        assertTrue(state.tryCommit(NacosApiGeneration.V1, second, live(second)))
        assertEquals(1, bumps)
        assertEquals(6, epoch.get())
    }

    @Test
    fun `stale session epoch does not commit a shared probe result`() {
        val epoch = AtomicLong(1)
        val state = SessionGenerationState(currentEpoch = { epoch.get() })
        epoch.set(2) // advanced after the consumer captured epoch 1
        val capture = capture(sessionEpoch = 1)

        assertFalse(state.tryCommit(NacosApiGeneration.V3, capture, live(capture)))
        assertNull(state.peekResolvedGeneration())
    }

    @Test
    fun `live profile revision mismatch does not commit a shared probe result`() {
        val epoch = AtomicLong(1)
        val state = SessionGenerationState(currentEpoch = { epoch.get() })
        val capture = capture(sessionEpoch = 1, profileRevision = 2)
        val live = SessionGenerationState.LiveIdentityKeys(
            profileId = capture.profileId,
            profileRevision = 99,
            accessRevision = capture.accessRevision
        )

        assertFalse(state.tryCommit(NacosApiGeneration.V3, capture, live))
        assertNull(state.peekResolvedGeneration())
    }

    @Test
    fun `live access revision mismatch does not commit a shared probe result`() {
        val epoch = AtomicLong(1)
        val state = SessionGenerationState(currentEpoch = { epoch.get() })
        val capture = capture(sessionEpoch = 1, accessRevision = 3)
        val live = SessionGenerationState.LiveIdentityKeys(
            profileId = capture.profileId,
            profileRevision = capture.profileRevision,
            accessRevision = 99
        )

        assertFalse(state.tryCommit(NacosApiGeneration.V3, capture, live))
        assertNull(state.peekResolvedGeneration())
    }

    @Test
    fun `entombed profile does not commit a shared probe result`() {
        val epoch = AtomicLong(1)
        val state = SessionGenerationState(
            currentEpoch = { epoch.get() },
            isEntombed = { it == "profile-a" }
        )
        val capture = capture(sessionEpoch = 1)

        assertFalse(state.tryCommit(NacosApiGeneration.V3, capture, live(capture)))
        assertNull(state.peekResolvedGeneration())
    }

    @Test
    fun `resolved generation is never part of a persisted structure`() {
        // SessionGenerationState has no PersistentStateComponent ancestry and no
        // serialization hooks — assigning a generation only mutates memory.
        val epoch = AtomicLong(0)
        val state = SessionGenerationState(currentEpoch = { epoch.get() })
        val capture = capture(sessionEpoch = 0)
        state.tryCommit(NacosApiGeneration.V3, capture, live(capture))
        state.clear()
        assertNull(state.peekResolvedGeneration())
    }

    private fun live(c: GenerationSessionCapture) = SessionGenerationState.LiveIdentityKeys(
        c.profileId, c.profileRevision, c.accessRevision
    )

    private fun capture(
        sessionEpoch: Long,
        profileRevision: Long = 2,
        accessRevision: Long = 3
    ) = GenerationSessionCapture(
        profileId = "profile-a",
        profileRevision = profileRevision,
        accessRevision = accessRevision,
        sessionEpoch = sessionEpoch,
        canonicalEndpoint = "https://nacos.example",
        authMode = AuthMode.ANONYMOUS,
        principal = "<anonymous>"
    )
}
