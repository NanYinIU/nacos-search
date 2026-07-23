package com.nanyin.nacos.search.services

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.nanyin.nacos.search.models.NacosApiGeneration

/**
 * Persists the last successfully resolved API generation keyed by
 * profile id + access revision + canonical endpoint for offline bootstrap.
 * Never written for entombed profiles.
 */
@Service(Service.Level.APP)
@State(name = "NacosLastKnownGeneration", storages = [Storage("nacos-last-known-generation.xml")])
class LastKnownGenerationStore : PersistentStateComponent<LastKnownGenerationStore.State> {

    data class Key(
        val profileId: String,
        val accessRevision: Long,
        val canonicalEndpoint: String
    ) {
        fun storageKey(): String = "$profileId|$accessRevision|$canonicalEndpoint"
    }

    data class State(
        var entries: MutableMap<String, String> = mutableMapOf()
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun get(key: Key): NacosApiGeneration? {
        val raw = state.entries[key.storageKey()] ?: return null
        return runCatching { NacosApiGeneration.valueOf(raw) }.getOrNull()
            ?.takeIf { it == NacosApiGeneration.V1 || it == NacosApiGeneration.V3 }
    }

    fun put(key: Key, generation: NacosApiGeneration) {
        if (generation != NacosApiGeneration.V1 && generation != NacosApiGeneration.V3) return
        val entombed = try {
            service<ProfileTombstoneRegistry>().isEntombed(
                com.nanyin.nacos.search.models.AccessIdentity.ofProfile(
                    profileId = key.profileId,
                    accessRevision = key.accessRevision,
                    canonicalEndpoint = key.canonicalEndpoint,
                    resolvedGeneration = generation,
                    authMode = com.nanyin.nacos.search.settings.AuthMode.ANONYMOUS,
                    principal = "<anonymous>"
                )
            )
        } catch (_: Exception) {
            false
        }
        if (entombed) return
        state.entries[key.storageKey()] = generation.name
    }

    fun clearProfile(profileId: String) {
        val prefix = "$profileId|"
        state.entries.keys.removeIf { it.startsWith(prefix) }
    }
}
