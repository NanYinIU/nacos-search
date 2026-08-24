package com.nanyin.nacos.search.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Latest-wins in-flight 配置详情 confirmation (issue #247).
 *
 * Owns the Job, [isLoading] presentation flag, and replace/cancel protocol in
 * one place. [DetailController] stays a pure mapper; [ConfigDetailPanel]
 * supplies the confirm/present/paint work. A newer [replace] is always
 * admitted — [isLoading] never rejects it — and an older job cannot clear
 * loading or paint once it has been replaced.
 *
 * When [run] returns null (a result the 展示门禁 will not paint), loading is
 * still released if this job owns it, so an epoch change cannot leave the
 * panel stuck on Loading with Refresh disabled.
 */
internal class DetailLatestLoad(
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    private var generation = 0

    @Volatile
    var isLoading: Boolean = false
        private set

    fun replace(
        keepCachedVisible: Boolean,
        run: suspend () -> DetailViewState?,
        onLoadingChanged: (Boolean) -> Unit,
        onPresented: (DetailViewState) -> Unit
    ) {
        job?.cancel()
        val mine = ++generation
        isLoading = true
        onLoadingChanged(true)
        if (!keepCachedVisible) {
            onPresented(DetailViewState.Loading)
        }
        job = scope.launch {
            try {
                val state = run()
                if (mine != generation) return@launch
                isLoading = false
                onLoadingChanged(false)
                if (state != null) onPresented(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine != generation) return@launch
                isLoading = false
                onLoadingChanged(false)
            }
        }
    }

    fun cancelAndRelease() {
        job?.cancel()
        job = null
        generation++
        isLoading = false
    }
}
