package com.nanyin.nacos.search.services.network

/**
 * Typed failure vocabulary for all Nacos HTTP interactions.
 *
 * Every subclass avoids logging or exposing Authorization headers and
 * accessToken parameters so secrets are never leaked in error messages.
 */
sealed class NacosRequestError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ConnectTimeout(cause: Throwable) : NacosRequestError("Connection timed out", cause)
    class ReadTimeout(cause: Throwable) : NacosRequestError("Read timed out", cause)
    class Connection(cause: Throwable) : NacosRequestError("Connection failed", cause)
    /**
     * Authn/authz HTTP failure. [body] is a **sanitized** upstream response body
     * (credentials stripped, length-capped) so protocol adapters can classify
     * refused tokens vs permission denials. It must never carry secrets.
     */
    data class Authentication(val status: Int, val body: String = "") : NacosRequestError("Authentication failed")
    data class RateLimited(val retryAfterMs: Long?) : NacosRequestError("Rate limited")
    data class Client(val status: Int, val body: String) : NacosRequestError("Client error $status")
    data class Server(val status: Int, val body: String) : NacosRequestError("Server error $status")
    class Protocol(message: String, cause: Throwable? = null) : NacosRequestError(message, cause)
}
