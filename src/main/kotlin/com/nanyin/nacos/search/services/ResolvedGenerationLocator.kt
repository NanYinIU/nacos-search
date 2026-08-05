package com.nanyin.nacos.search.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.models.NacosApiGeneration

/**
 * The non-secret keys an environment profile's cache is addressed by. They are
 * exactly the inputs both credential-free generation sources are keyed on, and
 * nothing else — reading a credential to derive them is what this whole path
 * exists to avoid.
 */
data class ProfileAccessKeys(
    val profileId: String,
    val profileRevision: Long,
    val accessRevision: Long,
    val canonicalEndpoint: String
)

/**
 * Locates the resolved API generation an environment's cache lives under, for a
 * caller that must not read a credential.
 *
 * The generation is part of the access identity, so a caller that cannot name it
 * addresses a key space of its own. For a profile whose API policy is AUTO the
 * generation is not in the profile — it is resolved per operation — and the
 * code-navigation, UI, and local-search paths run where reading `PasswordSafe`
 * is forbidden (ADR-0039). Both sources below are credential-free, so those
 * paths can name the same generation the operation layer resolved:
 *
 * - the generation this project session resolved, valid only while the profile
 *   id, profile revision, and access revision it was resolved under still hold;
 * - failing that, the last-known generation persisted by profile id, access
 *   revision, and canonical endpoint, whose stated purpose is to locate the same
 *   identity's cache in a later session (ADR-0034).
 *
 * When neither has a value the answer is [NacosApiGeneration.UNKNOWN] and the
 * read is a miss. That is not a gap to paper over: without a matching last-known
 * value AUTO exposes no cache, and a caller that wrote its own copy into the
 * unknown-generation space to make the read succeed would be publishing data no
 * successful detection ever confirmed.
 *
 * Locating a generation authorises nothing. It names a cache coordinate; formal
 * detection still runs before any remote operation, and a newly resolved
 * different generation switches identity and hides what this located.
 */
class ResolvedGenerationLocator(
    private val sessionResolved: (ProfileAccessKeys) -> NacosApiGeneration?,
    private val lastKnown: (ProfileAccessKeys) -> NacosApiGeneration?
) {
    fun locate(keys: ProfileAccessKeys): NacosApiGeneration =
        sessionResolved(keys)?.takeIf { it.isResolved() }
            ?: lastKnown(keys)?.takeIf { it.isResolved() }
            ?: NacosApiGeneration.UNKNOWN

    companion object {
        /**
         * For a hot path that belongs to one project: that project's own session
         * answers first. Project sessions are independent (ADR-0004), so another
         * project's resolution must never stand in for this one's.
         */
        fun forProject(project: Project): ResolvedGenerationLocator = ResolvedGenerationLocator(
            sessionResolved = { keys ->
                runCatching {
                    project.takeUnless { it.isDisposed }
                        ?.getService(ProjectSessionEpochs::class.java)
                        ?.resolvedGenerationIfCompatible(
                            profileId = keys.profileId,
                            profileRevision = keys.profileRevision,
                            accessRevision = keys.accessRevision
                        )
                }.getOrNull()
            },
            lastKnown = ::persistedLastKnownGeneration
        )

        /**
         * For an application-level reader with no project of its own — startup
         * warm-up, local search, the API service's clear gesture. It asks the
         * session of whichever open project selected the profile, which is the
         * same lookup the operation layer uses to decide where to commit a
         * resolution, so the two agree by construction.
         */
        fun forSelectedProfile(): ResolvedGenerationLocator = ResolvedGenerationLocator(
            sessionResolved = { keys ->
                runCatching {
                    ProjectSessionEpochs.findForProfile(keys.profileId)
                        ?.resolvedGenerationIfCompatible(
                            profileId = keys.profileId,
                            profileRevision = keys.profileRevision,
                            accessRevision = keys.accessRevision
                        )
                }.getOrNull()
            },
            lastKnown = ::persistedLastKnownGeneration
        )

        private fun persistedLastKnownGeneration(keys: ProfileAccessKeys): NacosApiGeneration? = runCatching {
            ApplicationManager.getApplication()
                ?.getService(LastKnownGenerationStore::class.java)
                ?.get(
                    LastKnownGenerationStore.Key(
                        profileId = keys.profileId,
                        accessRevision = keys.accessRevision,
                        canonicalEndpoint = keys.canonicalEndpoint
                    )
                )
        }.getOrNull()

        private fun NacosApiGeneration.isResolved(): Boolean =
            this == NacosApiGeneration.V1 || this == NacosApiGeneration.V3
    }
}

/**
 * Addresses [this] under the generation [locator] finds, when the profile's API
 * policy left it unresolved.
 *
 * An identity that already names V1 or V3 is returned untouched: a locked policy
 * is the answer, and convergence is achieved by giving the hot path the resolved
 * value, never by letting a lookup override or drop what the key already says.
 * When nothing is found the identity keeps its unknown generation, so the read
 * misses rather than silently borrowing another key space's data.
 */
internal fun AccessIdentity.locatedUnder(
    locator: ResolvedGenerationLocator,
    profileRevision: Long
): AccessIdentity {
    if (resolvedGeneration != NacosApiGeneration.UNKNOWN) return this
    val located = locator.locate(
        ProfileAccessKeys(
            profileId = profileId,
            profileRevision = profileRevision,
            accessRevision = accessRevision,
            canonicalEndpoint = canonicalEndpoint
        )
    )
    return if (located == NacosApiGeneration.UNKNOWN) this else copy(resolvedGeneration = located)
}
