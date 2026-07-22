package com.nanyin.nacos.search.models

import com.nanyin.nacos.search.settings.AuthMode
import java.net.IDN
import java.net.URI

/** A normalized Nacos origin. It intentionally never contains a path, query, fragment, or user-info. */
@JvmInline
value class CanonicalNacosEndpoint private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Result<CanonicalNacosEndpoint> = runCatching {
            require(raw == raw.trim() && raw.isNotBlank()) { "Endpoint is required" }
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase() ?: error("Endpoint scheme is required")
            require(scheme == "http" || scheme == "https") { "Endpoint must use HTTP or HTTPS" }
            require(uri.isAbsolute && uri.host != null) { "Endpoint must be an absolute origin" }
            require(uri.userInfo == null) { "Endpoint must not include user info" }
            require(uri.query == null && uri.fragment == null) { "Endpoint must not include query or fragment" }
            require(uri.path.isNullOrEmpty() || uri.path == "/") { "Endpoint must not include a path" }
            require(uri.port in -1..65535) { "Endpoint port is invalid" }

            val host = IDN.toASCII(uri.host).lowercase()
            require(host.isNotBlank()) { "Endpoint host is required" }
            val port = uri.port.takeUnless { (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
            val renderedHost = if (host.contains(':')) "[$host]" else host
            CanonicalNacosEndpoint(buildString {
                append(scheme).append("://").append(renderedHost)
                if (port != null && port >= 0) append(':').append(port)
            })
        }
    }
}

enum class NacosApiPolicy { AUTO, V1, V3 }

/**
 * Application-wide reusable environment. Secrets are referenced by slot id and
 * deliberately never stored in this state object.
 */
data class EnvironmentProfile(
    val id: String,
    val displayName: String,
    val canonicalEndpoint: String,
    val apiPolicy: NacosApiPolicy = NacosApiPolicy.AUTO,
    val authMode: AuthMode = AuthMode.TOKEN,
    val principal: String = "",
    val profileRevision: Long = 1,
    val accessRevision: Long = 1,
    val writeIntent: Boolean = false,
    val credentialSlotId: String = "$id:v1",
    val credentialSlotVersion: Long = 1,
    val cacheTombstones: MutableList<String> = mutableListOf()
) {
    fun withUpdated(
        canonicalEndpoint: String = this.canonicalEndpoint,
        apiPolicy: NacosApiPolicy = this.apiPolicy,
        authMode: AuthMode = this.authMode,
        principal: String = this.principal,
        writeIntent: Boolean = this.writeIntent,
        credentialSlotId: String = this.credentialSlotId,
        credentialSlotVersion: Long = this.credentialSlotVersion
    ): EnvironmentProfile {
        val accessChanged = canonicalEndpoint != this.canonicalEndpoint ||
            apiPolicy != this.apiPolicy ||
            authMode != this.authMode ||
            principal != this.principal ||
            credentialSlotId != this.credentialSlotId ||
            credentialSlotVersion != this.credentialSlotVersion
        val profileChanged = accessChanged || writeIntent != this.writeIntent
        return copy(
            canonicalEndpoint = canonicalEndpoint,
            apiPolicy = apiPolicy,
            authMode = authMode,
            principal = principal,
            writeIntent = writeIntent,
            credentialSlotId = credentialSlotId,
            credentialSlotVersion = credentialSlotVersion,
            profileRevision = if (profileChanged) profileRevision + 1 else profileRevision,
            accessRevision = if (accessChanged) accessRevision + 1 else accessRevision
        )
    }

    companion object {
        fun fromLegacy(server: NacosServerConfig): EnvironmentProfile = EnvironmentProfile(
            id = server.id.ifBlank { "default" },
            displayName = server.displayName,
            canonicalEndpoint = CanonicalNacosEndpoint.parse(server.serverUrl).getOrNull()?.value.orEmpty(),
            apiPolicy = NacosApiPolicy.AUTO,
            authMode = server.authMode,
            principal = server.username.trim(),
            writeIntent = false,
            credentialSlotId = "${server.id.ifBlank { "default" }}:v1",
            credentialSlotVersion = 1
        )
    }
}
