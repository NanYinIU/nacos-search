package com.nanyin.nacos.search.settings

import com.nanyin.nacos.search.services.operations.DiscoveredNamespace
import java.awt.Component
import java.awt.event.ItemEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Editable, filterable suggested-Namespace chooser. Options come from an
 * isolated connection diagnosis; the editor remains typeable when the list
 * is empty. Persistable value is always a Namespace ID.
 */
class SuggestedNamespaceComboBox : JComboBox<DiscoveredNamespace>(DefaultComboBoxModel()) {

    private var allOptions: List<DiscoveredNamespace> = emptyList()
    private var lastCommitted: String = ""
    private var mutating = false

    var onNamespaceCommitted: () -> Unit = {}

    private val editorField: JTextField
        get() = editor.editorComponent as JTextField

    init {
        isEditable = true
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
                text = when (value) {
                    is DiscoveredNamespace -> SuggestedNamespaceSelection.label(value)
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
            editorField.text = current
            selectMatching(current)
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

    private fun onEditorChange() {
        if (mutating) return
        filterModel(editorField.text)
        onNamespaceCommitted()
    }

    private fun filterModel(query: String) {
        if (allOptions.isEmpty()) return
        mutating = true
        try {
            val filtered = allOptions.filter { SuggestedNamespaceSelection.matchesFilter(it, query) }
            replaceItems(filtered)
            editorField.text = query
        } finally {
            mutating = false
        }
    }

    private fun restoreUnfilteredModel() {
        replaceItems(allOptions)
    }

    private fun replaceItems(items: List<DiscoveredNamespace>) {
        val model = DefaultComboBoxModel<DiscoveredNamespace>()
        items.forEach { model.addElement(it) }
        setModel(model)
    }

    private fun selectMatching(id: String) {
        val match = allOptions.find { it.namespaceId == id }
        selectedItem = match
        if (match == null) {
            editorField.text = id
        }
    }
}
