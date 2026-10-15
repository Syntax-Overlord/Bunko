package com.vayana.reader.web

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PageCountStoreTest {
    private val dir = Files.createTempDirectory("page-counts").toFile()
    private val book = "a".repeat(64)

    @Test
    fun `saved layouts come back most recent first and a resaved layout replaces its old counts`() {
        val store = PageCountStore(dir, "v1")
        store.save(book, "small", JSONArray("[[0,10]]"))
        store.save(book, "large", JSONArray("[[0,20]]"))
        store.save(book, "small", JSONArray("[[0,11]]"))
        val layouts = store.load(book).getJSONArray("layouts")
        assertEquals(2, layouts.length())
        assertEquals("small", layouts.getJSONObject(0).getString("layout"))
        assertEquals("[[0,11]]", layouts.getJSONObject(0).getJSONArray("counts").toString())
        assertEquals("large", layouts.getJSONObject(1).getString("layout"))
    }

    @Test
    fun `old layouts are dropped past the limit and another build's counts are ignored`() {
        val store = PageCountStore(dir, "v1")
        repeat(10) { store.save(book, "layout$it", JSONArray("[[0,$it]]")) }
        val layouts = store.load(book).getJSONArray("layouts")
        assertEquals(8, layouts.length())
        assertEquals("layout9", layouts.getJSONObject(0).getString("layout"))
        assertEquals(0, PageCountStore(dir, "v2").load(book).getJSONArray("layouts").length())
    }

    @Test
    fun `a book key that is not a hash is never used as a file name`() {
        PageCountStore(dir, "v1").save("../escape", "layout", JSONArray("[[0,1]]"))
        assertFalse(File(dir.parentFile, "escape.json").exists())
        assertEquals(0, dir.listFiles().orEmpty().size)
    }

    @Test
    fun `the book key changes when the file is replaced`() {
        val store = PageCountStore(dir, "v1")
        val file = File(dir, "book.epub").apply { writeText("one") }
        val before = store.bookKey(file)
        file.writeText("longer")
        assertNotEquals(before, store.bookKey(file))
        assertTrue(before.matches(Regex("[0-9a-f]{64}")))
    }
}
