package xyz.sakulik.comic

import android.app.Application
import xyz.sakulik.comic.model.db.AppDatabase
import xyz.sakulik.comic.model.preferences.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用级全局生命周期管控器
 * 负责在启动阶段进行核心数据库、网络缓存及其他单例对象的预热与初始化
 */
class ComicApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 显式触发 Room 数据库的初始化动作
        AppDatabase.getDatabase(this)

        // [存储瘦身自愈] 每次重启 App 时全量清理运行期临时解压产生的图片缓存
        CoroutineScope(Dispatchers.IO).launch {
            try {
                //\ 1 深度清理全量 cache 目录（解决 Reader/Scanner 子文件夹堆积）
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }

                //\ 2 根据用户设置，激进清理持久化封面目录
                SettingsDataStore.getAutoClearCoversFlow(this@ComicApplication).first().let { enabled ->
                    if (enabled) {
                        val coverDir = File(filesDir, "covers")
                        if (coverDir.exists()) coverDir.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
