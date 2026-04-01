package xyz.sakulik.comic.model.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ComicDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(comic: ComicEntity): Long

    @Update
    suspend fun update(comic: ComicEntity): Int

    @Delete
    suspend fun delete(comic: ComicEntity): Int

    @Query("SELECT * FROM comic_books WHERE uri = :uri LIMIT 1")
    suspend fun getComicByUri(uri: String): ComicEntity?

    @Query("SELECT * FROM comic_books WHERE id = :id LIMIT 1")
    suspend fun getComicById(id: Long): ComicEntity?

    @Query("SELECT * FROM comic_books WHERE location = :location LIMIT 1")
    suspend fun getComicByLocation(location: String): ComicEntity?

    @Query("SELECT * FROM comic_books")
    suspend fun getAllComicsUnordered(): List<ComicEntity>

    @Query("SELECT * FROM comic_books ORDER BY addedTime DESC")
    fun getAllComicsByAddedTimeFlow(): Flow<List<ComicEntity>>

    @Query("SELECT * FROM comic_books ORDER BY lastReadTime DESC")
    fun getAllComicsByLastReadFlow(): Flow<List<ComicEntity>>

    @Query("SELECT * FROM comic_books ORDER BY title ASC")
    fun getAllComicsByTitleFlow(): Flow<List<ComicEntity>>

    @Query("SELECT * FROM comic_books WHERE title LIKE '%' || :query || '%' ORDER BY lastReadTime DESC")
    fun searchComics(query: String): Flow<List<ComicEntity>>

    @Query("UPDATE comic_books SET currentPage = :page, totalPages = :total, lastReadTime = :time WHERE id = :id")
    suspend fun updateProgress(id: Long, page: Int, total: Int, time: Long): Int

    @Query("DELETE FROM comic_books WHERE source = :source")
    suspend fun deleteComicsBySource(source: ComicSource): Int
}
