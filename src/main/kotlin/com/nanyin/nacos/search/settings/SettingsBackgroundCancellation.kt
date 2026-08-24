package com.nanyin.nacos.search.settings

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal fun Throwable.isCooperativeCancellation(): Boolean =
    this is CancellationException || this is ProcessCanceledException

internal fun Throwable.asProcessCanceled(): ProcessCanceledException =
    this as? ProcessCanceledException ?: ProcessCanceledException(this)

private const val CANCEL_WATCH_MS = 25L

/**
 * Runs [block] under [runBlocking] and cancels that coroutine when the
 * IntelliJ [ProgressIndicator] is cancelled. Cancellation becomes
 * [ProcessCanceledException] so a [com.intellij.openapi.progress.Task]
 * treats it as Cancel rather than a failed run.
 */
internal fun <T> runBlockingWithProgressIndicator(
    indicator: ProgressIndicator,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend CoroutineScope.() -> T
): T {
    indicator.checkCanceled()
    try {
        return runBlocking {
            val parentJob = coroutineContext[Job]!!
            val watch = launch(Dispatchers.Default) {
                while (isActive) {
                    if (indicator.isCanceled) {
                        parentJob.cancel()
                        break
                    }
                    delay(CANCEL_WATCH_MS)
                }
            }
            try {
                withContext(dispatcher, block)
            } finally {
                watch.cancel()
            }
        }
    } catch (cancelled: ProcessCanceledException) {
        throw cancelled
    } catch (cancelled: CancellationException) {
        throw ProcessCanceledException(cancelled)
    }
}
