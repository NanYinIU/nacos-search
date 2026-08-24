package com.nanyin.nacos.search.ui

import com.nanyin.nacos.search.services.operations.DetailReadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Latest-wins in-flight 配置详情 confirmation (issue #247).
 *
 * Every approved selection is admitted. The previous job is cancelled before
 * the new confirmation starts. [DetailController.isLoading] is presentation
 * state only: it never rejects the newer selection, and cleanup from an older
 * ticket cannot clear loading owned by a newer one.
 *
 * Holds no Swing. [ConfigDetailPanel] supplies confirm / present / paint.
 */
internal class DetailLatestLoad(
    private val controller: DetailController,
    private val scope: CoroutineScope
) {
    private var job: Job? = null

    fun replace(
        keepCachedVisible: Boolean,
        confirm: suspend () -> DetailReadResult,
        present: (DetailReadResult) -> DetailViewState,
        onLoadingChanged: (Boolean) -> Unit,
        onPresented: (DetailViewState) -> Unit,
        mapFailure: (Exception) -> DetailViewState?
    ) {
        job?.cancel()
        val ticket = controller.admitLoad()
        onLoadingChanged(true)
        if (!keepCachedVisible) {
            onPresented(DetailViewState.Loading)
        }
        job = scope.launch {
            try {
                val result = confirm()
                if (!isActive || !controller.stillOwns(ticket)) return@launch
                val state = present(result)
                if (!controller.stillOwns(ticket)) return@launch
                controller.finishLoad(ticket)
                onLoadingChanged(controller.isLoading)
                if (!controller.stillOwns(ticket)) return@launch
                onPresented(state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isActive || !controller.stillOwns(ticket)) return@launch
                val state = mapFailure(e) ?: return@launch
                controller.finishLoad(ticket)
                onLoadingChanged(controller.isLoading)
                if (!controller.stillOwns(ticket)) return@launch
                onPresented(state)
            }
        }
    }

    fun cancelAndRelease() {
        job?.cancel()
        job = null
        controller.releaseLoad()
    }
}
