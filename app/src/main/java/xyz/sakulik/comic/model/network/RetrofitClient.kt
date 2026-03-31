package xyz.sakulik.comic.model.network

import android.content.Context
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import xyz.sakulik.comic.model.preferences.SettingsDataStore
import java.util.concurrent.TimeUnit

/**
 * 双源 API 框架极速构建单例底层引擎客户端
 */
object RetrofitClient {

    private var okHttpClient: OkHttpClient? = null

    // 初始化 OkHttp 客户端并注入 Context 用于读取 DataStore
    @Synchronized
    fun getClient(context: Context): OkHttpClient {
        if (okHttpClient == null) {
            val logging = HttpLoggingInterceptor().apply {
                level = if (xyz.sakulik.comic.BuildConfig.DEBUG)
                    HttpLoggingInterceptor.Level.BASIC  // Debug: 只记录请求行，不倒 Body
                else
                    HttpLoggingInterceptor.Level.NONE   // Release: 完全静默
            }

            // apiKeyProvider 此处需要打破协程阻隔边界：
            // Interceptor 拦截器本身是 Java 同步体系，
            // 故借由 runBlocking 切回阻塞主线抓取出 DataStore 的最新 Flow 瞬态记录
            val headerInterceptor = HeaderInterceptor {
                runBlocking {
                    SettingsDataStore.getComicVineApiKeyFlow(context).firstOrNull()
                }
            }

            okHttpClient = OkHttpClient.Builder()
                .addInterceptor(logging)
                .addInterceptor(headerInterceptor)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
        return okHttpClient!!
    }

    /**
     * 派生 Retrofit 服务制造工厂
     */
    fun <T> createService(context: Context, baseUrl: String, serviceClass: Class<T>): T {
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(getClient(context))
            .addConverterFactory(GsonConverterFactory.create()) // 将 JSON 反序列解构委托给 Gson 引擎
            .build()
        return retrofit.create(serviceClass)
    }
}
