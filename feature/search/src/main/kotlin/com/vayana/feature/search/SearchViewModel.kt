package com.vayana.feature.search

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vayana.core.database.model.Annotation
import com.vayana.core.database.model.Shelf
import com.vayana.core.database.repository.ShelfRepository
import com.vayana.core.database.model.Book
import com.vayana.core.database.repository.AnnotationRepository
import com.vayana.core.database.repository.BookRepository
import com.vayana.core.database.search.hasWordStartingWithAny
import com.vayana.core.database.search.searchTokens
import com.vayana.core.filesystem.ResolvedBooks
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import com.vayana.core.datastore.settings.SettingsRepository
import kotlinx.coroutines.launch
import com.vayana.core.common.DispatcherProvider

data class GlobalSearchUiState(
    val query: String = "",
    val books: List<BookSearchResult> = emptyList(),
    val annotations: List<AnnotationSearchResult> = emptyList(),
    val passages: List<BookContentResult> = emptyList(),
    /** The results shown are for an older query; the debounced search for [query] hasn't landed yet. */
    val isSearching: Boolean = false,
    val filter: SearchFilter = SearchFilter.ALL,
    /** Words of the query the results were found for, to highlight in them. */
    val tokens: List<String> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val scope: SearchScope = SearchScope(),
    val availableBooks: List<Book> = emptyList(),
    val availableShelves: List<Shelf> = emptyList(),
) {
    val hasQuery: Boolean get() = query.isNotBlank()
    val totalMatches: Int get() = books.size + annotations.size + passages.size
    val shownBooks: List<BookSearchResult> get() = if (filter != SearchFilter.ALL && filter != SearchFilter.BOOKS) emptyList() else books
    val shownAnnotations: List<AnnotationSearchResult> get() = if (filter != SearchFilter.ALL && filter != SearchFilter.NOTES) emptyList() else annotations
    val shownPassages: List<BookContentResult> get() = if (filter == SearchFilter.ALL || filter == SearchFilter.CONTENTS) passages else emptyList()
    val hasMatches: Boolean get() = shownBooks.isNotEmpty() || shownAnnotations.isNotEmpty() || shownPassages.isNotEmpty()
}

data class SearchScope(val bookId: Long? = null, val shelfId: Long? = null)

enum class SearchFilter { ALL, BOOKS, NOTES, CONTENTS }

data class BookContentResult(val id: Long, val book: Book, val chapter: String, val excerpt: String, val locator: String)

data class BookSearchResult(
    val book: Book,
    val matchedFields: List<SearchMatchedField>,
)

data class AnnotationSearchResult(
    val annotation: Annotation,
    val book: Book,
    val matchedFields: List<SearchMatchedField>,
)

enum class SearchMatchedField {
    TITLE,
    AUTHOR,
    SERIES,
    TAGS,
    DESCRIPTION,
    HIGHLIGHT,
    NOTE,
    CHAPTER,
}

internal data class SearchResults(
    val scope: SearchScope = SearchScope(),
    val query: String = "",
    val tokens: List<String> = emptyList(),
    val books: List<BookSearchResult> = emptyList(),
    val annotations: List<AnnotationSearchResult> = emptyList(),
    val passages: List<BookContentResult> = emptyList(),
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    bookRepository: BookRepository,
    annotationRepository: AnnotationRepository,
    resolvedBooks: ResolvedBooks,
    shelfRepository: ShelfRepository,
    private val settingsRepository: SettingsRepository,
    dispatchers: DispatcherProvider,
    private val passages: com.vayana.core.database.dao.EpubPassageDao,
    val contentIndex: EpubContentIndex,
) : ViewModel() {
    init { contentIndex.start() }
    private val query = MutableStateFlow("")
    private val scope = MutableStateFlow(SearchScope())
    private val scopeOptions = combine(resolvedBooks.all, shelfRepository.observeAll()) { books, shelves ->
        books.sortedBy { it.title.lowercase() } to shelves
    }
    private val filter = MutableStateFlow(SearchFilter.ALL)

    // Matching runs in SQLite FTS; only the capped result rows are joined with books and checked here.
    private val results = combine(query, scope) { text, scope -> text.trim() to scope }
        .debounce { (text, _) -> if (text.isEmpty()) 0L else SearchDebounceMillis }
        .distinctUntilChanged()
        .flatMapLatest { (text, scope) ->
            if (searchTokens(text).isEmpty()) {
                flowOf(SearchResults(query = text, scope = scope))
            } else {
                combine(
                    bookRepository.observeSearchIds(text, MaxBookResults, scope.bookId, scope.shelfId),
                    annotationRepository.observeSearch(text, MaxAnnotationResults, scope.bookId, scope.shelfId),
                    resolvedBooks.all,
                    passages.search(requireNotNull(com.vayana.core.database.search.ftsSearchMatch(text)), 60, scope.bookId, scope.shelfId),
                ) { bookIds, annotations, books, content ->
                    val byId = books.associateBy { it.id }
                    val chapters = chapterTexts(content.map { it.chapterId })
                    searchResults(text, bookIds, annotations, books).copy(scope = scope, passages = content.mapNotNull { passage ->
                        val book = byId[passage.bookId] ?: return@mapNotNull null
                        val chapter = chapters[passage.chapterId] ?: return@mapNotNull null
                        val excerpt = passageExcerpt(chapter.substring(passage.startOffset, passage.endOffset), searchTokens(text))
                        BookContentResult(passage.id, book, passage.chapterTitle, excerpt,
                            "text:search:${android.net.Uri.encode(passage.chapterHref)}:${android.net.Uri.encode(excerpt)}")
                    })
                }
            }
        }
        .flowOn(dispatchers.default)

    // Passages are offsets into their chapter's stored, compressed text. Typing a word longer mostly matches the same
    // chapters again, so recently read chapters are kept (bounded by characters) instead of decompressed per keystroke.
    private val chapterCache = object : android.util.LruCache<Long, String>(ChapterCacheChars) {
        override fun sizeOf(key: Long, value: String) = value.length
    }

    private suspend fun chapterTexts(ids: List<Long>): Map<Long, String> {
        val cached = ids.distinct().mapNotNull { id -> chapterCache.get(id)?.let { id to it } }.toMap()
        val loaded = passages.chapterTexts(ids.filterNot { it in cached })
        loaded.forEach { (id, text) -> chapterCache.put(id, text) }
        return cached + loaded
    }

    private val baseUiState = combine(
        query,
        results,
        filter,
        settingsRepository.recentSearches,
        scope,
    ) { query, results, filter, recentSearches, scope ->
        GlobalSearchUiState(
            query = query,
            books = if (scope == results.scope) results.books else emptyList(),
            annotations = if (scope == results.scope) results.annotations else emptyList(),
            passages = if (scope == results.scope) results.passages else emptyList(),
            isSearching = query.trim() != results.query || scope != results.scope,
            scope = scope,
            filter = filter,
            tokens = results.tokens,
            recentSearches = recentSearches,
        )
    }
    val uiState: StateFlow<GlobalSearchUiState> = combine(baseUiState, scopeOptions) { state, options ->
        state.copy(availableBooks = options.first, availableShelves = options.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalSearchUiState())

    /**
     * The search box's text, as Compose state so the field updates in the same frame as the keystroke; waiting for
     * [uiState] to echo it back let stale text overwrite new typing and made the cursor jump.
     */
    var searchText by mutableStateOf("")
        private set

    fun updateQuery(value: String) {
        searchText = value
        query.update { value }
    }

    fun updateScope(value: SearchScope) { scope.value = value }

    fun updateFilter(value: SearchFilter) {
        filter.update { value }
    }

    /** Remembers the current query; called when the user submits it or opens one of its results. */
    fun recordSearch() {
        // Stored one per line, so pasted line breaks collapse like any other whitespace.
        val text = query.value.trim().replace(WhitespaceRun, " ")
        if (searchTokens(text).isEmpty()) return
        viewModelScope.launch { settingsRepository.updateRecentSearches { it.withRecentSearch(text) } }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { settingsRepository.updateRecentSearches { emptyList() } }
    }
}

internal fun searchResults(
    text: String,
    bookIds: List<Long>,
    annotations: List<Annotation>,
    books: List<Book>,
): SearchResults {
    val tokens = searchTokens(text)
    val booksById = books.associateBy { it.id }
    return SearchResults(
        query = text,
        tokens = tokens,
        books = bookIds.mapNotNull { id ->
            booksById[id]?.let { book -> BookSearchResult(book, book.matchedFields(tokens)) }
        },
        annotations = annotations.mapNotNull { annotation ->
            booksById[annotation.bookId]?.let { book -> AnnotationSearchResult(annotation, book, annotation.matchedFields(tokens)) }
        },
    )
}

private fun Book.matchedFields(tokens: List<String>): List<SearchMatchedField> = buildList {
    if (title.hasWordStartingWithAny(tokens)) add(SearchMatchedField.TITLE)
    if (author.hasWordStartingWithAny(tokens)) add(SearchMatchedField.AUTHOR)
    if (series.hasWordStartingWithAny(tokens) || seriesNumber.hasWordStartingWithAny(tokens)) add(SearchMatchedField.SERIES)
    if (tagsCsv.hasWordStartingWithAny(tokens)) add(SearchMatchedField.TAGS)
    if (description.hasWordStartingWithAny(tokens)) add(SearchMatchedField.DESCRIPTION)
}

private fun Annotation.matchedFields(tokens: List<String>): List<SearchMatchedField> = buildList {
    if (selectedText.hasWordStartingWithAny(tokens)) add(SearchMatchedField.HIGHLIGHT)
    if (readerNote.hasWordStartingWithAny(tokens)) add(SearchMatchedField.NOTE)
    if (chapterTitle.hasWordStartingWithAny(tokens)) add(SearchMatchedField.CHAPTER)
}

/** [query] moved to the front, without case-insensitive repeats, capped. */
internal fun List<String>.withRecentSearch(query: String): List<String> =
    (listOf(query) + filterNot { it.equals(query, ignoreCase = true) }).take(MaxRecentSearches)

private val WhitespaceRun = Regex("""\s+""")

private const val SearchDebounceMillis = 150L
private const val MaxRecentSearches = 8
private const val MaxBookResults = 30
private const val MaxAnnotationResults = 80
private const val ChapterCacheChars = 2_000_000

internal fun passageExcerpt(text: String, tokens: List<String>): String {
    val firstMatch = com.vayana.core.database.search.wordPrefixMatchRanges(text, tokens).firstOrNull()?.first ?: 0
    val roughStart = (firstMatch - 60).coerceAtLeast(0)
    val start = if (roughStart == 0) 0 else text.indexOf(' ', roughStart).takeIf { it in roughStart..firstMatch }?.plus(1) ?: roughStart
    return text.substring(start, minOf(text.length, start + 300)).trim()
}
