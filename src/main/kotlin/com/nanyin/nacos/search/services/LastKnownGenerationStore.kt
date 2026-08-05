package com.nanyin.nacos.search.services

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.nanyin.nacos.search.models.NacosApiGeneration
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists the last successfully resolved API generation keyed by
 * profile id + access revision + canonical endpoint for offline bootstrap.
 * Never written for entombed profiles.
 *
 * The live map is concurrent because the two sides run on different threads:
 * resolutions are committed from IO while the cache hot paths read it from the
 * EDT and the highlighter to locate their key space (ADR-0053). The serialized
 * [State] bean stays a plain map — it is only touched at save/load.
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

    private val entries = ConcurrentHashMap<String, String>()
    private var state = State()

    override fun getState(): State {
        state.entries = entries.toMutableMap()
        return state
    }

    override fun loadState(state: State) {
        this.state = state
        entries.clear()
        entries.putAll(state.entries)
    }

    fun get(key: Key): NacosApiGeneration? {
        val raw = entries[key.storageKey()] ?: return null
        return runCatching { NacosApiGeneration.valueOf(raw) }.getOrNull()?.takeIf { it.isResolved() }
    }

    fun put(key: Key, generation: NacosApiGeneration) {
        if (!generation.isResolved()) return
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
        entries[key.storageKey()] = generation.name
    }

    fun clearProfile(profileId: String) {
        val prefix = "$profileId|"
        entries.keys.removeIf { it.startsWith(prefix) }
    }
}
