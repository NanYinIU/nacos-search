package com.nanyin.nacos.search.services

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source wiring for issue #193: non-cache marker inputs must request a
 * coalesced [NavigationIndexRefreshService.requestGutterPass] rather than a
 * synchronous [com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.restart].
 *
 * Call sites that are hard to drive under the unit-test harness are guarded
 * here so a later edit cannot drop the pass request from one sibling path
 * while leaving another covered.
 */
class MarkerInputGutterPassWiringTest {

    @Test
    fun `namespace switch requests a gutter pass before preheat`() {
        val window = File("src/main/kotlin/com/nanyin/nacos/search/ui/NacosSearchWindow.kt").readText()
        val openSelection = extractFunction(window, "openNamespaceSelection")
        assertTrue(
            openSelection.contains("requestMarkerInputGutterPass()"),
            "openNamespaceSelection must request a pass so a cached-index early return still re-analyzes"
        )
        // Pass before preheat: the early-return branch of preheat must not be
        // the only path that can re-analyze.
        val passAt = openSelection.indexOf("requestMarkerInputGutterPass()")
        val preheatAt = openSelection.indexOf("preheatNamespaceIndex")
        assertTrue(passAt in 0 until preheatAt, "pass request must run before preheat")
    }

    @Test
    fun `environment switch requests a gutter pass after adopting the new selection`() {
        val window = File("src/main/kotlin/com/nanyin/nacos/search/ui/NacosSearchWindow.kt").readText()
        val restart = extractFunction(window, "restartUnderSelectedEnvironment")
        assertTrue(
            restart.contains("requestMarkerInputGutterPass()"),
            "environment switch must request a pass; it does not go through openNamespaceSelection"
        )
        assertTrue(
            restart.contains("openSelectedNamespace()"),
            "environment switch still opens the selected namespace before the pass"
        )
    }

    @Test
    fun `preference-only changes use the coalesced pass not a synchronous restart`() {
        val window = File("src/main/kotlin/com/nanyin/nacos/search/ui/NacosSearchWindow.kt").readText()
        val prefs = extractFunction(window, "handlePreferencesChanged")
        assertTrue(prefs.contains("requestMarkerInputGutterPass()"))
        assertFalse(
            prefs.contains("DaemonCodeAnalyzer"),
            "synchronous DaemonCodeAnalyzer.restart is the flicker source; use requestGutterPass"
        )
    }

    @Test
    fun `Find Usages single-hit and chooser branches both call navigateToCodeUsage`() {
        val detail = File("src/main/kotlin/com/nanyin/nacos/search/ui/ConfigDetailPanel.kt").readText()
        val action = extractClass(detail, "KeyFindUsagesAction")
        // Both branches must name the shared helper — a direct navigate(true)
        // on either sibling would skip the gutter pass.
        val singleHit = action.contains("1 ->") &&
            action.substringAfter("1 ->").substringBefore("else").contains("navigateToCodeUsage")
        val multiHit = action.contains("setItemChosenCallback") &&
            action.substringAfter("setItemChosenCallback").contains("navigateToCodeUsage")
        assertTrue(singleHit, "single-hit Find Usages must call navigateToCodeUsage")
        assertTrue(multiHit, "multi-chooser Find Usages must call navigateToCodeUsage")
    }

    @Test
    fun `preheat early-return does not request a second pass`() {
        val window = File("src/main/kotlin/com/nanyin/nacos/search/ui/NacosSearchWindow.kt").readText()
        val preheat = extractFunction(window, "preheatNamespaceIndex")
        val earlyReturn = preheat.substringBefore("requestSwitchedNamespaceIndex")
        assertFalse(
            earlyReturn.contains("requestGutterPass") || earlyReturn.contains("requestMarkerInputGutterPass"),
            "cached-index early return must not double-restart; openNamespaceSelection already requested"
        )
        assertTrue(
            preheat.contains(".refresh("),
            "remote index load still refreshes navigation after new keys land"
        )
    }

    private fun extractFunction(source: String, name: String): String {
        val marker = "fun $name"
        val start = source.indexOf(marker)
        assertTrue(start >= 0, "function $name not found")
        return balancedBlock(source, start)
    }

    private fun extractClass(source: String, name: String): String {
        val marker = "class $name"
        val start = source.indexOf(marker)
        assertTrue(start >= 0, "class $name not found")
        return balancedBlock(source, start)
    }

    private fun balancedBlock(source: String, from: Int): String {
        val brace = source.indexOf('{', from)
        assertTrue(brace >= 0, "block start not found")
        var depth = 0
        for (i in brace until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(from, i + 1)
                }
            }
        }
        error("unbalanced braces from $from")
    }
}
