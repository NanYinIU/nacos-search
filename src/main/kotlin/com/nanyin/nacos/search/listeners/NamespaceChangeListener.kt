package com.nanyin.nacos.search.listeners

import com.nanyin.nacos.search.models.NamespaceInfo

/**
 * 命名空间变化监听器接口
 * 用于监听命名空间选择变化事件，实现组件间的解耦通信
 */
interface NamespaceChangeListener {
    
    /**
     * 当命名空间选择发生变化时调用
     * 
     * @param oldNamespace 之前选择的命名空间，如果是首次选择则为null
     * @param newNamespace 新选择的命名空间，如果清除选择则为null
     */
    suspend fun onNamespaceChanged(oldNamespace: NamespaceInfo?, newNamespace: NamespaceInfo?)
    
    /**
     * 当命名空间列表刷新时调用
     * 
     * @param namespaces 刷新后的命名空间列表
     */
    fun onNamespaceListRefreshed(namespaces: List<NamespaceInfo>) {
        // 默认空实现，子类可选择性重写
    }
    
    /**
     * 当命名空间加载失败时调用
     * 
     * @param error 错误信息
     * @param throwable 异常对象，可能为null
     */
    fun onNamespaceLoadError(error: String, throwable: Throwable? = null) {
        // 默认空实现，子类可选择性重写
    }
    
    /**
     * 当命名空间加载开始时调用
     */
    fun onNamespaceLoadStarted() {
        // 默认空实现，子类可选择性重写
    }
    
    /**
     * 当命名空间加载完成时调用
     * 
     * @param success 是否加载成功
     */
    fun onNamespaceLoadCompleted(success: Boolean) {
        // 默认空实现，子类可选择性重写
    }
}