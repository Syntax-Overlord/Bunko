package com.vayana.feature.search

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchCategoryFlowTest {
    @Test fun hiddenCategoriesNeverCreateOrCollectTheirQueries() = runBlocking {
        for (selected in SearchFilter.entries) {
            for (category in listOf(SearchFilter.BOOKS, SearchFilter.NOTES, SearchFilter.CONTENTS)) {
                var created = 0
                var collected = 0
                val rows = selected.searchFlow(category) {
                    created++
                    flow { collected++; emit(listOf(category.name)) }
                }.first()
                val enabled = selected == SearchFilter.ALL || selected == category
                assertEquals(if (enabled) listOf(category.name) else emptyList(), rows)
                assertEquals(if (enabled) 1 else 0, created)
                assertEquals(created, collected)
            }
        }
    }

    @Test fun switchingFiltersCancelsOldQueriesAndQueriesOtherCategoriesAgain() = runBlocking {
        val filter = MutableStateFlow(SearchFilter.BOOKS)
        val started = mutableListOf<SearchFilter>()
        val cancelled = mutableListOf<SearchFilter>()
        val updates = Channel<Triple<List<String>, List<String>, List<String>>>()
        fun rows(category: SearchFilter) = flow {
            started.add(category)
            try { emit(listOf(category.name)); awaitCancellation() }
            finally { cancelled.add(category) }
        }
        val job = launch {
            filter.flatMapLatest { selected ->
                combine(selected.searchFlow(SearchFilter.BOOKS) { rows(SearchFilter.BOOKS) },
                    selected.searchFlow(SearchFilter.NOTES) { rows(SearchFilter.NOTES) },
                    selected.searchFlow(SearchFilter.CONTENTS) { rows(SearchFilter.CONTENTS) }) { books, notes, content -> Triple(books, notes, content) }
            }.collect { updates.send(it) }
        }
        suspend fun next() = withTimeout(10_000) { updates.receive() }
        try {
            assertEquals(Triple(listOf("BOOKS"), emptyList(), emptyList()), next())
            assertEquals(listOf(SearchFilter.BOOKS), started)
            filter.value = SearchFilter.NOTES
            assertEquals(Triple(emptyList(), listOf("NOTES"), emptyList()), next())
            assertTrue(SearchFilter.BOOKS in cancelled)
            assertFalse(SearchFilter.CONTENTS in started)
            filter.value = SearchFilter.ALL
            assertEquals(Triple(listOf("BOOKS"), listOf("NOTES"), listOf("CONTENTS")), next())
            assertTrue(SearchFilter.NOTES in cancelled)
            filter.value = SearchFilter.CONTENTS
            assertEquals(Triple(emptyList(), emptyList(), listOf("CONTENTS")), next())
            assertEquals(2, started.count { it == SearchFilter.CONTENTS })
        } finally { job.cancelAndJoin(); updates.cancel() }
    }

    @Test fun activeCategoryPropagatesLiveDatabaseChangesIncludingNoMatches() = runBlocking {
        val rows = MutableStateFlow(listOf("first"))
        val updates = Channel<List<String>>()
        val job = launch { SearchFilter.NOTES.searchFlow(SearchFilter.NOTES) { rows }.collect { updates.send(it) } }
        try {
            assertEquals(listOf("first"), withTimeout(10_000) { updates.receive() })
            rows.value = emptyList()
            assertEquals(emptyList(), withTimeout(10_000) { updates.receive() })
        } finally { job.cancelAndJoin(); updates.cancel() }
    }
}
