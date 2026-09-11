package xyz.sakulik.comic.model.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import xyz.sakulik.comic.R
import xyz.sakulik.comic.utils.LocalizedThrowable
import xyz.sakulik.comic.utils.UiText

/**
 * HTTP 通信管线拦截器：
 * 负责强仿正常浏览器行为，并且通过回调闭包动态为指定域名目标拦截与注入鉴权 Token
 */
/** 拒绝向公网 HTTP 发送请求；错误文案由 UI 层解析。 */
class PublicHttpRejectedException : IOException("refusing plaintext HTTP request to a public host"), LocalizedThrowable {
    override val uiText = UiText.Res(R.string.error_public_http_rejected)
}

class HeaderInterceptor(
    private val comicVineKeyProvider: () -> String?,
    private val comixTokenProvider: () -> String?,
    private val comixBaseUrlProvider: () -> String?
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val builder = originalRequest.newBuilder()
            .removeHeader("x-comix-token")

        // 全局通用的 UA伪装
        builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        val httpUrl = originalRequest.url
        if (httpUrl.host == "comicvine.gamespot.com") {
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
            val isComix = ComixEndpointPolicy.isComixApiRequest(httpUrl, comixBaseUrlProvider())

            if (isComix) {
                if (httpUrl.scheme == "http" && !ComixEndpointPolicy.isPrivateLanHost(httpUrl.host)) {
                    throw PublicHttpRejectedException()
                }
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
