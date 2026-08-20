package com.nanyin.nacos.search.settings

import com.intellij.openapi.ui.ComboBox
import com.nanyin.nacos.search.bundle.NacosSearchBundle
import com.nanyin.nacos.search.invokeOnEdt
import com.nanyin.nacos.search.services.operations.DiscoveredNamespace
import java.awt.Component
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Editable, filterable suggested-Namespace chooser. Options come from an
 * isolated Namespace discovery; the editor remains typeable when the list
 * is empty. Persistable value is always a Namespace ID.
 *
 * Must be an IntelliJ [ComboBox]: a plain Swing editable [javax.swing.JComboBox]
 * leaves Darcula/Basic ComboBoxUI.editor null, and the first layout NPE's in
 * getPreferredSize (settings wraps this field in BorderLayout).
 */
class SuggestedNamespaceComboBox : ComboBox<DiscoveredNamespace>() {

    private var allOptions: List<DiscoveredNamespace> = emptyList()
    private var lastCommitted: String = ""
    private var mutating = false

    var onNamespaceCommitted: () -> Unit = {}
    var onPopupWillBecomeVisible: () -> Unit = {}

    private val editorField: JTextField
        get() = editor.editorComponent as JTextField

    init {
        isEditable = true
        // installUI only adds the editor when isEditable is already true.
        // The ComboBox constructor installed the UI while still non-editable,
        // so getPreferredSize NPEs (Darcula/Basic ComboBoxUI.editor is null).
        updateUI()
        setEditor(NamespaceIdEditor())
        putClientProperty("nacos.automation.id", "nacos.settings.namespace")
        prototypeDisplayValue = DiscoveredNamespace("public", "public")
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = when {
                    value is DiscoveredNamespace && value === SuggestedNamespaceChooserStatus.LOADING ->
                        NacosSearchBundle.message("settings.namespace.chooser.loading")
                    value is DiscoveredNamespace && value === SuggestedNamespaceChooserStatus.FAILED ->
                        NacosSearchBundle.message("settings.namespace.chooser.failed")
                    value is DiscoveredNamespace -> SuggestedNamespaceSelection.label(value)
                    else -> value?.toString().orEmpty()
                }
                return this
            }
        }
        editorField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onEditorChange()
            override fun removeUpdate(e: DocumentEvent?) = onEditorChange()
            override fun changedUpdate(e: DocumentEvent?) = onEditorChange()
        })
        addItemListener { event ->
            if (mutating || event.stateChange != ItemEvent.SELECTED) return@addItemListener
            val selected = event.item as? DiscoveredNamespace ?: return@addItemListener
            if (SuggestedNamespaceChooserStatus.isTransient(selected)) return@addItemListener
            mutating = true
            try {
                editorField.text = selected.namespaceId
                lastCommitted = selected.namespaceId
                restoreUnfilteredModel()
            } finally {
                mutating = false
            }
            onNamespaceCommitted()
        }
        addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent?) {
                onPopupWillBecomeVisible()
            }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent?) = Unit
            override fun popupMenuCanceled(e: PopupMenuEvent?) = Unit
        })
    }

    fun namespaceId(): String =
        SuggestedNamespaceSelection.persistableId(editorField.text, allOptions, lastCommitted)

    fun setNamespaceId(id: String) {
        mutating = true
        try {
            lastCommitted = id
            editorField.text = id
            selectMatching(id)
        } finally {
            mutating = false
        }
    }

    fun applyDiscovered(options: List<DiscoveredNamespace>) {
        val current = namespaceId()
        allOptions = options
        lastCommitted = current
        mutating = true
        try {
            restoreUnfilteredModel()
        } finally {
            mutating = false
        }
    }

    fun clearDiscoveredOptions() {
        val current = namespaceId()
        allOptions = emptyList()
        lastCommitted = current
        mutating = true
        try {
            removeAllItems()
            editorField.text = current
        } finally {
            mutating = false
        }
    }

    fun discoveredCount(): Int = allOptions.size

    fun showLoadingPlaceholder() = showTransient(SuggestedNamespaceChooserStatus.LOADING)

    fun showFailurePlaceholder() = showTransient(SuggestedNamespaceChooserStatus.FAILED)

    fun isTransientRowVisible(): Boolean =
        itemCount == 1 && getItemAt(0)?.let { SuggestedNamespaceChooserStatus.isTransient(it) } == true

    private fun showTransient(row: DiscoveredNamespace) {
        mutating = true
        try {
            val model = DefaultComboBoxModel<DiscoveredNamespace>()
            model.addElement(row)
            model.selectedItem = null
            setModel(model)
            editorField.text = lastCommitted
        } finally {
            mutating = false
        }
    }

    private fun onEditorChange() {
        if (mutating) return
        val query = editorField.text
        invokeOnEdt {
            if (mutating) return@invokeOnEdt
            filterModel(query)
            onNamespaceCommitted()
        }
    }

    private fun filterModel(query: String) {
        if (allOptions.isEmpty()) return
        mutating = true
        try {
            val items = if (query == lastCommitted || allOptions.any { it.namespaceId == query }) {
                allOptions
            } else {
                allOptions.filter { SuggestedNamespaceSelection.matchesFilter(it, query) }
            }
            replaceItems(items, editorText = query)
        } finally {
            mutating = false
        }
    }

    private fun restoreUnfilteredModel() {
        val alreadyMutating = mutating
        mutating = true
        try {
            replaceItems(allOptions, editorText = lastCommitted)
        } finally {
            if (!alreadyMutating) mutating = false
        }
    }

    private fun replaceItems(items: List<DiscoveredNamespace>, editorText: String) {
        val model = DefaultComboBoxModel<DiscoveredNamespace>()
        items.forEach { model.addElement(it) }
        val keepId = lastCommitted
        model.selectedItem = items.find { it.namespaceId == keepId }
        setModel(model)
        editorField.text = editorText
    }

    private fun selectMatching(id: String) {
        val match = allOptions.find { it.namespaceId == id }
        selectedItem = match
        if (match == null) {
            editorField.text = id
        }
    }

    /**
     * Stores and displays the Namespace ID, never [DiscoveredNamespace.toString].
     */
    private class NamespaceIdEditor : javax.swing.plaf.basic.BasicComboBoxEditor() {
        override fun setItem(anObject: Any?) {
            if (anObject is DiscoveredNamespace && SuggestedNamespaceChooserStatus.isTransient(anObject)) return
            val text = when (anObject) {
                is DiscoveredNamespace -> anObject.namespaceId
                null -> ""
                else -> anObject.toString()
            }
            editor.text = text
        }

        override fun getItem(): Any = editor.text
    }
}
