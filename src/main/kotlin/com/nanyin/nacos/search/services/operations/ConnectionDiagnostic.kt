package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.models.NacosApiGeneration

/**
 * An immutable snapshot of settings that have NOT been applied yet.
 *
 * The diagnostic reads exclusively from this snapshot. It never touches
 * persisted profiles, project sessions, session epochs, cache, last-known
 * generation, request flights, or the formal authentication registry.
 */
data class DiagnosticSnapshot(
    val endpoint: String,
    val apiPolicy: String,
    val authStrategy: String,
    val principal: String,
    val secret: String,
    val namespaceId: String
)

/**
 * Result of a single diagnostic stage.
 */
data class DiagnosticStageResult(
    val stage: String,
    val success: Boolean,
    val durationMillis: Long,
    val resolvedGeneration: NacosApiGeneration? = null,
    val sanitizedFailure: String? = null
)

/**
 * Overall diagnostic outcome.
 *
 * A connection is successful when local validation, generation resolution,
 * and a configured-namespace read all succeed. Namespace discovery is
 * attempted separately; its failure does not change the connection verdict.
 */
data class DiagnosticReport(
    val connected: Boolean,
    val stages: List<DiagnosticStageResult>,
    val manualNamespaceRequired: Boolean
) {
    val summary: String
        get() = when {
            !connected -> "Connection failed"
            manualNamespaceRequired -> "Connected. Manual namespace. Discovery unavailable."
            else -> "Connected"
        }
}

/**
 * Runs an isolated connection diagnostic from an unapplied settings snapshot.
 *
 * The diagnostic applies the same V3-first, typed-fallback generation rules
 * as AUTO but does not share the formal probe context or temporary token.
 * It never mutates persisted profiles, project sessions, cache, auth registry,
 * or last-known generation.
 */
class ConnectionDiagnostic(
    private val resolver: GenerationResolver,
    private val gateway: OperationGateway,
    private val discoveryProbe: (suspend (OperationTarget) -> Result<Unit>)? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun diagnose(snapshot: DiagnosticSnapshot): DiagnosticReport {
        val stages = mutableListOf<DiagnosticStageResult>()

        // Stage 1: local validation
        val validation = validateLocally(snapshot)
        stages.add(validation)
        if (!validation.success) {
            return DiagnosticReport(connected = false, stages = stages, manualNamespaceRequired = false)
        }

        // Stage 2: honor locked V1/V3 drafts; only AUTO runs isolated probing
        val genStage = when (val policy = parseApiPolicy(snapshot.apiPolicy)) {
            com.nanyin.nacos.search.models.NacosApiPolicy.V1 ->
                DiagnosticStageResult(
                    stage = "generation",
                    success = true,
                    durationMillis = 0,
                    resolvedGeneration = NacosApiGeneration.V1
                )
            com.nanyin.nacos.search.models.NacosApiPolicy.V3 ->
                DiagnosticStageResult(
                    stage = "generation",
                    success = true,
                    durationMillis = 0,
                    resolvedGeneration = NacosApiGeneration.V3
                )
            com.nanyin.nacos.search.models.NacosApiPolicy.AUTO -> {
                val target = snapshotToTarget(snapshot, NacosApiGeneration.UNKNOWN)
                val genResult = timed("generation") { resolver.resolve(target) }
                DiagnosticStageResult(
                    stage = "generation",
                    success = genResult.second.isSuccess,
                    durationMillis = genResult.first,
                    resolvedGeneration = genResult.second.getOrNull(),
                    sanitizedFailure = genResult.second.exceptionOrNull()?.let { sanitize(it) }
                )
            }
        }
        stages.add(genStage)
        if (!genStage.success) {
            return DiagnosticReport(connected = false, stages = stages, manualNamespaceRequired = false)
        }

        val generation = genStage.resolvedGeneration!!

        // Stage 3: configured namespace read (page size 1)
        val resolvedTarget = snapshotToTarget(snapshot, generation)
        val readResult = timed("namespace_read") {
            gateway.listSummaries(resolvedTarget, SummaryQuery(pageSize = 1), useCache = false)
        }
        val readStage = DiagnosticStageResult(
            stage = "namespace_read",
            success = readResult.second.isSuccess,
            durationMillis = readResult.first,
            sanitizedFailure = readResult.second.exceptionOrNull()?.let { sanitize(it) }
        )
        stages.add(readStage)
        if (!readStage.success) {
            return DiagnosticReport(connected = false, stages = stages, manualNamespaceRequired = false)
        }

        // Stage 4: namespace discovery (optional, does not affect verdict)
        val discoveryResult = timed("discovery") {
            val probe = discoveryProbe ?: { gateway.probe(it) }
            probe(resolvedTarget)
        }
        val discoveryStage = DiagnosticStageResult(
            stage = "discovery",
            success = discoveryResult.second.isSuccess,
            durationMillis = discoveryResult.first,
            sanitizedFailure = discoveryResult.second.exceptionOrNull()?.let { sanitize(it) }
        )
        stages.add(discoveryStage)

        val connected = readStage.success
        val manualNamespaceRequired = connected && !discoveryStage.success

        return DiagnosticReport(connected = connected, stages = stages, manualNamespaceRequired = manualNamespaceRequired)
    }

    private fun validateLocally(snapshot: DiagnosticSnapshot): DiagnosticStageResult {
        val failures = mutableListOf<String>()
        if (snapshot.endpoint.isBlank()) failures.add("Endpoint is required")
        if (snapshot.principal.isBlank() && snapshot.secret.isNotBlank()) failures.add("Secret without principal")
        if (snapshot.principal.isNotBlank() && snapshot.secret.isBlank() &&
            snapshot.authStrategy != "BEARER_TOKEN" &&
            snapshot.authStrategy != "NACOS_PASSWORD" &&
            snapshot.authStrategy != "HTTP_BASIC"
        ) failures.add("Principal without secret")
        return DiagnosticStageResult(
            stage = "local_validation",
            success = failures.isEmpty(),
            durationMillis = 0,
            sanitizedFailure = failures.takeIf { it.isNotEmpty() }?.joinToString("; ")
        )
    }

    private fun snapshotToTarget(snapshot: DiagnosticSnapshot, generation: NacosApiGeneration): OperationTarget {
        // The diagnostic builds a target from the unapplied snapshot. This
        // target is never used to mutate shared state — it is consumed only
        // by the isolated probe and read operations below.
        val endpoint = com.nanyin.nacos.search.models.CanonicalNacosEndpoint.parse(snapshot.endpoint).getOrNull()
            ?: com.nanyin.nacos.search.models.CanonicalNacosEndpoint.parse("http://invalid").getOrThrow()
        val authMode = parseAuthMode(snapshot.authStrategy)
        val identity = com.nanyin.nacos.search.models.AccessIdentity.ofProfile(
            profileId = "diagnostic",
            accessRevision = 0,
            canonicalEndpoint = endpoint.value,
            resolvedGeneration = generation,
            authMode = authMode,
            principal = snapshot.principal.ifBlank { "<anonymous>" }
        )
        val context = com.nanyin.nacos.search.settings.NacosOperationContext(
            identity = identity,
            endpoint = endpoint,
            credential = com.nanyin.nacos.search.settings.CredentialSnapshot(snapshot.secret),
            authMode = authMode,
            profileRevision = 0,
            accessRevision = 0,
            resolvedGeneration = generation
        )
        return OperationTarget(context, snapshot.namespaceId.ifBlank { "public" })
    }

    private fun parseApiPolicy(policy: String): com.nanyin.nacos.search.models.NacosApiPolicy =
        when (policy.trim().uppercase()) {
            "V1" -> com.nanyin.nacos.search.models.NacosApiPolicy.V1
            "V3" -> com.nanyin.nacos.search.models.NacosApiPolicy.V3
            else -> com.nanyin.nacos.search.models.NacosApiPolicy.AUTO
        }

    private fun parseAuthMode(strategy: String): com.nanyin.nacos.search.settings.AuthMode = when (strategy.uppercase()) {
        "ANONYMOUS" -> com.nanyin.nacos.search.settings.AuthMode.ANONYMOUS
        "NACOS_PASSWORD", "TOKEN" -> com.nanyin.nacos.search.settings.AuthMode.NACOS_PASSWORD
        "HTTP_BASIC", "BASIC" -> com.nanyin.nacos.search.settings.AuthMode.HTTP_BASIC
        "BEARER_TOKEN" -> com.nanyin.nacos.search.settings.AuthMode.BEARER_TOKEN
        else -> com.nanyin.nacos.search.settings.AuthMode.NACOS_PASSWORD
    }

    private fun sanitize(error: Throwable): String = when (error) {
        is RemoteOperationError.Authentication -> "Authentication failed"
        is RemoteOperationError.Authorization -> "Permission denied"
        is RemoteOperationError.NotFound -> "Resource not found"
        is RemoteOperationError.GenerationUnsupported -> "Generation not supported"
        is RemoteOperationError.CapabilityUnsupported -> "Capability not supported"
        is RemoteOperationError.Server -> "Server error"
        is RemoteOperationError.RateLimited -> "Rate limited"
        is RemoteOperationError.Connection -> "Connection failed"
        is RemoteOperationError.Protocol -> "Protocol error"
        else -> "Unknown failure"
    }

    private suspend fun <T> timed(stage: String, operation: suspend () -> Result<T>): Pair<Long, Result<T>> {
        val start = clock()
        val result = operation()
        val duration = clock() - start
        return duration to result
    }
}
