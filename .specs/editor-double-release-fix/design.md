# Editor Double Release Fix - Design Document

## Overview

This document outlines the technical design to fix the editor double release issue in `ConfigDetailPanel.kt`. The solution focuses on implementing thread-safe editor resource management using IntelliJ Platform best practices.

## Root Cause Analysis

The double release error occurs due to:
1. **Race conditions** in multithreaded environments when multiple configuration switches happen rapidly
2. **Improper async handling** with `SwingUtilities.invokeLater()` causing disposal order issues
3. **Lack of synchronization** in editor lifecycle management
4. **Missing state tracking** to prevent duplicate disposal operations

## Design Principles

### 1. Thread Safety
- Use proper synchronization mechanisms for editor operations <mcreference link="https://gavincook.gitbooks.io/intellij-platform-sdk-devguide/content/basics/architectural_overview/general_threading_rules.html" index="3">3</mcreference>
- Replace `SwingUtilities.invokeLater()` with `ApplicationManager.getApplication().invokeLater()` for proper modality handling <mcreference link="https://gavincook.gitbooks.io/intellij-platform-sdk-devguide/content/basics/architectural_overview/general_threading_rules.html" index="3">3</mcreference>
- Implement sequential editor disposal to prevent race conditions

### 2. Resource Management
- Follow IntelliJ Platform's Disposable pattern correctly <mcreference link="https://plugins.jetbrains.com/docs/intellij/disposers.html" index="1">1</mcreference>
- Register editors with proper parent Disposables
- Implement explicit resource cleanup in dispose() methods

### 3. State Management
- Track editor lifecycle states to prevent double operations
- Use atomic operations for state changes
- Implement proper cancellation for async operations

## Technical Design

### 1. Editor State Management

```kotlin
private enum class EditorState {
    NONE,
    CREATING,
    ACTIVE,
    DISPOSING,
    DISPOSED
}

private val editorState = AtomicReference(EditorState.NONE)
private val editorLock = ReentrantLock()
```

### 2. Thread-Safe Editor Operations

#### 2.1 Editor Creation
```kotlin
private fun createEditorSafely(content: String, fileType: FileType): Editor? {
    return editorLock.withLock {
        if (!editorState.compareAndSet(EditorState.NONE, EditorState.CREATING)) {
            return null // Another operation in progress
        }
        
        try {
            val document = EditorFactory.getInstance().createDocument(content)
            val editor = EditorFactory.getInstance().createEditor(document, project, fileType, true)
            
            // Register with Disposer for proper cleanup
            Disposer.register(this, editor)
            
            editorState.set(EditorState.ACTIVE)
            editor
        } catch (e: Exception) {
            editorState.set(EditorState.NONE)
            throw e
        }
    }
}
```

#### 2.2 Editor Disposal
```kotlin
private fun disposeEditorSafely() {
    editorLock.withLock {
        val currentState = editorState.get()
        if (currentState == EditorState.DISPOSING || currentState == EditorState.DISPOSED) {
            return // Already disposing or disposed
        }
        
        if (!editorState.compareAndSet(currentState, EditorState.DISPOSING)) {
            return // State changed, abort
        }
        
        try {
            currentEditor?.let { editor ->
                if (!editor.isDisposed) {
                    EditorFactory.getInstance().releaseEditor(editor)
                }
            }
            currentEditor = null
            editorState.set(EditorState.DISPOSED)
        } catch (e: Exception) {
            // Log error but don't rethrow to prevent cascading failures
            logger.warn("Error disposing editor", e)
            editorState.set(EditorState.DISPOSED)
        }
    }
}
```

### 3. Async Operation Management

#### 3.1 Cancellable Operations
```kotlin
private var currentLoadingTask: Future<*>? = null

private fun loadConfigurationContentSafely(configInfo: ConfigInfo) {
    // Cancel previous loading task
    currentLoadingTask?.cancel(true)
    
    currentLoadingTask = ApplicationManager.getApplication().executeOnPooledThread {
        try {
            val content = nacosService.getConfigContent(configInfo)
            
            // Use proper IntelliJ threading instead of SwingUtilities
            ApplicationManager.getApplication().invokeLater({
                if (!Thread.currentThread().isInterrupted) {
                    displayContentSafely(content, configInfo.type)
                }
            }, ModalityState.defaultModalityState())
            
        } catch (e: Exception) {
            if (!Thread.currentThread().isInterrupted) {
                logger.error("Failed to load configuration content", e)
            }
        }
    }
}
```

### 4. UI Update Synchronization

#### 4.1 Safe Content Display
```kotlin
private fun displayContentSafely(content: String, fileType: FileType) {
    ApplicationManager.getApplication().assertIsDispatchThread()
    
    // Dispose previous editor safely
    disposeEditorSafely()
    
    // Wait for disposal to complete
    editorLock.withLock {
        while (editorState.get() == EditorState.DISPOSING) {
            Thread.sleep(10) // Brief wait for disposal completion
        }
        
        // Reset state for new editor
        editorState.set(EditorState.NONE)
    }
    
    // Create new editor
    val newEditor = createEditorSafely(content, fileType)
    if (newEditor != null) {
        currentEditor = newEditor
        updateUI(newEditor)
    }
}
```

### 5. Disposable Implementation

#### 5.1 Proper Cleanup
```kotlin
override fun dispose() {
    // Cancel any ongoing operations
    currentLoadingTask?.cancel(true)
    
    // Dispose editor safely
    disposeEditorSafely()
    
    // Clean up other resources
    // Note: Registered disposables will be automatically cleaned up
}
```

## Implementation Strategy

### Phase 1: Core Infrastructure
1. Add thread-safe state management
2. Implement editor lifecycle tracking
3. Add proper synchronization mechanisms

### Phase 2: Editor Operations
1. Refactor editor creation with safety checks
2. Implement safe editor disposal
3. Add proper error handling

### Phase 3: Async Operations
1. Replace SwingUtilities with ApplicationManager.invokeLater()
2. Implement cancellable loading operations
3. Add proper modality state handling

### Phase 4: Integration
1. Update all editor-related methods
2. Add comprehensive error handling
3. Implement proper cleanup in dispose()

## Testing Strategy

### Unit Tests
- Test editor state transitions
- Test concurrent access scenarios
- Test disposal safety

### Integration Tests
- Test rapid configuration switching
- Test async operation cancellation
- Test resource cleanup

### Manual Testing
- Stress test with rapid UI interactions
- Test with various configuration types
- Verify no memory leaks

## Risk Mitigation

### Performance Impact
- Synchronization overhead is minimal due to short critical sections
- State checks are atomic operations
- Lock contention is unlikely in typical usage

### Compatibility
- Changes are internal to ConfigDetailPanel
- No API changes affecting other components
- Backward compatible with existing functionality

### Rollback Plan
- Keep original implementation as backup
- Implement feature flag for new behavior
- Monitor for any regression issues

## Success Criteria

1. **No Double Release Errors**: Complete elimination of TraceableDisposable$DisposalException
2. **Thread Safety**: No race conditions during rapid configuration switching
3. **Resource Management**: Proper cleanup of all editor resources
4. **Performance**: No noticeable performance degradation
5. **Stability**: No new exceptions or memory leaks introduced

## Dependencies

- IntelliJ Platform SDK (existing)
- Kotlin coroutines (if needed for advanced async handling)
- No external dependencies required

## Timeline

Estimated implementation time: 2-3 days
- Day 1: Core infrastructure and state management
- Day 2: Editor operations and async handling
- Day 3: Integration, testing, and refinement