package xyz.sakulik.comic.model.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// 定义对 Context 的扩展属性，使其作为顶层对象保证单例特性
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 聚合偏好配置存取服务组件
 */
object SettingsDataStore {
    
    // 用于保存用户自定义输入在 Settings 页的 Vine 特有 API Key
    private val COMIC_VINE_API_KEY = stringPreferencesKey("comic_vine_api_key")
    private val COMIC_API_BASE_URL = stringPreferencesKey("comic_api_base_url")
    private val REMOTE_ENABLED = booleanPreferencesKey("remote_enabled")

    /**
     * 以冷流 Flow 形式订阅 API Key 变动情况，非常契合 Compose 的体系
     */
    fun getComicVineApiKeyFlow(context: Context): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[COMIC_VINE_API_KEY]
        }
    }

    /**
     * 协程安全：将重新设值的 API Key 落盘写入
     */
    suspend fun saveComicVineApiKey(context: Context, apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[COMIC_VINE_API_KEY] = apiKey.trim()
        }
    }

    /**
     * 获取云端服务器基础 URL 地址同步流
     */
    fun getComicApiBaseUrlFlow(context: Context): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[COMIC_API_BASE_URL]
        }
    }

    /**
     * 动态设置/更新云端服务器基础 URL
     */
    suspend fun saveComicApiBaseUrl(context: Context, baseUrl: String) {
        context.dataStore.edit { preferences ->
            preferences[COMIC_API_BASE_URL] = baseUrl.trim()
        }
    }

    /**
     * 【主开关】是否启用云端流媒体相关功能
     */
    fun getRemoteEnabledFlow(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[REMOTE_ENABLED] ?: true // 默认开启
        }
    }

    suspend fun saveRemoteEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[REMOTE_ENABLED] = enabled
        }
    }
}
