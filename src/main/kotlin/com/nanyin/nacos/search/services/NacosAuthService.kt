package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Owns the application-level [AuthenticationSessionRegistry] shared by V1 and V3
 * dialects, and profile-deletion cleanup for those sessions.
 *
 * Legacy settings-coordinate token helpers were removed with issue #156; dialect
 * login over ProtocolTransport is the sole acquisition path (issue #96).
 */
@Service(Service.Level.APP)
class NacosAuthService {
    private val logger = thisLogger()
    private val sessions = AuthenticationSessionRegistry()

    /** The one completed-token registry both protocol dialects must use (issue #96). */
    internal val authenticationSessions: AuthenticationSessionRegistry get() = sessions

    /**
     * Every identity holding authentication-session state. Read-only: what an
     * operation leaves behind in the registry is otherwise invisible, and
     * ADR-0022 makes "leaves nothing behind" a requirement diagnostics have to meet.
     */
    internal fun sessionIdentities(): Set<AccessIdentity> = sessions.trackedIdentities()

    /**
     * Invalidates the authentication session for a captured operation context.
     */
    internal fun invalidateToken(context: NacosOperationContext) {
        sessions.invalidate(context.identity)
        logger.info("Invalidated token for captured access context")
    }

    /**
     * Profile-deletion cleanup (ADR-0025 / issue #105). Drops every authentication
     * session owned by [profileId]. Idempotent.
     */
    internal fun invalidateProfile(profileId: String) {
        val id = profileId.trim()
        if (id.isBlank()) return
        sessions.invalidateProfile(id)
        logger.info("Invalidated authentication sessions for deleted profile $id")
    }
}
