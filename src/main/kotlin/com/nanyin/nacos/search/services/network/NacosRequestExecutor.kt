package com.nanyin.nacos.search.services.network

import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * Executes a single HTTP GET against a Nacos endpoint with bounded, cancellable
 * retries. The transport, clock, and jitter provider are injected so tests run
 * deterministically without real sockets or [Thread.sleep].
 *
 * The executor never logs or surfaces Authorization headers or accessToken
 * parameters — error messages carry only status codes and generic category text.
 */
class NacosRequestExecutor(
    private val transport: HttpTransport = DefaultHttpTransport,
    private val clock: () -> Long = System::currentTimeMillis,
    private val jitterProvider: () -> Long = { DEFAULT_JITTER_MS }
) {

    fun interface HttpTransport {
        /**
         * Performs one HTTP GET. Returns the raw body on 2xx.
         * Throws [NacosRequestError] subtypes for classified failures.
         */
        @Throws(NacosRequestError::class)
        fun get(request: TransportRequest): String
    }

    data class TransportRequest(
        val url: String,
        val connectTimeoutMs: Int,
 val readTimeoutMs: Int,
        val authHeaders: Map<String, String>,
        val attempt: Int
    )

    /**
     * Executes a GET request under [policy], retrying only retriable failures
     * (connection, read-timeout, server 5xx, rate-limit 429) up to
     * [RequestPolicy.maxAttempts]. Non-retriable errors (4xx except 429,
     * protocol, auth) surface immediately.
     */
    suspend fun get(
        url: String,
        policy: RequestPolicy,
        authHeaders: Map<String, String> = emptyMap()
   ): String = coroutineScope {
       val deadline = clock() + policy.totalBudgetMs

       withTimeout(policy.totalBudgetMs) {
           var lastError: NacosRequestError? = null
           for (attempt in 1..policy.maxAttempts) {
               coroutineContext.ensureActive()
               val remaining = deadline - clock()
               if (remaining <= 0) {
                   throw lastError ?: NacosRequestError.Protocol("Request budget exhausted")
               }

               val request = TransportRequest(
                   url = url,
                   connectTimeoutMs = policy.connectTimeoutMs,
                   readTimeoutMs = policy.readTimeoutMs,
                   authHeaders = authHeaders,
                   attempt = attempt
               )

               try {
                   return@withTimeout transport.get(request)
               } catch (ce: kotlinx.coroutines.CancellationException) {
                   throw ce
               } catch (e: NacosRequestError) {
                   lastError = e
                   if (!isRetriable(e) || attempt >= policy.maxAttempts) {
                       throw e
                   }
                   val backoff = jitterProvider()
                   delay(backoff)
               }
           }
           throw lastError ?: NacosRequestError.Protocol("No attempt completed")
       }
   }

   private fun isRetriable(error: NacosRequestError): Boolean = when (error) {
       is NacosRequestError.ConnectTimeout -> true
        is NacosRequestError.ReadTimeout -> true
        is NacosRequestError.Connection -> true
        is NacosRequestError.Server -> true
        is NacosRequestError.RateLimited -> true
        is NacosRequestError.Authentication -> false
        is NacosRequestError.Client -> false
        is NacosRequestError.Protocol -> false
    }

    companion object {
        private const val DEFAULT_JITTER_MS = 250L
    }
}

/**
 * Production transport backed by IntelliJ [com.intellij.util.io.HttpRequests].
 * Preserves the second-attempt compatibility headers (Connection: close,
 * Accept-Encoding: identity) that work around broken chunked-encoding on
 * some Nacos servers / reverse proxies.
 */
object DefaultHttpTransport : NacosRequestExecutor.HttpTransport {

    override fun get(request: NacosRequestExecutor.TransportRequest): String {
        try {
            return com.intellij.util.io.HttpRequests
                .request(request.url)
                .connectTimeout(request.connectTimeoutMs)
                .readTimeout(request.readTimeoutMs)
                .tuner { connection ->
                    // A redirect can silently cross an origin boundary. Endpoint
                    // validation establishes the origin up front, so transport must
                    // never follow a later redirect.
                    (connection as? java.net.HttpURLConnection)?.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "application/json")
                    if (request.attempt > 1) {
                        connection.setRequestProperty("Connection", "close")
                        connection.setRequestProperty("Accept-Encoding", "identity")
                    }
                    request.authHeaders.forEach { (key, value) ->
                        connection.setRequestProperty(key, value)
                    }
                }
                .readString()
        } catch (e: java.net.SocketTimeoutException) {
            if (e.message?.contains("connect") == true) {
                throw NacosRequestError.ConnectTimeout(e)
            }
            throw NacosRequestError.ReadTimeout(e)
        } catch (e: java.net.ConnectException) {
            throw NacosRequestError.Connection(e)
        } catch (e: java.io.IOException) {
            val status = extractStatus(e)
            if (status != null) {
                throw classifyStatus(status, e.message ?: "")
            }
            throw NacosRequestError.Connection(e)
        }
    }

    private fun extractStatus(e: java.io.IOException): Int? {
        // IntelliJ HttpRequests wraps status codes in its own exception message
        val match = Regex("(?:HTTP |status )?(\\d{3})").find(e.message ?: "")
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun classifyStatus(status: Int, body: String): NacosRequestError {
        return when (status) {
            401, 403 -> NacosRequestError.Authentication(status)
            429 -> NacosRequestError.RateLimited(null)
            in 400..499 -> NacosRequestError.Client(status, sanitizeBody(body))
            in 500..599 -> NacosRequestError.Server(status, sanitizeBody(body))
            else -> NacosRequestError.Protocol("Unexpected status $status")
        }
    }

    /** Strip anything that looks like a credential from upstream error body. */
    private fun sanitizeBody(body: String): String {
        return body
            .replace(Regex("(?i)(Authorization)[:\\s]*[^\\s,]+"), "$1: ***")
            .replace(Regex("(?i)(accessToken)[=]&?[^&\\s]+"), "$1=***")
            .take(500)
    }
}
