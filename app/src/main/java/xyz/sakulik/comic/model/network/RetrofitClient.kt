package xyz.sakulik.comic.model.network

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 双源 API 框架极速构建单例底层引擎客户端
 */
object RetrofitClient {

    // 由外部（ViewModel）通过 collect DataStore 后注入，拦截器直接读取，无需 runBlocking
    @Volatile var cachedApiKey: String? = null
        private set

    @Volatile private var okHttpClient: OkHttpClient? = null

    /** 当 API Key 更新时调用，同时使现有客户端失效以便下次重建 */
    fun updateApiKey(key: String?) {
        if (cachedApiKey != key) {
            cachedApiKey = key
            okHttpClient = null
        }
    }

    @Synchronized
    fun getClient(context: Context): OkHttpClient {
        return okHttpClient ?: buildClient().also { okHttpClient = it }
    }

    private fun buildClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (xyz.sakulik.comic.BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BASIC
            else
                HttpLoggingInterceptor.Level.NONE
        }
        val headerInterceptor = HeaderInterceptor { cachedApiKey }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor(headerInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
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
