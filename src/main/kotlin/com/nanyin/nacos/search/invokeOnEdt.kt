package com.nanyin.nacos.search

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState

/**
 * The one EDT marshalling idiom (#82). Always schedules onto the event
 * dispatch thread under [modality]; never runs [action] inline. Call sites
 * that defer focus or layout past the current stack keep that order.
 */
internal fun invokeOnEdt(
    modality: ModalityState = ModalityState.defaultModalityState(),
    action: () -> Unit,
) {
    ApplicationManager.getApplication().invokeLater(action, modality)
}
