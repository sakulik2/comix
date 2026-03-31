package xyz.sakulik.comic.model.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * HTTP 通信管线拦截器：
 * 负责强仿正常浏览器行为，并且通过回调闭包动态为指定域名目标拦截与注入鉴权 Token
 */
class HeaderInterceptor(private val apiKeyProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val builder = originalRequest.newBuilder()

        // 核心！全局通用的 UA伪装：主流浏览器头，绕过更严苛的反爬拦截（因为有些站台会将自写的 UA 判为脚本拦截）
        builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        // 智能判断：检测出爬虫目标是高阶域名的，拦截下来后注入用户的合法 API Key (针对 ComicVine 服务)
        val httpUrl = originalRequest.url
        if (httpUrl.host.contains("comicvine.gamespot.com")) {
            val key = apiKeyProvider()
            if (!key.isNullOrEmpty()) {
                val newUrl = httpUrl.newBuilder()
                    .addQueryParameter("api_key", key)
                    .build()
                builder.url(newUrl)
            }
        }
        
        return chain.proceed(builder.build())
    }
}
