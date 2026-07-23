package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.NacosApiGeneration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LastKnownGenerationStoreTest {

    @Test
    fun `put and get round trip by profile revision and endpoint`() {
        val store = LastKnownGenerationStore()
        val key = LastKnownGenerationStore.Key("dev", 3, "https://nacos.example")

        store.put(key, NacosApiGeneration.V3)

        assertEquals(NacosApiGeneration.V3, store.get(key))
        assertNull(store.get(key.copy(accessRevision = 4)))
        assertNull(store.get(key.copy(canonicalEndpoint = "https://other.example")))
    }

    @Test
    fun `clearProfile removes every revision for that profile`() {
        val store = LastKnownGenerationStore()
        store.put(LastKnownGenerationStore.Key("dev", 1, "https://a"), NacosApiGeneration.V1)
        store.put(LastKnownGenerationStore.Key("dev", 2, "https://a"), NacosApiGeneration.V3)
        store.put(LastKnownGenerationStore.Key("prod", 1, "https://a"), NacosApiGeneration.V3)

        store.clearProfile("dev")

        assertNull(store.get(LastKnownGenerationStore.Key("dev", 1, "https://a")))
        assertNull(store.get(LastKnownGenerationStore.Key("dev", 2, "https://a")))
        assertEquals(NacosApiGeneration.V3, store.get(LastKnownGenerationStore.Key("prod", 1, "https://a")))
    }
}
