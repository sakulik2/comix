package xyz.sakulik.comic.model.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object DatabaseMigrations {
    val ALL = arrayOf(
        migrateLegacyTo13(
            startVersion = 4,
            issueTitle = "NULL",
            remoteSeriesId = "NULL",
            year = "NULL",
            source = "'LOCAL'",
            location = "uri",
            lastModified = "0",
            fileSize = "0"
        ),
        migrateLegacyTo13(
            startVersion = 7,
            issueTitle = "NULL",
            remoteSeriesId = "NULL",
            year = "NULL",
            source = "source",
            location = "location",
            lastModified = "0",
            fileSize = "0"
        ),
        migrateLegacyTo13(
            startVersion = 8,
            issueTitle = "NULL",
            remoteSeriesId = "NULL",
            year = "NULL",
            source = "source",
            location = "location",
            lastModified = "lastModified",
            fileSize = "fileSize"
        ),
        migrateLegacyTo13(
            startVersion = 10,
            issueTitle = "issueTitle",
            remoteSeriesId = "remoteSeriesId",
            year = "year",
            source = "source",
            location = "location",
            lastModified = "lastModified",
            fileSize = "fileSize"
        ),
        object : Migration(12, 13) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `comic_books` ADD COLUMN `customCoverPage` INTEGER")
            }
        }
    )

    private fun migrateLegacyTo13(
        startVersion: Int,
        issueTitle: String,
        remoteSeriesId: String,
        year: String,
        source: String,
        location: String,
        lastModified: String,
        fileSize: String
    ): Migration = object : Migration(startVersion, 13) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `comic_books` RENAME TO `comic_books_legacy`")
            connection.execSQL("DROP INDEX IF EXISTS `index_comic_books_location`")
            createComicBooks(connection)
            connection.execSQL(
                """
                INSERT OR IGNORE INTO `comic_books` (
                    `id`, `title`, `uri`, `extension`, `totalPages`, `currentPage`,
                    `lastReadTime`, `addedTime`, `coverCachePath`, `series`, `authors`,
                    `summary`, `genres`, `publisher`, `rating`, `region`, `format`,
                    `seriesName`, `issueTitle`, `remoteSeriesId`, `issueNumber`,
                    `volumeNumber`, `year`, `source`, `location`, `lastModified`,
                    `fileSize`, `remark`, `customCoverPage`
                )
                SELECT
                    `id`, `title`, `uri`, `extension`, `totalPages`, `currentPage`,
                    `lastReadTime`, `addedTime`, `coverCachePath`, `series`, `authors`,
                    `summary`, `genres`, `publisher`, `rating`, `region`, `format`,
                    `seriesName`, $issueTitle, $remoteSeriesId, `issueNumber`,
                    `volumeNumber`, $year, $source, $location, $lastModified,
                    $fileSize, NULL, NULL
                FROM `comic_books_legacy`
                ORDER BY `lastReadTime` DESC, `id` DESC
                """.trimIndent()
            )
            connection.execSQL("DROP TABLE `comic_books_legacy`")
            createCollections(connection)
        }
    }

    private fun createComicBooks(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `comic_books` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `uri` TEXT NOT NULL,
                `extension` TEXT NOT NULL,
                `totalPages` INTEGER NOT NULL,
                `currentPage` INTEGER NOT NULL,
                `lastReadTime` INTEGER NOT NULL,
                `addedTime` INTEGER NOT NULL,
                `coverCachePath` TEXT,
                `series` TEXT,
                `authors` TEXT,
                `summary` TEXT,
                `genres` TEXT,
                `publisher` TEXT,
                `rating` REAL,
                `region` TEXT NOT NULL,
                `format` TEXT NOT NULL,
                `seriesName` TEXT NOT NULL,
                `issueTitle` TEXT,
                `remoteSeriesId` TEXT,
                `issueNumber` REAL,
                `volumeNumber` REAL,
                `year` TEXT,
                `source` TEXT NOT NULL,
                `location` TEXT NOT NULL,
                `lastModified` INTEGER NOT NULL,
                `fileSize` INTEGER NOT NULL,
                `remark` TEXT,
                `customCoverPage` INTEGER
            )
            """.trimIndent()
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_comic_books_location` ON `comic_books` (`location`)"
        )
    }

    private fun createCollections(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `collections` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT,
                `coverComicId` INTEGER,
                `addedTime` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `collection_comic_cross_ref` (
                `collectionId` INTEGER NOT NULL,
                `comicId` INTEGER NOT NULL,
                PRIMARY KEY(`collectionId`, `comicId`),
                FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`comicId`) REFERENCES `comic_books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_collection_comic_cross_ref_collectionId` ON `collection_comic_cross_ref` (`collectionId`)"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_collection_comic_cross_ref_comicId` ON `collection_comic_cross_ref` (`comicId`)"
        )
    }
}
