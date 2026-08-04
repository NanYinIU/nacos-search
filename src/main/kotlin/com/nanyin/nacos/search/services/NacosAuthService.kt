package com.nanyin.nacos.search.services

import com.nanyin.nacos.search.models.AccessIdentity
import com.nanyin.nacos.search.settings.NacosSettings
import com.nanyin.nacos.search.settings.NacosOperationContext
import com.nanyin.nacos.search.settings.V1AuthenticationStrategy
import com.nanyin.nacos.search.services.operations.V1Authenticator
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Nacos认证服务，负责管理accessToken的获取、缓存和刷新
 */
@Service(Service.Level.APP)
class NacosAuthService : V1Authenticator {
    private val logger = thisLogger()
    private val gson = Gson()
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    
    // Token缓存
    private val tokenCache = ConcurrentHashMap<String, TokenInfo>()
    private val lastTokenRefresh = AtomicLong(0)
    private val v1Sessions = AuthenticationSessionRegistry()
    
    companion object {
        private const val LOGIN_ENDPOINT = "/nacos/v1/auth/login"
        private const val TOKEN_VALIDATION_ENDPOINT = "/nacos/v1/auth/users"
        private const val CONNECTION_TIMEOUT = 10000
        private const val READ_TIMEOUT = 30000
        private const val TOKEN_REFRESH_BUFFER_MINUTES = 5 // 提前5分钟刷新token
    }
    
    /**
     * Token信息数据类
     */
    private data class TokenInfo(
        val accessToken: String,
        val tokenTtl: Long, // TTL in seconds
        val globalAdmin: Boolean,
        val createTime: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean {
            val currentTime = System.currentTimeMillis()
            val expireTime = createTime + (tokenTtl * 1000) - (TOKEN_REFRESH_BUFFER_MINUTES * 60 * 1000)
            return currentTime >= expireTime
        }
        
        fun isValid(): Boolean {
            return accessToken.isNotBlank() && !isExpired()
        }
    }
    
    /**
     * 获取有效的accessToken
     * @return 有效的accessToken，如果获取失败返回null
     *
     * Reads live settings coordinates (not a captured operation context). Prefer
     * [getValidAccessToken] with a prepared [NacosOperationContext] for in-flight
     * operations so credentials stay off the EDT and environment switches cannot
     * retarget mid-request (issue #53).
     */
    suspend fun getValidAccessToken(): String? {
        return try {
            val serverUrl = com.nanyin.nacos.search.models.CanonicalNacosEndpoint
                .parse(settings.serverUrl).getOrNull()?.value.orEmpty()
            val username = settings.username
            val password = settings.password
            getValidAccessToken(serverUrl, username, password)
        } catch (e: Exception) {
            logger.warn("Failed to get valid access token", e)
            null
        }
    }

    /**
     * Acquires a token using only an already-captured operation context. This
     * deliberately avoids consulting mutable application settings while an
     * operation is in flight.
     */
    internal suspend fun getValidAccessToken(context: NacosOperationContext): String? {
        if (context.authenticationStrategy != V1AuthenticationStrategy.NACOS_PASSWORD) return null
        val key = AuthenticationExecutionKey(
            identity = context.identity,
            profileRevision = context.profileRevision,
            strategy = context.authenticationStrategy
        )
        return v1Sessions.getOrLogin(key) { login(context)?.toAuthenticationToken() }?.value
    }

    /**
     * Logs in for [context] and returns the token WITHOUT recording it in the
     * session registry or the legacy token cache.
     *
     * ADR-0022 requires connection diagnostics to run from unapplied settings on
     * temporary authentication state and to leave the registry untouched. They
     * still need a real token to exercise the V1 read path, so the login itself
     * stays here — this service owns the `/nacos/v1/auth/login` wire format —
     * while the caller owns the token's lifetime (see `EphemeralV1Authenticator`).
     */
    internal suspend fun loginWithoutRecording(context: NacosOperationContext): AuthenticationToken? {
        if (context.authenticationStrategy != V1AuthenticationStrategy.NACOS_PASSWORD) return null
        return login(context)?.toAuthenticationToken()
    }

    /**
     * Every identity holding V1 session state. Read-only: what an operation
     * leaves behind in the registry is otherwise invisible, and ADR-0022 makes
     * "leaves nothing behind" a requirement diagnostics have to meet.
     */
    internal fun v1SessionIdentities(): Set<AccessIdentity> = v1Sessions.trackedIdentities()

    override suspend fun accessToken(context: NacosOperationContext): String? = getValidAccessToken(context)

    private suspend fun getValidAccessToken(
        serverUrl: String,
        username: String,
        password: String
    ): String? {
        return try {
            val cacheKey = "${serverUrl}_$username"
            val cachedToken = tokenCache[cacheKey]

            if (cachedToken != null && cachedToken.isValid()) {
                logger.debug("Using cached access token")
                cachedToken.accessToken
            } else {
                logger.info("Cached token expired or invalid, requesting new token")
                val newToken = login(serverUrl, username, password)
                if (newToken != null) {
                    tokenCache[cacheKey] = newToken
                    // Evict tokens for stale server/user combinations so switching
                    // credentials does not leak access tokens for the app lifetime.
                    tokenCache.keys.retainAll { it == cacheKey }
                    lastTokenRefresh.set(System.currentTimeMillis())
                    newToken.accessToken
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to get valid access token", e)
            null
        }
    }

    /**
     * 执行登录获取accessToken
     * @return TokenInfo对象，如果登录失败返回null
     */
    private suspend fun login(): TokenInfo? {
        val serverUrl = com.nanyin.nacos.search.models.CanonicalNacosEndpoint
            .parse(settings.serverUrl).getOrNull()?.value.orEmpty()
        return login(serverUrl, settings.username, settings.password)
    }

    private suspend fun login(context: NacosOperationContext): TokenInfo? =
        login(
            serverUrl = context.endpoint.value,
            username = context.identity.principal.takeUnless { it == "<anonymous>" }.orEmpty(),
            password = context.credential.secret
        )

    /**
     * Performs the V1 login wire call. Credentials are method parameters only —
     * they never enter a data class, equality, logging, or a cache key (issue #53 /
     * ADR-0009).
     */
    private suspend fun login(serverUrl: String, username: String, password: String): TokenInfo? {
        return withContext(Dispatchers.IO) {
            try {
                if (username.isBlank() || password.isBlank()) {
                    logger.warn("Username or password is empty, cannot perform token authentication")
                    return@withContext null
                }

                val loginUrl = "${serverUrl.trimEnd('/')}/nacos/v1/auth/login"
                logger.debug("Attempting to login to: $loginUrl")

                val postData = "username=${URLEncoder.encode(username, StandardCharsets.UTF_8.name())}&password=${URLEncoder.encode(password, StandardCharsets.UTF_8.name())}"

                val response = HttpRequests.post(loginUrl, "application/x-www-form-urlencoded")
                    .connectTimeout(CONNECTION_TIMEOUT)
                    .readTimeout(READ_TIMEOUT)
                    .tuner { connection ->
                        (connection as? java.net.HttpURLConnection)?.instanceFollowRedirects = false
                        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        connection.setRequestProperty("Content-Length", postData.length.toString())
                    }
                    .connect { request ->
                        request.write(postData)
                        request.readString(null)
                    }

                logger.debug("Login response: $response")

                val jsonResponse = gson.fromJson(response, JsonObject::class.java)
                val accessToken = jsonResponse.get("accessToken")?.asString
                val tokenTtl = jsonResponse.get("tokenTtl")?.asLong ?: 18000L // 默认5小时
                val globalAdmin = jsonResponse.get("globalAdmin")?.asBoolean ?: false

                if (accessToken.isNullOrBlank()) {
                    logger.warn("Login failed: accessToken is null or empty")
                    return@withContext null
                }

                logger.info("Successfully obtained access token, TTL: ${tokenTtl}s, GlobalAdmin: $globalAdmin")
                return@withContext TokenInfo(accessToken, tokenTtl, globalAdmin)

            } catch (e: Exception) {
                logger.warn("Login failed", e)
                null
            }
        }
    }
    
    /**
     * 验证token是否有效
     * @param token 要验证的token
     * @return true如果token有效，false否则
     */
    suspend fun validateToken(token: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val validationUrl = "${settings.serverUrl.trimEnd('/')}$TOKEN_VALIDATION_ENDPOINT?accessToken=${URLEncoder.encode(token, StandardCharsets.UTF_8.name())}"
                
                HttpRequests.request(validationUrl)
                    .connectTimeout(CONNECTION_TIMEOUT)
                    .readTimeout(READ_TIMEOUT)
                    .readString()
                
                true
            } catch (e: Exception) {
                logger.debug("Token validation failed", e)
                false
            }
        }
    }
    
    /**
     * 如果需要则刷新token
     */
    suspend fun refreshTokenIfNeeded(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val cacheKey = "${settings.serverUrl}_${settings.username}"
                val cachedToken = tokenCache[cacheKey]
                
                if (cachedToken == null || cachedToken.isExpired()) {
                    logger.info("Token needs refresh")
                    val newToken = login()
                    if (newToken != null) {
                        tokenCache[cacheKey] = newToken
                        lastTokenRefresh.set(System.currentTimeMillis())
                        return@withContext true
                    }
                    return@withContext false
                }
                
                true
            } catch (e: Exception) {
                logger.warn("Failed to refresh token", e)
                false
            }
        }
    }
    
    /**
     * 清除缓存的token（登出）
     */
    fun logout() {
        try {
            val cacheKey = "${settings.serverUrl}_${settings.username}"
            tokenCache.remove(cacheKey)
            logger.info("Logged out and cleared cached token")
        } catch (e: Exception) {
            logger.warn("Error during logout", e)
        }
    }

    /**
     * Invalidates all cached tokens so the next request re-authenticates.
     * Called after a 401/403 to force a credential refresh.
     */
    fun invalidateToken() {
        tokenCache.clear()
        logger.info("Invalidated all cached tokens")
    }

    internal fun invalidateToken(context: NacosOperationContext) {
        v1Sessions.invalidate(context.identity)
        val principal = context.identity.principal.takeUnless { it == "<anonymous>" }.orEmpty()
        tokenCache.remove("${context.endpoint.value}_$principal")
        logger.info("Invalidated token for captured access context")
    }

    override fun invalidate(context: NacosOperationContext) = invalidateToken(context)

    /**
     * 检查当前是否有有效的token
     */
    fun isTokenValid(): Boolean {
        val cacheKey = "${settings.serverUrl}_${settings.username}"
        val cachedToken = tokenCache[cacheKey]
        return cachedToken?.isValid() == true
    }
    
    /**
     * 检查是否配置了token认证
     */
    fun isTokenAuthConfigured(): Boolean {
        return settings.username.isNotBlank() && settings.password.isNotBlank() && settings.serverUrl.isNotBlank()
    }
    
    /**
     * 获取token状态信息
     */
   fun getTokenStatus(): String {
       val cacheKey = "${settings.serverUrl}_${settings.username}"
       val cachedToken = tokenCache[cacheKey]
       
        return when {
            cachedToken == null -> "No token cached"
            cachedToken == null -> "No token cached"
            cachedToken.isValid() -> "Token valid (expires in ${(cachedToken.createTime + cachedToken.tokenTtl * 1000 - System.currentTimeMillis()) / 1000}s)"
            cachedToken.isExpired() -> "Token expired"
           else -> "Token invalid"
       }
   }

    /** Test/inspection helper: the set of cached token keys. */
    internal fun tokenCacheKeys(): Set<String> = tokenCache.keys.toSet()
    
    /**
     * 构建登录URL
     */
    private fun buildLoginUrl(): String {
        return "${settings.serverUrl.trimEnd('/')}$LOGIN_ENDPOINT"
    }

    private fun TokenInfo.toAuthenticationToken(): AuthenticationToken = AuthenticationToken(
        value = accessToken,
        expiresAtMillis = createTime + tokenTtl * 1000 - TOKEN_REFRESH_BUFFER_MINUTES * 60 * 1000
    )
}
