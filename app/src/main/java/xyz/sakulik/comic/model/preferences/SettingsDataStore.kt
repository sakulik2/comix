package xyz.sakulik.comic.model.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
}
