package xyz.sakulik.comic.model.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Transaction
    @Query("SELECT * FROM collections ORDER BY addedTime DESC")
    fun getAllCollectionsWithComics(): Flow<List<CollectionWithComics>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: CollectionEntity): Long

    @Update
    suspend fun updateCollection(collection: CollectionEntity): Int

    @Delete
    suspend fun deleteCollection(collection: CollectionEntity): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addComicToCollection(crossRef: CollectionComicCrossRef): Long

    @Query("DELETE FROM collection_comic_cross_ref WHERE collectionId = :collectionId AND comicId = :comicId")
    suspend fun removeComicFromCollection(collectionId: Long, comicId: Long): Int

    @Query("UPDATE collections SET name = :newName WHERE id = :id")
    suspend fun updateCollectionName(id: Long, newName: String): Int

    @Query("UPDATE collections SET coverComicId = :comicId WHERE id = :id")
    suspend fun updateCollectionCover(id: Long, comicId: Long): Int
}
