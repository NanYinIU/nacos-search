package com.nanyin.nacos.search

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState

/**
 * The one EDT marshalling idiom (#82). Always schedules onto the event
 * dispatch thread under [modality]; never runs [action] inline. Call sites
 * that defer focus or layout past the current stack keep that order.
 *
 * This is a named object so IDEA's JPS compiler indexes it. A file that
 * contains only top-level functions is dropped from that compile, which
 * surfaces as `Unresolved reference: invokeOnEdt` in `runIde`.
 */
internal object Edt {
    fun invokeOnEdt(
        modality: ModalityState = ModalityState.defaultModalityState(),
        action: () -> Unit,
    ) {
        ApplicationManager.getApplication().invokeLater(action, modality)
    }
}
