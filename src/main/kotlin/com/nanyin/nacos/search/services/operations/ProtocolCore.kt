package com.nanyin.nacos.search.services.operations

import com.nanyin.nacos.search.services.AuthenticationExecutionKey
import com.nanyin.nacos.search.services.AuthenticationSessionRegistry
import com.nanyin.nacos.search.services.AuthenticationToken
import com.nanyin.nacos.search.settings.V1AuthenticationStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Authentication material attached to one protocol request.
 *
 * Generation-neutral: both dialects place the same ANONYMOUS / HTTP_BASIC /
 * BEARER / NACOS_PASSWORD credentials the same way; only login wire and
 * response classification differ.
 */
data class RequestAuthentication(
    val query: List<Pair<String, String>> = emptyList(),
    val headers: Map<String, String> = emptyMap()
)

/**
 * Dialect classification of one wire response into the shared failure
 * vocabulary. [ProtocolCore] owns what each case *means* (recover once,
 * surface as product failure, never retry, etc.).
 */
sealed class ClassifiedResponse {
    /** Wire success — the caller parses the body. */
    data class Success(val response: ProtocolResponse) : ClassifiedResponse()

    /** Non-recoverable failure already mapped by the dialect. */
    data class Failure(val error: RemoteOperationError) : ClassifiedResponse()

    /**
     * Classified expired/invalid NACOS_PASSWORD token. The core invalidates
     * the session and replays an idempotent exchange at most once (ADR-0011).
     */
    data class RecoverableTokenRefusal(val status: Int) : ClassifiedResponse()
}

/**
 * The one protocol execution shell (ADR-0048 / issue #97).
 *
 * Owns target-agnostic authentication application, the request budget,
 * transport dispatch, the single permitted authentication recovery for
 * idempotent exchanges, cancellation preservation, and the meaning of
 * [ClassifiedResponse]. Dialects supply request construction, login wire,
 * and response classification only. Used by generation probing (#97) and
 * configuration browsing reads (#98).
 *
 * Transport-level retries remain inside [ProtocolTransport] (ADR-0021); this
 * budget is the outer deadline that login, those retries, and one auth
 * replay must all share.
 */
class ProtocolCore(
    private val transport: ProtocolTransport,
    private val sessions: AuthenticationSessionRegistry,
    private val budgetMillis: Long = DEFAULT_READ_BUDGET_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /**
     * Runs one idempotent exchange under a single request budget.
     *
     * CancellationException propagates unchanged and is never mapped to a
     * connection or protocol failure. A [ClassifiedResponse.RecoverableTokenRefusal]
     * triggers at most one invalidate + re-login + replay; a second refusal
     * becomes [RemoteOperationError.InvalidOrExpiredNacosPasswordToken].
     */
    suspend fun <T> executeIdempotent(
        target: OperationTarget,
        build: (RequestAuthentication) -> ProtocolRequest,
        classify: (ProtocolResponse) -> ClassifiedResponse,
        parse: (ProtocolResponse) -> T,
        login: suspend (OperationTarget) -> AuthenticationToken?
    ): Result<T> {
        return try {
            Result.success(runIdempotent(target, build, classify, parse, login))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private suspend fun <T> runIdempotent(
        target: OperationTarget,
        build: (RequestAuthentication) -> ProtocolRequest,
        classify: (ProtocolResponse) -> ClassifiedResponse,
        parse: (ProtocolResponse) -> T,
        login: suspend (OperationTarget) -> AuthenticationToken?
    ): T {
        val deadline = clock() + budgetMillis
        try {
            val first = dispatch(build(authenticationFor(target, deadline, login)), deadline)
            val response = when (val classified = classify(first)) {
                is ClassifiedResponse.Success -> classified.response
                is ClassifiedResponse.Failure -> throw classified.error
                is ClassifiedResponse.RecoverableTokenRefusal -> {
                    requireNacosPassword(target)
                    sessions.invalidate(target.context.identity)
                    val second = dispatch(build(authenticationFor(target, deadline, login)), deadline)
                    when (val replayed = classify(second)) {
                        is ClassifiedResponse.Success -> replayed.response
                        is ClassifiedResponse.Failure -> throw replayed.error
                        is ClassifiedResponse.RecoverableTokenRefusal ->
                            throw RemoteOperationError.InvalidOrExpiredNacosPasswordToken(replayed.status)
                    }
                }
            }
            return parse(response)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RemoteOperationError) {
            throw error
        } catch (error: Throwable) {
            throw RemoteOperationError.Connection(error)
        }
    }

    private fun requireNacosPassword(target: OperationTarget) {
        require(target.context.authenticationStrategy == V1AuthenticationStrategy.NACOS_PASSWORD) {
            "Recoverable token refusal is only defined for NACOS_PASSWORD"
        }
    }

    private suspend fun authenticationFor(
        target: OperationTarget,
        deadline: Long,
        login: suspend (OperationTarget) -> AuthenticationToken?
    ): RequestAuthentication = when (target.context.authenticationStrategy) {
        V1AuthenticationStrategy.ANONYMOUS -> RequestAuthentication()
        V1AuthenticationStrategy.NACOS_PASSWORD -> {
            val token = withinBudget(deadline) {
                sessions.getOrLogin(AuthenticationExecutionKey(target.context)) { login(target) }?.value
            } ?: throw RemoteOperationError.Authentication(401)
            RequestAuthentication(query = listOf("accessToken" to token))
        }
        V1AuthenticationStrategy.HTTP_BASIC -> {
            val credentials = "${target.context.identity.principal}:${target.context.credential.secret}"
            val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
            RequestAuthentication(headers = mapOf("Authorization" to "Basic $encoded"))
        }
        V1AuthenticationStrategy.BEARER_TOKEN -> RequestAuthentication(
            headers = mapOf("Authorization" to "Bearer ${target.context.credential.secret}")
        )
    }

    private suspend fun dispatch(request: ProtocolRequest, deadline: Long): ProtocolResponse =
        withinBudget(deadline) { transport.execute(request) }

    private suspend fun <T> withinBudget(deadline: Long, operation: suspend () -> T): T {
        val remaining = deadline - clock()
        if (remaining <= 0) throw RemoteOperationError.Protocol("Request budget exhausted")
        return withTimeout(remaining) { operation() }
    }

    companion object {
        const val DEFAULT_READ_BUDGET_MILLIS = 30_000L
    }
}
