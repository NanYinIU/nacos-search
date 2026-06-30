package com.nanyin.nacos.search.psi

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

data class NacosUsagePresentation(
    val primaryText: String,
    val secondaryText: String,
    val locationText: String
) {
    override fun toString(): String = "$primaryText  $locationText"

    companion object {
        fun fromElement(element: PsiElement): NacosUsagePresentation {
            val application = ApplicationManager.getApplication()
            if (!application.isReadAccessAllowed) {
                return application.runReadAction<NacosUsagePresentation> {
                    buildPresentation(element)
                }
            }
            return buildPresentation(element)
        }

        private fun buildPresentation(element: PsiElement): NacosUsagePresentation {
            val literal = element as? PsiLiteralExpression
            val owner = literal?.let { usageOwner(it) } ?: element
            val containingClass = PsiTreeUtil.getParentOfType(owner, PsiClass::class.java)
                ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
            val primary = formatPrimaryText(
                className = containingClass?.name,
                ownerName = ownerName(owner),
                moduleName = ModuleUtilCore.findModuleForPsiElement(element)?.name
            ).ifBlank { element.text.trim().ifBlank { "Nacos usage" } }
            val annotation = literal?.let { PsiTreeUtil.getParentOfType(it, PsiAnnotation::class.java) }
            val secondary = annotation?.text?.compactWhitespace()
                ?: element.text.compactWhitespace()
            val location = elementLocation(element)
            return NacosUsagePresentation(primary, secondary, location)
        }

        private fun usageOwner(literal: PsiLiteralExpression): PsiElement? {
            val field = PsiTreeUtil.getParentOfType(literal, PsiField::class.java)
            if (field != null) return field
            return PsiTreeUtil.getParentOfType(literal, PsiMethod::class.java)
        }

        private fun ownerName(owner: PsiElement): String? = when (owner) {
            is PsiField -> owner.name
            is PsiMethod -> owner.name
            else -> null
        }?.takeIf { it.isNotBlank() }

        fun formatPrimaryText(className: String?, ownerName: String?, moduleName: String?): String {
            val symbol = listOfNotNull(className, ownerName)
                .joinToString(".")
                .takeIf { it.isNotBlank() }
                ?: return ""
            val modulePath = normalizeModuleName(moduleName)
            return if (modulePath.isBlank()) symbol else "$modulePath/$symbol"
        }

        private fun normalizeModuleName(moduleName: String?): String =
            moduleName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.split(Regex("[.:/]+"))
                ?.filter { it.isNotBlank() && it != "main" && it != "test" }
                ?.joinToString("/")
                .orEmpty()

        private fun elementLocation(element: PsiElement): String {
            val file = element.containingFile ?: return ""
            val fileName = file.name
            val document = file.viewProvider.document ?: return fileName
            val line = document.getLineNumber(element.textOffset) + 1
            return "$fileName:$line"
        }

        private fun String.compactWhitespace(): String =
            replace(Regex("\\s+"), " ").trim()
    }
}
