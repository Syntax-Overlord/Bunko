package com.vayana.core.database.search

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The full-text index of every local EPUB. `epub_passages_fts` is an external-content FTS4 table whose content table,
 * `epub_passages_fts_content`, stays empty: the index holds only the words, and the text lives once, compressed, in
 * `epub_chapters`. FTS4 reads the content table only to delete, so a delete first stages the passages' text there
 * (see `EpubPassageDao.deleteForBook`). A contentless FTS4 table can't delete at all, and on a 138 MB library database
 * the plain text was most of the size.
 *
 * Room can't declare an FTS table over a content table it also writes to, so the index table is created here: on a
 * fresh install by [EpubPassageIndexCallback], on upgrade by the migration. The content table is a Room entity.
 */
object EpubPassageIndex {
    fun create(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `epub_passages_fts` USING FTS4(`text`, tokenize=unicode61, " +
                "content=`epub_passages_fts_content`)",
        )
    }

    fun compress(text: String): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(text.toByteArray(Charsets.UTF_8))
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    fun decompress(bytes: ByteArray): String {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val output = ByteArrayOutputStream(bytes.size * 3)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && !inflater.finished() && (inflater.needsInput() || inflater.needsDictionary())) {
                    error("Truncated chapter text")
                }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }
}

/** On a fresh install Room creates the passage tables from their entities but not the index table. */
object EpubPassageIndexCallback : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        EpubPassageIndex.create(connection)
    }
}
