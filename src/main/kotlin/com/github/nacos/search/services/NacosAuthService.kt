package com.github.nacos.search.services

import com.github.nacos.search.settings.NacosSettings
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
class NacosAuthService {
    private val logger = thisLogger()
    private val gson = Gson()
    private val settings = ApplicationManager.getApplication().getService(NacosSettings::class.java)
    
    // Token缓存
    private val tokenCache = ConcurrentHashMap<String, TokenInfo>()
    private val lastTokenRefresh = AtomicLong(0)
    
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
     */
    suspend fun getValidAccessToken(): String {
            try {
                val cacheKey = "${settings.serverUrl}_${settings.username}"
                val cachedToken = tokenCache[cacheKey]
                
                // 检查缓存的token是否有效
                if (cachedToken != null && cachedToken.isValid()) {
                    logger.debug("Using cached access token")
                    return cachedToken.accessToken;
                }
                
                // 缓存无效，重新登录获取token
                logger.info("Cached token expired or invalid, requesting new token")
                val newToken = login()
                if (newToken != null) {
                    tokenCache[cacheKey] = newToken
                    lastTokenRefresh.set(System.currentTimeMillis())
                    return newToken.accessToken
                }
                
                null
            } catch (e: Exception) {
                logger.error("Failed to get valid access token", e)
                null
            }
        return ""
    }
    
    /**
     * 执行登录获取accessToken
     * @return TokenInfo对象，如果登录失败返回null
     */
    private suspend fun login(): TokenInfo? {
        return withContext(Dispatchers.IO) {
            try {
                if (settings.username.isBlank() || settings.password.isBlank()) {
                    logger.warn("Username or password is empty, cannot perform token authentication")
                    return@withContext null
                }
                
                val loginUrl = buildLoginUrl()
                logger.debug("Attempting to login to: $loginUrl")
                
                 // val postData = "username=${URLEncoder.encode(settings.username, StandardCharsets.UTF_8.name())}&password=${URLEncoder.encode(settings.password, StandardCharsets.UTF_8.name())}"
                val postData = "username=${settings.username}&password=${settings.password}"

                val response = HttpRequests.post(loginUrl, "application/x-www-form-urlencoded")
                    .connectTimeout(CONNECTION_TIMEOUT)
                    .readTimeout(READ_TIMEOUT)
                    .tuner { connection ->
                        // 设置请求头
                        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        connection.setRequestProperty("Content-Length", postData.length.toString())
                    }
                    .connect { request ->
                        // 2. 在 connect 的 lambda 中执行 I/O 操作

                        // 步骤 A: 写入（发送）POST 数据
                        request.write(postData)

                        // 步骤 B: 读取服务器的响应
                        // lambda 的最后一个表达式的值将作为 connect 方法的返回值
                        request.readString(null)
                    }
                
                logger.debug("Login response: $response")
                
                val jsonResponse = gson.fromJson(response, JsonObject::class.java)
                val accessToken = jsonResponse.get("accessToken")?.asString
                val tokenTtl = jsonResponse.get("tokenTtl")?.asLong ?: 18000L // 默认5小时
                val globalAdmin = jsonResponse.get("globalAdmin")?.asBoolean ?: false
                
                if (accessToken.isNullOrBlank()) {
                    logger.error("Login failed: accessToken is null or empty")
                    return@withContext null
                }
                
                logger.info("Successfully obtained access token, TTL: ${tokenTtl}s, GlobalAdmin: $globalAdmin")
                return@withContext TokenInfo(accessToken, tokenTtl, globalAdmin)
                
            } catch (e: Exception) {
                logger.error("Login failed", e)
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
                logger.error("Failed to refresh token", e)
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
            logger.error("Error during logout", e)
        }
    }
    
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
            cachedToken.isValid() -> "Token valid (expires in ${(cachedToken.createTime + cachedToken.tokenTtl * 1000 - System.currentTimeMillis()) / 1000}s)"
            cachedToken.isExpired() -> "Token expired"
            else -> "Token invalid"
        }
    }
    
    /**
     * 构建登录URL
     */
    private fun buildLoginUrl(): String {
        return "${settings.serverUrl.trimEnd('/')}$LOGIN_ENDPOINT"
    }
}