package com.vayana.feature.reader

import androidx.room.withTransaction
import com.vayana.core.database.VayanaDatabase
import com.vayana.core.database.model.AnnotationType
import com.vayana.core.database.repository.AnnotationRepository
import com.vayana.core.database.repository.BookRepository
import com.vayana.reader.api.Locator

/** Save completion, the optional rating and an ordinary journal note as one operation, safe to retry on failure. */
internal suspend fun finishBookWithReflection(
    database: VayanaDatabase,
    books: BookRepository,
    annotations: AnnotationRepository,
    bookId: Long,
    rating: Float?,
    reflection: String,
    locator: Locator?,
) {
    val body = reflection.trim().take(4000)
    database.withTransaction {
        val book = books.getById(bookId) ?: error("Book is no longer available")
        books.markFinished(bookId)
        if (rating != null) books.updateRating(bookId, rating)
        if (body.isNotEmpty()) {
            val position = locator?.cfi?.takeIf { it.isNotBlank() }
                ?: book.lastLocator?.takeIf { it.isNotBlank() }
                ?: error("Reading position unavailable")
            annotations.create(
                bookId = bookId,
                type = AnnotationType.NOTE,
                colorKey = "journal",
                locator = position,
                chapterTitle = locator?.chapterTitle,
                chapterHref = locator?.href,
                selectedText = "",
                readerNote = body,
            )
        }
    }
}
