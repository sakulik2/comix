package xyz.sakulik.comic

import android.app.Application
import xyz.sakulik.comic.model.db.AppDatabase

import java.io.File

/**
 * 应用级全局生命周期管控器。
 * 负责在启动阶段进行核心数据库、网络缓存及其他单例对象的预热与初始化。
 */
class ComicApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 显式触发 Room 数据库的初始化动作
        AppDatabase.getDatabase(this)

        // 每次重启 App 时清理任何非物理崩溃导致的缓存残留
        try {
            // 清理所有 reader_ 开头的缓存文件、scanner_buffer 以及其他历史 .cbr/.tmp 残留
            cacheDir.listFiles { _, name -> 
                name.startsWith("reader_") || 
                name.startsWith("scanner_") || 
                name.endsWith(".cbr") || 
                name.endsWith(".tmp") 
            }?.forEach { 
                it.delete() 
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
