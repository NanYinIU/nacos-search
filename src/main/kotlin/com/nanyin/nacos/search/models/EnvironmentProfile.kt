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
 *
 * Persisted inside [com.nanyin.nacos.search.settings.NacosSettings] via IntelliJ
 * XmlSerializer, so every property is a `var` with a default (Bean / Instantiator
 * friendly). Required-looking fields still validate at use sites.
 */
data class EnvironmentProfile(
    var id: String = "",
    var displayName: String = "",
    var canonicalEndpoint: String = "",
    var apiPolicy: NacosApiPolicy = NacosApiPolicy.AUTO,
    var authMode: AuthMode = AuthMode.TOKEN,
    var principal: String = "",
    var profileRevision: Long = 1,
    var accessRevision: Long = 1,
    var writeIntent: Boolean = false,
    var credentialSlotId: String = "",
    var credentialSlotVersion: Long = 1,
    var cacheTombstones: MutableList<String> = mutableListOf()
) {
    init {
        if (credentialSlotId.isBlank() && id.isNotBlank()) {
            credentialSlotId = "$id:v1"
        }
    }

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
        fun fromLegacy(server: NacosServerConfig): EnvironmentProfile {
            val profileId = server.id.ifBlank { "default" }
            return EnvironmentProfile(
                id = profileId,
                displayName = server.displayName,
                canonicalEndpoint = CanonicalNacosEndpoint.parse(server.serverUrl).getOrNull()?.value.orEmpty(),
                apiPolicy = server.apiPolicy,
                authMode = server.authMode,
                principal = server.username.trim(),
                writeIntent = server.writeIntent,
                credentialSlotId = "$profileId:v1",
                credentialSlotVersion = 1
            )
        }
    }
}
