package com.nanyin.nacos.search.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.nanyin.nacos.search.Edt

/**
 * Owns Settings background work: the progress indicator, delivery generation,
 * and disposal fence. Closing the Configurable cancels in-flight work and
 * drops queued EDT completions so they cannot mutate a dead UI or a newer
 * request's lifecycle identity.
 */
internal class SettingsBackgroundOperations {
    private val lock = Any()
    private var generation = 0
    private var disposed = false
    private val activeIndicators = mutableListOf<ProgressIndicator>()

    fun <T> run(
        title: String,
        indicatorText: String,
        work: (ProgressIndicator) -> T,
        onSuccess: (T) -> Unit,
        onCancelled: () -> Unit
    ) {
        val token: Int
        synchronized(lock) {
            if (disposed) return
            token = ++generation
        }
        val unitTest = ApplicationManager.getApplication().isUnitTestMode

        fun deliver(block: () -> Unit) {
            val apply = {
                if (synchronized(lock) { !disposed && token == generation }) {
                    block()
                }
            }
            if (unitTest) {
                apply()
            } else {
                Edt.invokeOnEdt(ModalityState.defaultModalityState(), apply)
            }
        }

        fun execute(indicator: ProgressIndicator) {
            synchronized(lock) { activeIndicators.add(indicator) }
            try {
                val value = work(indicator)
                if (indicator.isCanceled) throw ProcessCanceledException()
                deliver { onSuccess(value) }
            } catch (error: Exception) {
                if (error.isCooperativeCancellation()) {
                    deliver { onCancelled() }
                    if (!unitTest) throw error.asProcessCanceled()
                    return
                }
                throw error
            } finally {
                synchronized(lock) { activeIndicators.remove(indicator) }
            }
        }

        if (unitTest) {
            execute(EmptyProgressIndicator())
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(null, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = indicatorText
                execute(indicator)
            }
        })
    }

    fun dispose() {
        val indicators: List<ProgressIndicator>
        synchronized(lock) {
            disposed = true
            generation++
            indicators = activeIndicators.toList()
            activeIndicators.clear()
        }
        indicators.forEach { it.cancel() }
    }
}
