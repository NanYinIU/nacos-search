package com.nanyin.nacos.search.bundle

import com.intellij.AbstractBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

/**
 * Bundle for internationalization support
 */
object NacosSearchBundle : AbstractBundle("messages.NacosSearchBundle") {
    
    @NonNls
    private const val BUNDLE = "messages.NacosSearchBundle"
    
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String {
        return getMessage(key, *params)
    }
}