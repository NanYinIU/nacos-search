package com.nanyin.nacos.search.bundle

import com.nanyin.nacos.search.settings.AuthMode
import com.nanyin.nacos.search.settings.AuthStrategyFormPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Guards the two properties a literal search over `src/main` cannot see (issue #205):
 * that the locales carry the same key set, and that the keys
 * [AuthStrategyFormPolicy] interpolates at runtime exist. An interpolated key
 * deleted as "unreferenced" produces no compile error — only a missing Settings
 * label.
 */
class NacosSearchBundleKeysTest {

    @Test
    fun `both locale bundles hold the same key set`() {
        assertEquals(keysOf(EN), keysOf(ZH))
    }

    @Test
    fun `every auth strategy composes a label and a tooltip that both bundles hold`() {
        for (bundle in listOf(EN, ZH)) {
            val keys = keysOf(bundle)
            for (mode in AuthMode.values()) {
                for (key in listOf(AuthStrategyFormPolicy.labelKey(mode), AuthStrategyFormPolicy.tooltipKey(mode))) {
                    assertTrue(key in keys, "$bundle is missing $key, composed for AuthMode.$mode")
                }
            }
        }
    }

    /** Reads as UTF-8 — the encoding the Chinese bundle is stored in — not `load`'s ISO-8859-1 default. */
    private fun keysOf(bundle: String): Set<String> =
        checkNotNull(javaClass.getResourceAsStream(bundle)) { "$bundle not on the test classpath" }
            .reader(Charsets.UTF_8)
            .use { Properties().apply { load(it) } }
            .stringPropertyNames()
            .toSortedSet()
            // Two bundles that both failed to load would compare equal; neither test may pass on nothing.
            .also { assertTrue(it.isNotEmpty(), "$bundle loaded no keys") }

    private companion object {
        const val EN = "/messages/NacosSearchBundle.properties"
        const val ZH = "/messages/NacosSearchBundle_zh_CN.properties"
    }
}
