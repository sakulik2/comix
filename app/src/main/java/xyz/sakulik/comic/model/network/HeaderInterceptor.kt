package xyz.sakulik.comic.model.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * HTTP 通信管线拦截器：
 * 负责强仿正常浏览器行为，并且通过回调闭包动态为指定域名目标拦截与注入鉴权 Token
 */
class HeaderInterceptor(
    private val comicVineKeyProvider: () -> String?,
    private val comixTokenProvider: () -> String?
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val builder = originalRequest.newBuilder()

        // 全局通用的 UA伪装
        builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        val httpUrl = originalRequest.url
        if (httpUrl.host.contains("comicvine.gamespot.com")) {
            // 智能判断：针对 ComicVine 注入 Query 参数 API Key
            val key = comicVineKeyProvider()
            if (!key.isNullOrEmpty()) {
                val newUrl = httpUrl.newBuilder()
                    .addQueryParameter("api_key", key)
                    .build()
                builder.url(newUrl)
            }
        } else {
            val token = comixTokenProvider()
            val path = httpUrl.encodedPath
            // 侦测请求是否属于 Comix 协议（路径中包含 /api/comics 或 /api/scan）
            val isComix = path.contains("/api/comics") || path.contains("/api/scan")

            if (isComix) {
                if (!token.isNullOrEmpty()) {
                    builder.header("x-comix-token", token)
                }
                // 仅对 Comix 协议请求，覆盖并注入专属的 UA
                builder.header("User-Agent", "comix/1.7.2 (Android; Mobile)")
            }
        }
        
        return chain.proceed(builder.build())
    }
}
