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
    private val METADATA_ENABLED = booleanPreferencesKey("metadata_enabled")
    private val AUTO_CLEAR_COVERS = booleanPreferencesKey("auto_clear_covers")

    /**
     * 以 cold Flow 形式订阅已保存的 API Key 变更
     */
    fun getComicVineApiKeyFlow(context: Context): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[COMIC_VINE_API_KEY]
        }
    }

    /**
     * 协程安全地保存新 API Key
     */
    suspend fun saveComicVineApiKey(context: Context, apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[COMIC_VINE_API_KEY] = apiKey.trim()
        }
    }

    /**
     * 获取云端服务器基础 URL 
     */
    fun getComicApiBaseUrlFlow(context: Context): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[COMIC_API_BASE_URL]
        }
    }

    /**
     * 保存云端服务器基础 URL
     */
    suspend fun saveComicApiBaseUrl(context: Context, baseUrl: String) {
        context.dataStore.edit { preferences ->
            preferences[COMIC_API_BASE_URL] = baseUrl.trim()
        }
    }

    /**
     * 【开关】是否显示远程书架书籍
     */
    fun getRemoteEnabledFlow(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[REMOTE_ENABLED] ?: true 
        }
    }

    suspend fun saveRemoteEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[REMOTE_ENABLED] = enabled
        }
    }

    /**
     * 【开关】是否启用元数据清洗与自动对齐
     */
    fun getMetadataEnabledFlow(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[METADATA_ENABLED] ?: true 
        }
    }

    suspend fun saveMetadataEnabled(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[METADATA_ENABLED] = enabled
        }
    }

    /**
     * 【主开关】是否在启动时自动清除持久化封面缓存（激进清理模式）
     */
    fun getAutoClearCoversFlow(context: Context): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[AUTO_CLEAR_COVERS] ?: false 
        }
    }

    suspend fun saveAutoClearCovers(context: Context, enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_CLEAR_COVERS] = enabled
        }
    }
}
