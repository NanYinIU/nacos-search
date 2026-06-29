package com.nanyin.nacos.search

import com.intellij.openapi.util.IconLoader

object NacosIcons {
    val ToolWindow = IconLoader.getIcon("/icons/nacosSearch_20.svg", NacosIcons::class.java)

    // Gutter line-marker: dual state. The _dark.svg siblings are auto-selected by IconLoader.
    val GutterConfig = IconLoader.getIcon("/icons/nacosConfigGutter.svg", NacosIcons::class.java)
    val GutterConfigUnresolved = IconLoader.getIcon("/icons/nacosConfigGutterUnresolved.svg", NacosIcons::class.java)
    val GutterCodeUsage = IconLoader.getIcon("/icons/nacosCodeUsage.svg", NacosIcons::class.java)
}
