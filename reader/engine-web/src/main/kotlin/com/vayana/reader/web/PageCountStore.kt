package com.vayana.reader.web

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-book page counts measured by bridge.js, one small JSON file per book holding its most recent layouts, so
 * reopening a book publishes exact page numbers without laying every chapter out again. It is a cache: [stamp]
 * names the app and WebView builds, and a file written under another stamp is ignored, since either can paginate
 * differently.
 */
internal class PageCountStore(private val dir: File, private val stamp: String) {
    private val lock = Any()

    /** Stable for a book file, and changes when the file is replaced. */
    fun bookKey(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest("${file.absolutePath}|${file.length()}|${file.lastModified()}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** The `savedPageCounts` argument of bridge.js's open(): `{ book, layouts: [{ layout, counts }] }`. */
    fun load(book: String): JSONObject = JSONObject().put("book", book).put("layouts", synchronized(lock) { read(book) })

    fun save(book: String, layout: String, counts: JSONArray) {
        if (!BookKeyPattern.matches(book)) return
        synchronized(lock) {
            val layouts = JSONArray().put(JSONObject().put("layout", layout).put("counts", counts))
            val previous = read(book)
            for (index in 0 until previous.length()) {
                if (layouts.length() >= MaxLayoutsPerBook) break
                val entry = previous.optJSONObject(index) ?: continue
                if (entry.optString("layout") != layout) layouts.put(entry)
            }
            dir.mkdirs()
            file(book).writeText(JSONObject().put("stamp", stamp).put("layouts", layouts).toString())
        }
    }

    private fun read(book: String): JSONArray = runCatching {
        JSONObject(file(book).readText()).takeIf { it.optString("stamp") == stamp }?.optJSONArray("layouts")
    }.getOrNull() ?: JSONArray()

    private fun file(book: String) = File(dir, "$book.json")
}

private val BookKeyPattern = Regex("[0-9a-f]{64}")

// Most recent first; enough for a few font sizes with and without the read-aloud panel.
private const val MaxLayoutsPerBook = 8
