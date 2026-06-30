package com.nanyin.nacos.search.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Stores Nacos server passwords in the IntelliJ [PasswordSafe] credential
 * store instead of the plaintext component-state XML. Secrets are keyed by the
 * stable server id, so each environment has its own entry.
 */
object NacosCredentialStore {

    private const val SUBSYSTEM = "Nacos Search"

    private fun attributesFor(serverId: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SUBSYSTEM, serverId))

    fun get(serverId: String): String? =
        try {
            PasswordSafe.instance.getPassword(attributesFor(serverId))
        } catch (e: Exception) {
            null
        }

    fun set(serverId: String, password: String) {
        try {
            val attributes = attributesFor(serverId)
            if (password.isEmpty()) {
                PasswordSafe.instance.set(attributes, null)
            } else {
                PasswordSafe.instance.set(attributes, Credentials(serverId, password))
            }
        } catch (e: Exception) {
            // PasswordSafe may be unavailable in some headless contexts; ignore.
        }
    }

    fun remove(serverId: String) {
        try {
            PasswordSafe.instance.set(attributesFor(serverId), null)
        } catch (e: Exception) {
            // ignore
        }
    }
}
