package com.nanyin.nacos.search.psi

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.nanyin.nacos.search.NacosIcons
import com.nanyin.nacos.search.models.NacosConfiguration
import javax.swing.Icon

/**
 * Virtual [PsiElement] representing the *definition site* of a Nacos
 * configuration key (e.g. the `app.name` line inside `application.properties`).
 *
 * It is not backed by a real file — the configuration lives in the local
 * cache — so it extends [FakePsiElement]. It serves two roles:
 *
 *  1. Declaration target: `@NacosValue` references resolve() to instances of
 *     this class, powering Cmd+Click navigation and the "go to declaration"
 *     gutter action.
 *  2. Find Usages anchor: invoking Find Usages on this element searches the
 *     project for `${key}` placeholders and reports them as usages.
 */
class NacosConfigKeyElement(
    private val project: Project,
    val config: NacosConfiguration,
    val key: String,
    val value: String,
    val lineIndex: Int
) : FakePsiElement() {

    val namespaceId: String get() = config.tenantId ?: ""
    override fun getProject(): Project = project
    override fun getParent(): PsiElement? = null

    override fun getName(): String = key

    override fun getNavigationElement(): PsiElement = this

    override fun isWritable(): Boolean = false

    override fun isValid(): Boolean = true

    override fun navigate(requestFocus: Boolean) {
        NacosConfigNavigator.navigate(project, config, lineIndex)
    }

    override fun getPresentation(): ItemPresentation {
        return object : ItemPresentation {
            override fun getPresentableText(): String = "$key = $value"
            override fun getLocationString(): String =
                "${config.dataId} (${config.group})"
            override fun getIcon(unused: Boolean): Icon = NacosIcons.ToolWindow
        }
    }

    override fun getTextOffset(): Int = 0

    override fun toString(): String = "NacosConfigKeyElement($key in ${config.dataId})"
}
