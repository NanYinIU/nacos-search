package com.nanyin.nacos.search.services.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.ConcurrentHashMap

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

        /**
         * Performs one HTTP POST. Returns the raw body on 2xx.
         * Throws [NacosRequestError] subtypes for classified failures.
         */
        @Throws(NacosRequestError::class)
        fun post(request: TransportRequest): String = throw UnsupportedOperationException("POST not supported")
    }

    data class TransportRequest(
        val url: String,
        val connectTimeoutMs: Int,
 val readTimeoutMs: Int,
        val authHeaders: Map<String, String>,
        val attempt: Int,
        val postBody: String? = null
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
                   return@withTimeout invokeCancellable { transport.get(request) }
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

    /**
     * Executes a POST request under [policy]. POST is never retried because
     * a write may have left the client after a timeout or disconnect.
     */
    suspend fun post(
        url: String,
        body: String,
        policy: RequestPolicy,
        authHeaders: Map<String, String> = emptyMap()
    ): String = coroutineScope {
        withTimeout(policy.totalBudgetMs) {
            invokeCancellable {
                transport.post(TransportRequest(
                    url = url,
                    connectTimeoutMs = policy.connectTimeoutMs,
                    readTimeoutMs = policy.readTimeoutMs,
                    authHeaders = authHeaders,
                    attempt = 1,
                    postBody = body
                ))
            }
        }
    }

    private suspend fun invokeCancellable(block: () -> String): String =
        suspendCancellableCoroutine { continuation ->
            val thread = Thread({
                try {
                    continuation.resume(block())
                } catch (error: Throwable) {
                    continuation.resumeWithException(
                        HttpCallGuard.cancellationOr(error)
                    )
                }
            }, "nacos-http")
            thread.start()
            continuation.invokeOnCancellation {
                HttpCallGuard.cancel(thread)
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
 * Production transport backed by [java.net.HttpURLConnection] for both GET and
 * POST. IntelliJ `HttpRequests` is deliberately not used: it discards the
 * response body of a non-2xx, and protocol adapters need that body to tell a
 * refused-or-expired token from a permission denial (issue #39).
 *
 * Preserves the second-attempt compatibility headers (Connection: close,
 * Accept-Encoding: identity) that work around broken chunked-encoding on
 * some Nacos servers / reverse proxies.
 */
object DefaultHttpTransport : NacosRequestExecutor.HttpTransport {

    override fun get(request: NacosRequestExecutor.TransportRequest): String {
        // HttpURLConnection (not IntelliJ HttpRequests) so non-2xx responses expose
        // their real body. Adapters need that body to classify refused tokens vs
        // permission denials; discarding it made V1 recovery unreachable in production.
        val conn = (java.net.URL(request.url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            // A redirect can silently cross an origin boundary. Endpoint validation
            // establishes the origin up front, so transport must never follow later.
            instanceFollowRedirects = false
            connectTimeout = request.connectTimeoutMs
            readTimeout = request.readTimeoutMs
            setRequestProperty("Accept", "application/json")
            // Second-attempt compatibility headers work around broken chunked
            // encoding on some Nacos servers / reverse proxies.
            if (request.attempt > 1) {
                setRequestProperty("Connection", "close")
                setRequestProperty("Accept-Encoding", "identity")
            }
            request.authHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return execute(conn, writeBody = null)
    }

    override fun post(request: NacosRequestExecutor.TransportRequest): String {
        val conn = (java.net.URL(request.url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = request.connectTimeoutMs
            readTimeout = request.readTimeoutMs
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Connection", "close")
            request.authHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        return execute(conn, writeBody = request.postBody ?: "")
    }

    private fun execute(conn: java.net.HttpURLConnection, writeBody: String?): String {
        HttpCallGuard.bind(conn)
        try {
            if (writeBody != null) {
                conn.outputStream.use { it.write(writeBody.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                throw classifyStatus(status, body)
            }
            return body
        } catch (e: java.net.SocketTimeoutException) {
            HttpCallGuard.throwIfCancelled(e)
            if (e.message?.contains("connect") == true) {
                throw NacosRequestError.ConnectTimeout(e)
            }
            throw NacosRequestError.ReadTimeout(e)
        } catch (e: java.io.InterruptedIOException) {
            throw CancellationException("http cancelled", e)
        } catch (e: java.net.ConnectException) {
            HttpCallGuard.throwIfCancelled(e)
            throw NacosRequestError.Connection(e)
        } catch (e: java.io.IOException) {
            HttpCallGuard.throwIfCancelled(e)
            val status = extractStatus(e)
            if (status != null) {
                throw classifyStatus(status, e.message ?: "")
            }
            throw NacosRequestError.Connection(e)
        } finally {
            HttpCallGuard.unbind(conn)
        }
    }

    private fun extractStatus(e: java.io.IOException): Int? {
        val match = Regex("(?:HTTP |status )?(\\d{3})").find(e.message ?: "")
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun classifyStatus(status: Int, body: String): NacosRequestError {
        return when (status) {
            // Carry a sanitized body so adapters can classify refused tokens vs
            // permission denials (ADR-0011). Empty body stays empty — never invent
            // evidence the wire did not provide.
            401, 403 -> NacosRequestError.Authentication(status, sanitizeBody(body))
            429 -> NacosRequestError.RateLimited(null)
            in 400..499 -> NacosRequestError.Client(status, sanitizeBody(body))
            in 500..599 -> NacosRequestError.Server(status, sanitizeBody(body))
            else -> NacosRequestError.Protocol("Unexpected status $status")
        }
    }

    /**
     * Strip anything that looks like a credential from an upstream error body.
     *
     * The masked run stops at `"` and `}` as well as whitespace and `,`: this
     * body is now parsed, not just logged, so swallowing the delimiters of a
     * JSON error envelope would turn a classifiable authentication failure into
     * an unparseable one (issue #39).
     */
    private fun sanitizeBody(body: String): String {
        return body
            .replace(Regex("(?i)(authorization)[\"\\s:=]*[^\"\\s,}]*"), "$1: ***")
            .replace(Regex("(?i)(accesstoken)[\"\\s:=&]*[^\"\\s,&}]*"), "$1=***")
            .take(500)
    }
}

/**
 * Lets coroutine cancellation disconnect the thread currently blocked in
 * [HttpURLConnection]. [Thread.interrupt] alone does not unblock that API.
 */
internal object HttpCallGuard {
    private val connections = ConcurrentHashMap<Thread, java.net.HttpURLConnection>()
    private val cancelled = ConcurrentHashMap.newKeySet<Thread>()

    fun bind(conn: java.net.HttpURLConnection) {
        connections[Thread.currentThread()] = conn
    }

    fun unbind(conn: java.net.HttpURLConnection) {
        connections.remove(Thread.currentThread(), conn)
    }

    fun cancel(thread: Thread) {
        cancelled.add(thread)
        connections.remove(thread)?.disconnect()
        thread.interrupt()
    }

    fun throwIfCancelled(cause: Throwable) {
        if (Thread.currentThread().isInterrupted || cancelled.remove(Thread.currentThread())) {
            throw CancellationException("http cancelled", cause)
        }
    }

    fun cancellationOr(error: Throwable): Throwable {
        if (error is CancellationException) return error
        if (Thread.currentThread().isInterrupted || cancelled.remove(Thread.currentThread())) {
            return CancellationException("http cancelled", error)
        }
        return error
    }
}
