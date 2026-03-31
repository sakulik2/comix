package xyz.sakulik.comic

import android.app.Application
import xyz.sakulik.comic.model.db.AppDatabase

import java.io.File
import xyz.sakulik.comic.model.scanner.ComicPageLoader

/**
 * 应用级全局生命周期管控器。
 * 负责在启动阶段进行核心数据库、网络缓存及其他单例对象的预热与初始化。
 */
class ComicApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 显式触发 Room 数据库的初始化动作
        AppDatabase.getDatabase(this)

        // 【冷启动大作战】 每次重启 App 时清理任何非物理崩溃导致的缓存残留
        try {
            ComicPageLoader.clearActiveCache(this)
            // 清理可能存在的扫描残留 scanner_buffer.cbr 和其他历史 tmp
            cacheDir.listFiles { _, name -> name.endsWith(".cbr") || name.endsWith(".tmp") }?.forEach { 
                it.delete() 
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
