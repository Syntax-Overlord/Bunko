package com.vayana.feature.search

import com.vayana.core.common.ApplicationScope
import com.vayana.core.common.runCatchingCancellable
import com.vayana.core.database.dao.EpubPassageDao
import com.vayana.core.database.dao.IndexedEpubFile
import com.vayana.core.database.dao.EpubChapterText
import com.vayana.core.filesystem.StorageRoots
import com.vayana.format.epub.EpubTextExtractor
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class IndexProgress(val done: Int = 0, val total: Int = 0, val failures: Int = 0) {
    val running: Boolean get() = done < total
}

@Singleton
class EpubContentIndex @Inject constructor(
    private val storageRoots: StorageRoots,
    private val dao: EpubPassageDao,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private val lock = Mutex()
    private val _progress = MutableStateFlow(IndexProgress())
    val progress = _progress.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            dao.observeLocalEpubFiles()
                .map { files -> files.map { Triple(it.bookId, it.fileHash, storageRoots.resolve(it.filePath).absolutePath) } }
                .distinctUntilChanged().collectLatest { local ->
                    lock.withLock {
                        dao.discardUnavailable()
                        val indexed = dao.indexedFiles().toHashSet()
                        val pending = local.filterNot { (id, hash, _) -> IndexedEpubFile(id, hash) in indexed }
                        _progress.value = IndexProgress(total = pending.size)
                        var failures = 0
                        pending.forEachIndexed { index, (id, hash, path) ->
                            currentCoroutineContext().ensureActive()
                            val result = runCatchingCancellable {
                                val context = currentCoroutineContext()
                                val chapters = EpubTextExtractor.extract(File(path)) { context.ensureActive() }
                                dao.replace(id, hash, chapters.asSequence().map { chapter ->
                                    EpubChapterText(chapter.href, chapter.title, chapter.text, passageRanges(chapter.text).toList())
                                })
                            }
                            if (result.isFailure) failures++
                            _progress.value = IndexProgress(index + 1, pending.size, failures)
                        }
                    }
                }
        }
    }

    fun retry() { job?.cancel(); job = null; start() }
}

/** Overlap avoids losing queries at chunk boundaries; prefer a word boundary for passage navigation. */
internal fun chunkPassage(text: String, size: Int = 1800, overlap: Int = 240): List<String> =
    passageChunks(text, size, overlap).toList()

internal fun passageChunks(text: String, size: Int = 1800, overlap: Int = 240): Sequence<String> =
    passageRanges(text, size, overlap).map { text.substring(it) }

/** [passageChunks] as ranges of [text], whitespace trimmed; blank stretches are skipped. */
internal fun passageRanges(text: String, size: Int = 1800, overlap: Int = 240): Sequence<IntRange> {
    require(size > overlap && overlap >= 0)
    return sequence {
        var start = 0
        while (start < text.length) {
            val end = minOf(start + size, text.length)
            var first = start
            var last = end - 1
            while (first <= last && text[first].isWhitespace()) first++
            while (last >= first && text[last].isWhitespace()) last--
            if (first <= last) yield(first..last)
            if (end == text.length) break
            val candidate = end - overlap
            start = text.indexOf(' ', candidate).takeIf { it in candidate until end }?.plus(1) ?: candidate
        }
    }
}
