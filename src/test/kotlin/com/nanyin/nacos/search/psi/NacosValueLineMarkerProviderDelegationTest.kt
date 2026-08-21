package com.nanyin.nacos.search.psi

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Gutter adapter wiring for the shared 配置坐标 read (issue #236).
 * Behaviour lives in [com.nanyin.nacos.search.services.operations.ConfigurationCoordinateReadTest].
 */
class NacosValueLineMarkerProviderDelegationTest {

    @Test
    fun `click and refresh delegate the coordinate read and keep site 运行时格式`() {
        val source = locateMarkerSource().readText()
        assertFalse(source.contains("captureOperationContext"))
        assertFalse(source.contains("applyMutation"))
        assertFalse(source.contains("getConfiguration("))
        assertFalse(source.contains("writeDetail"))
        assertTrue(source.contains("readForNavigation"))
        assertTrue(source.contains("requestCoordinate"))
        assertTrue(source.contains("keysUnderRuntimeFormat"))
    }

    private fun locateMarkerSource(): Path {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            val candidate = dir.resolve(
                "src/main/kotlin/com/nanyin/nacos/search/psi/NacosValueLineMarkerProvider.kt"
            )
            if (Files.isRegularFile(candidate)) return candidate
            dir = dir.parent ?: return candidate
        }
        return Path.of("src/main/kotlin/com/nanyin/nacos/search/psi/NacosValueLineMarkerProvider.kt")
    }
}
