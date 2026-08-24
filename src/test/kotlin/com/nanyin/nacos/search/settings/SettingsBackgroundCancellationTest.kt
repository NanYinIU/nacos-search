package com.nanyin.nacos.search.settings

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

@TestApplication
class SettingsBackgroundCancellationTest {

    @Test
    fun `cancelling the progress indicator cancels the coroutine promptly`() {
        val indicator = EmptyProgressIndicator()
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Throwable?>()
        val hang = CompletableDeferred<Unit>()
        val thread = Thread({
            try {
                runBlockingWithProgressIndicator(indicator) {
                    started.complete(Unit)
                    hang.await()
                }
                finished.complete(null)
            } catch (error: Throwable) {
                finished.complete(error)
            }
        }, "settings-progress-cancel-test")
        thread.start()
        runBlocking {
            started.await()
            indicator.cancel()
            val error = withTimeout(1_000) { finished.await() }
            assertTrue(error is ProcessCanceledException, error.toString())
        }
        thread.join(1_000)
        assertFalse(thread.isAlive)
    }

    @Test
    fun `already cancelled indicator does not start the coroutine`() {
        val indicator = EmptyProgressIndicator()
        indicator.cancel()
        var started = false
        try {
            runBlockingWithProgressIndicator(indicator) {
                started = true
            }
            fail("expected ProcessCanceledException")
        } catch (_: ProcessCanceledException) {
            // expected
        }
        assertFalse(started)
    }

    @Test
    fun `successful work returns its value`() {
        val indicator = EmptyProgressIndicator()
        val value = runBlockingWithProgressIndicator(indicator) { 7 }
        assertEquals(7, value)
    }

    @Test
    fun `ordinary exceptions are not wrapped as ProcessCanceledException`() {
        val indicator = EmptyProgressIndicator()
        try {
            runBlockingWithProgressIndicator<Unit>(indicator) {
                throw IllegalStateException("boom")
            }
            fail("expected IllegalStateException")
        } catch (error: IllegalStateException) {
            assertEquals("boom", error.message)
        } catch (error: ProcessCanceledException) {
            fail("ordinary failure must not become ProcessCanceledException")
        }
    }
}
