package xyz.sakulik.comic.model.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val coverComicId: Long? = null, // 指定封面参考的漫画 ID
    val addedTime: Long = System.currentTimeMillis()
)
