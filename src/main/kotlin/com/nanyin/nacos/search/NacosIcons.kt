package com.nanyin.nacos.search

import com.intellij.openapi.util.IconLoader

object NacosIcons {
    val ToolWindow = IconLoader.getIcon("/icons/nacosSearch_20.svg", NacosIcons::class.java)

    // Gutter line-marker: fresh, stale, unresolved, and 格式不参与解析. Dark
    // siblings are auto-selected. The last one is the only member of the family
    // drawn without the jump arrow, because it is the only one with nowhere to
    // jump — every other state is clickable, even the unresolved one.
    val GutterConfig = IconLoader.getIcon("/icons/nacosConfigGutter.svg", NacosIcons::class.java)
    val GutterConfigStale = IconLoader.getIcon("/icons/nacosConfigGutterStale.svg", NacosIcons::class.java)
    val GutterConfigUnresolved = IconLoader.getIcon("/icons/nacosConfigGutterUnresolved.svg", NacosIcons::class.java)
    val GutterConfigFormatNotParsed =
        IconLoader.getIcon("/icons/nacosConfigGutterFormatNotParsed.svg", NacosIcons::class.java)
    val GutterCodeUsage = IconLoader.getIcon("/icons/nacosCodeUsage.svg", NacosIcons::class.java)
}
