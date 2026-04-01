package xyz.sakulik.comic.model.db

/**
 * 漫画来源标识：本地档案 (CBR/CBZ/PDF) 或 云端流媒体
 */
enum class ComicSource {
    LOCAL,     // 本地存储，location 为文件路径或 URI
    REMOTE     // 远程服务器，location 为 Comic ID 或基础 URL
}
