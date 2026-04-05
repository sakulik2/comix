package xyz.sakulik.comic.model.db

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class CollectionWithComics(
    @Embedded val collection: CollectionEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = CollectionComicCrossRef::class,
            parentColumn = "collectionId",
            entityColumn = "comicId"
        )
    )
    val comics: List<ComicEntity>
)
