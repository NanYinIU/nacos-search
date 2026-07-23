package com.nanyin.nacos.search.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.util.SlowOperations
import com.intellij.util.ThrowableRunnable
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores Nacos server passwords in the IntelliJ [PasswordSafe] credential
 * store instead of the plaintext component-state XML. Secrets are keyed by the
 * stable server id, so each environment has its own entry.
 *
 * PasswordSafe access is a slow operation (it may involve OS keychain I/O)
 * that IntelliJ prohibits on the EDT via [SlowOperations]. To avoid repeated
 * slow reads, resolved secrets are cached in memory after the first access.
 * The actual PasswordSafe call is wrapped in
 * [SlowOperations.allowSlowOperations] so the first read is permitted even
 * when it happens on the EDT.
 */
@Suppress("DEPRECATION")
object NacosCredentialStore {

    private const val SUBSYSTEM = "Nacos Search"

    /**
     * In-memory mirror of PasswordSafe entries. Values are wrapped in
     * [Optional] because [ConcurrentHashMap] cannot store `null`.
     */
    private val cache = ConcurrentHashMap<String, Optional<String>>()

    private fun attributesFor(serverId: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SUBSYSTEM, serverId))

    fun get(serverId: String): String? =
        try {
            cache.getOrPut(serverId) {
                Optional.ofNullable(
                    SlowOperations.allowSlowOperations(
                        ThrowableComputable<String?, RuntimeException> {
                            PasswordSafe.instance.getPassword(attributesFor(serverId))
                        }
                    )
                )
            }.orElse(null)
        } catch (e: Exception) {
            null
        }

    fun set(serverId: String, password: String) {
        try {
            val attributes = attributesFor(serverId)
            SlowOperations.allowSlowOperations(
                ThrowableRunnable<RuntimeException> {
                    if (password.isEmpty()) {
                        PasswordSafe.instance.set(attributes, null)
                    } else {
                        PasswordSafe.instance.set(attributes, Credentials(serverId, password))
                    }
                }
            )
            cache[serverId] = Optional.of(password)
        } catch (e: Exception) {
            // PasswordSafe may be unavailable in some headless contexts; ignore.
        }
    }

    fun remove(serverId: String) {
        try {
            SlowOperations.allowSlowOperations(
                ThrowableRunnable<RuntimeException> {
                    PasswordSafe.instance.set(attributesFor(serverId), null)
                }
            )
            cache.remove(serverId)
        } catch (e: Exception) {
            // ignore
        }
    }
}
