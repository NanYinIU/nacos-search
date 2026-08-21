package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.services.operations.RemoteOperationError
import com.nanyin.nacos.search.settings.ConfigurationRequired
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Shared typed-failure unwrap and title/detail assembly (issue #234).
 * One table per rule; expected values are literals, not recomputed.
 */
class FailureCopyTest {

    @Test
    fun `unwrap stops at typed 请求失败 and 需要配置`() {
        val required = ConfigurationRequired(listOf("Select a Nacos environment profile"))
        val connection = RemoteOperationError.Connection(RuntimeException("down"))
        val fifth = RuntimeException("leaf")
        val fourth = Exception("fourth", fifth)
        val chain = Exception("zero", Exception("one", Exception("two", Exception("three", fourth))))
        data class Case(val name: String, val error: Throwable?, val expected: Throwable?)
        val cases = listOf(
            Case("null", null, null),
            Case("untyped", RuntimeException("boom"), RuntimeException("boom")),
            Case("需要配置", required, required),
            Case("wrapped 需要配置", Exception("search failed", required), required),
            Case("请求失败", connection, connection),
            Case("wrapped 请求失败 keeps Connection", Exception("wrap", connection), connection),
            Case("depth bound stops on the fourth cause", chain, fourth)
        )
        for (case in cases) {
            val actual = FailureCopy.unwrap(case.error)
            when (val expected = case.expected) {
                null -> assertNull(actual, case.name)
                else -> {
                    assertTrue(actual === expected || actual?.javaClass == expected.javaClass, case.name)
                    assertEquals(expected.message, actual?.message, case.name)
                }
            }
        }
    }

    @Test
    fun `combine title and detail without duplicate English or Chinese punctuation`() {
        data class Case(val name: String, val title: String, val detail: String, val expected: String)
        val cases = listOf(
            Case("both", "Search failed", "Connection failed", "Search failed: Connection failed"),
            Case("duplicate title", "Search failed", "Search failed", "Search failed"),
            Case("english colon prefix", "Search failed", "Search failed: down", "Search failed: down"),
            Case("chinese colon prefix", "加载失败", "加载失败：超时", "加载失败：超时"),
            Case("empty detail", "Search failed", "  ", "Search failed"),
            Case("empty title", "", "boom", "boom"),
            Case("both empty uses fallback", "", "", "Failed")
        )
        for (case in cases) {
            assertEquals(case.expected, FailureCopy.combine(case.title, case.detail, "Failed"), case.name)
        }
    }

    @Test
    fun `no production source writes a legacy search-failed prefix`() {
        val prefixes = listOf(
            "搜索失败: ",
            "搜索过程中发生错误: ",
            "Search failed: ",
            "Error during search: "
        )
        val main = locateMainSources()
        val hits = mutableListOf<String>()
        Files.walk(main).use { stream ->
            stream.forEach { path ->
                if (path.extension != "kt") return@forEach
                val text = path.readText()
                for (prefix in prefixes) {
                    if (text.contains("\"$prefix\"") || text.contains("'$prefix'")) {
                        hits += "${path.toUnix()}: $prefix"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), hits)
    }

    private fun locateMainSources(): Path {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            val candidate = dir.resolve("src/main")
            if (Files.isDirectory(candidate)) return candidate
            dir = dir.parent ?: return candidate
        }
        return Path.of("src/main")
    }

    private fun Path.toUnix(): String = toString().replace('\\', '/')
}
