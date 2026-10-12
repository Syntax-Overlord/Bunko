package com.vayana.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One spine document of an indexed EPUB, its plain text Deflate-compressed ([com.vayana.core.database.search.EpubPassageIndex]).
 * The text is kept only to show result excerpts and to remove the chapter's words from the full-text index exactly.
 * A disposable, device-local index: never included in backups or cloud snapshots.
 *
 * No foreign key to `books`: a cascade would delete the text the index needs to forget those words. Chapters of a book
 * that is gone or whose file changed are removed by `EpubPassageDao.discardUnavailable`.
 */
@Entity(tableName = "epub_chapters", indices = [Index("bookId")])
class EpubChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val fileHash: String,
    val href: String,
    val title: String,
    val compressedText: ByteArray,
)

/** A searchable stretch of a chapter: `[startOffset, endOffset)` of its text. Its id is its `epub_passages_fts` docid. */
@Entity(tableName = "epub_passages", foreignKeys = [ForeignKey(
    entity = EpubChapterEntity::class, parentColumns = ["id"], childColumns = ["chapterId"], onDelete = ForeignKey.CASCADE,
)], indices = [Index("chapterId")])
data class EpubPassageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chapterId: Long,
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * The content table of `epub_passages_fts`, empty between operations. Passage text is written here only so one
 * statement can index it (`INSERT … SELECT`) or so FTS4 can read the words it must delete; then it is cleared.
 * [id] is the passage id, the row's rowid and the docid in the index.
 */
@Entity(tableName = "epub_passages_fts_content")
class EpubPassageTextEntity(@PrimaryKey val id: Long, val text: String)
