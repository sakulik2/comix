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
            // 对于我们的自建 Comix Server，通过 HTTP Header 注入 Token
            val token = comixTokenProvider()
            if (!token.isNullOrEmpty()) {
                builder.header("x-comix-token", token)
            }
        }
        
        return chain.proceed(builder.build())
    }
}
