package com.vayana.core.sync.snapshot

import com.vayana.core.backup.PortableReadingProgressPatch
import com.vayana.core.backup.PortableReadingSession
import com.vayana.core.backup.PortableSnapshot
import com.vayana.core.backup.PortableSnapshotLatestPath
import com.vayana.core.backup.PortableTombstone
import com.vayana.core.backup.PortableWordLookupCounter
import com.vayana.core.backup.patchPortableReadingProgressOnly
import com.vayana.core.backup.patchSlicedPortableReadingProgress
import com.vayana.core.backup.parsePortableAppBuild
import com.vayana.core.backup.portableSnapshotManifestDataEquals
import com.vayana.core.backup.portableSnapshotSliceDataHash
import com.vayana.core.backup.portableSnapshotSlicePaths
import com.vayana.core.backup.repointPortableSnapshotSlices
import com.vayana.core.backup.toSlicedJsonDocuments
import com.vayana.core.database.model.isCommunityQuoteLocator
import com.vayana.core.diagnostics.DiagnosticCategory
import com.vayana.core.diagnostics.DiagnosticsLogStore
import com.vayana.core.sync.asset.GitHubAssetStoreException
import com.vayana.core.sync.asset.GitHubContentsAssetStore
import com.vayana.core.sync.asset.SyncDocumentChange
import java.util.concurrent.ConcurrentHashMap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The latest snapshot pointer plus lazy access to its slices. A slice is downloaded the first time someone
 * asks for it, so a caller that only needs positions (the manifest's "books") never pays for annotations or
 * sessions. A missing (404) slice falls back to the manifest text, as before.
 */
class RemotePortableSnapshotDocument internal constructor(
    val jsonText: String,
    val sha: String,
    val sliced: Boolean,
    sliceJsonByKey: Map<String, String> = emptyMap(),
    private val slicePaths: Map<String, String> = emptyMap(),
    private val loadSlice: (suspend (String) -> String)? = null,
) {
    constructor(jsonText: String, sha: String, sliced: Boolean) : this(jsonText, sha, sliced, emptyMap())

    private val loadedSlices = ConcurrentHashMap(sliceJsonByKey)
    private val missingSlices: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val sliceLocks = ConcurrentHashMap<String, Mutex>()
    private val alreadySyncedHashes = ConcurrentHashMap<String, String>()

    /**
     * Marks the slices whose published file this device already holds (see [SyncedSnapshotSlice]): [jsonFor]
     * then reads them as empty, so they are neither downloaded nor merged again.
     */
    fun skipAlreadySynced(slices: Collection<SyncedSnapshotSlice>) {
        slices.forEach { synced -> if (slicePaths[synced.key] == synced.path) alreadySyncedHashes[synced.key] = synced.dataHash }
    }

    /** For sync diagnostics: slices skipped as already synced here, and slices read (with their sizes in chars). */
    fun sliceStats(): String =
        "slicesSkipped=${alreadySyncedHashes.keys.sorted()}, slicesRead=${loadedSlices.entries.sortedBy { it.key }.map { "${it.key}:${it.value.length}" }}"

    /** The slice's records to merge; empty (the manifest) for a missing slice or one already synced here. */
    suspend fun jsonFor(slice: RemotePortableSnapshotSlice): String =
        if (alreadySyncedHashes.containsKey(slice.key)) jsonText else sliceJsonOrNull(slice.key) ?: jsonText

    /**
     * Downloads the given slices concurrently, for callers about to read most of them, and returns the first
     * failure (null when every slice loaded or is gone). Callers stop on a failure rather than read on: a later
     * [jsonFor] would retry the download outside their error handling, and merging only some slices before
     * publishing would push a snapshot missing what failed to arrive.
     */
    suspend fun prefetch(slices: Collection<RemotePortableSnapshotSlice> = RemotePortableSnapshotSlice.entries): Exception? =
        coroutineScope {
            slices.filter { it.key in slicePaths && !alreadySyncedHashes.containsKey(it.key) && !loadedSlices.containsKey(it.key) }
                .map { slice ->
                    async {
                        try {
                            sliceJsonOrNull(slice.key)
                            null
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Exception) {
                            failure
                        }
                    }
                }
                .awaitAll()
                .firstOrNull { it != null }
        }

    /** The slice's own document, or null when this snapshot has no such slice or it is gone (404). */
    internal suspend fun sliceJsonOrNull(key: String): String? {
        loadedSlices[key]?.let { return it }
        if (key in missingSlices) return null
        val loader = loadSlice ?: return null
        val path = slicePaths[key] ?: return null
        return sliceLocks.computeIfAbsent(key) { Mutex() }.withLock {
            loadedSlices[key]?.let { return@withLock it }
            if (key in missingSlices) return@withLock null
            val json = loadSnapshotSliceOrNull(path, loader)
            if (json == null) missingSlices += key else loadedSlices[key] = json
            json
        }
    }

    /** The records hash of a slice already synced here or in memory, without triggering a download. */
    internal fun knownSliceDataHashOrNull(key: String): String? =
        alreadySyncedHashes[key] ?: loadedSlices[key]?.let { portableSnapshotSliceDataHash(key, it) }

    internal fun slicePathOrNull(key: String): String? = slicePaths[key]
}

/**
 * A published slice file whose records this device's database already holds: it published them, and nothing
 * removes a record without a tombstone. Slice files are never rewritten, so while the latest snapshot still
 * points at [path], merging it again would change nothing. [dataHash] is [portableSnapshotSliceDataHash].
 */
data class SyncedSnapshotSlice(val key: String, val path: String, val dataHash: String)

enum class RemotePortableSnapshotSlice(val key: String) {
    Books("books"),
    Annotations("annotations"),
    CommunityQuotes("communityQuotes"),
    Shelves("shelves"),
    ShelfMemberships("shelfMemberships"),
    ReadingSessions("readingSessions"),
    VocabularyCards("vocabularyCards"),
    WordLookupCounters("wordLookupCounters"),
    BookAliases("bookAliases"),
    Tombstones("tombstones"),
}

enum class PortableSnapshotPublishStage {
    SNAPSHOT_SLICE,
    LATEST_POINTER,
    PRUNING,
}

data class PortableSnapshotPublishProgress(
    val stage: PortableSnapshotPublishStage,
    val completedDocuments: Int,
    val totalDocuments: Int,
    val completedBytes: Int,
    val totalBytes: Int,
    val currentPath: String? = null,
) {
    val fraction: Float = when {
        totalBytes > 0 -> (completedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        totalDocuments > 0 -> (completedDocuments.toFloat() / totalDocuments.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
}

suspend fun GitHubContentsAssetStore.getLatestPortableSnapshotDocument(): RemotePortableSnapshotDocument {
    return getLatestPortableSnapshotDocumentUnlessSha(skipSha = null) ?: error("Latest snapshot unexpectedly skipped")
}

suspend fun GitHubContentsAssetStore.getLatestPortableSnapshotDocumentUnlessSha(
    skipSha: String?,
): RemotePortableSnapshotDocument? {
    val latest = getSyncDocumentWithShaUnless(PortableSnapshotLatestPath, skipSha) ?: return null
    val latestJson = latest.bytes.toString(Charsets.UTF_8)
    return remotePortableSnapshotDocumentFrom(
        latestJson = latestJson,
        sha = latest.sha,
        slicePaths = portableSnapshotSlicePaths(latestJson),
        loadSlice = { path -> getSnapshotSliceCached(path) },
    )
}

internal fun remotePortableSnapshotDocumentFrom(
    latestJson: String,
    sha: String,
    slicePaths: Map<String, String>,
    loadSlice: suspend (String) -> String,
): RemotePortableSnapshotDocument =
    if (slicePaths.isEmpty()) {
        RemotePortableSnapshotDocument(jsonText = latestJson, sha = sha, sliced = false)
    } else {
        RemotePortableSnapshotDocument(
            jsonText = latestJson,
            sha = sha,
            sliced = true,
            slicePaths = slicePaths,
            loadSlice = loadSlice,
        )
    }

/**
 * Slice files are written once to a timestamped path and never rewritten (see [putNewSyncDocument]), so a
 * slice read by path stays valid for the life of the process and repeat syncs skip the download entirely.
 */
private suspend fun GitHubContentsAssetStore.getSnapshotSliceCached(path: String): String {
    val key = "$cacheScope|$path"
    return SnapshotSliceCache.getOrLoad(key) {
        val bytes = getSyncDocument(path)
        if (path.endsWith(".zip")) unzipSnapshotJson(bytes) else bytes.toString(Charsets.UTF_8)
    }
}

internal fun zipSnapshotJson(json: String): ByteArray {
    val output = ByteArrayOutputStream(json.length.coerceAtMost(MaxInitialZipBufferBytes))
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry(AnnotationsZipEntryName))
        zip.write(json.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    return output.toByteArray()
}

internal fun unzipSnapshotJson(bytes: ByteArray): String {
    val output = ByteArrayOutputStream()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        val entry = zip.nextEntry ?: error("Compressed snapshot slice is empty")
        require(!entry.isDirectory && entry.name == AnnotationsZipEntryName) { "Invalid compressed snapshot slice" }
        val buffer = ByteArray(ZipBufferBytes)
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            require(output.size() <= MaxUncompressedSliceBytes) { "Compressed snapshot slice is too large" }
        }
        zip.closeEntry()
        require(zip.nextEntry == null) { "Compressed snapshot slice has unexpected entries" }
    }
    return output.toString(Charsets.UTF_8.name())
}

/** Process-wide, size-bounded LRU of slice texts keyed by repository scope and path. */
internal object SnapshotSliceCache {
    private const val MaxTotalChars = 6 * 1024 * 1024
    private const val MaxEntryChars = 3 * 1024 * 1024
    private val entries = LinkedHashMap<String, String>(16, 0.75f, true)
    private class ActiveLoad(val mutex: Mutex = Mutex(), var users: Int = 0, var result: String? = null)
    private val inFlightLocks = mutableMapOf<String, ActiveLoad>()
    private var totalChars = 0

    @Synchronized
    fun get(key: String): String? = entries[key]

    /** Concurrent readers of one immutable slice share the first successful download/decompression. */
    suspend fun getOrLoad(key: String, load: suspend () -> String): String {
        get(key)?.let { return it }
        val active = synchronized(this) {
            inFlightLocks.getOrPut(key) { ActiveLoad() }.also { it.users++ }
        }
        try {
            return active.mutex.withLock {
                get(key) ?: active.result ?: load().also {
                    active.result = it // also share oversized slices that cannot enter the bounded LRU
                    put(key, it)
                }
            }
        } finally {
            synchronized(this) {
                active.users--
                if (active.users == 0) inFlightLocks.remove(key, active)
            }
        }
    }

    @Synchronized
    fun put(key: String, json: String) {
        if (json.length > MaxEntryChars) return
        entries.put(key, json)?.let { totalChars -= it.length }
        totalChars += json.length
        val iterator = entries.entries.iterator()
        while (totalChars > MaxTotalChars && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == key) continue
            totalChars -= eldest.value.length
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        totalChars = 0
    }
}

private suspend fun loadSnapshotSliceOrNull(path: String, loadSlice: suspend (String) -> String): String? =
    try {
        loadSlice(path)
    } catch (throwable: GitHubAssetStoreException) {
        if (throwable.statusCode == 404) null else throw throwable
    }

data class PortableReadingProgressPushResult(
    val patched: Int = 0,
    val sessionsAdded: Int = 0,
    val wordLookupCountersMerged: Int = 0,
    val tombstonesMerged: Int = 0,
) {
    val pushed: Int
        get() = patched + sessionsAdded + wordLookupCountersMerged + tombstonesMerged
}

/**
 * Pushes local reading positions, sessions, word-lookup counters and tombstones on top of [remote], for both
 * single-document and sliced snapshots. The latest pointer is written with [RemotePortableSnapshotDocument.sha]
 * as the expected SHA, so a concurrent writer surfaces as a 409 [GitHubAssetStoreException] for the caller to
 * re-fetch and retry; slice files written before a lost race are harmless orphans that pruning removes.
 */
suspend fun GitHubContentsAssetStore.pushPortableReadingProgress(
    remote: RemotePortableSnapshotDocument,
    patches: List<PortableReadingProgressPatch>,
    exportedAt: Long,
    readingSessions: List<PortableReadingSession> = emptyList(),
    wordLookupCounters: List<PortableWordLookupCounter> = emptyList(),
    tombstones: List<PortableTombstone> = emptyList(),
): PortableReadingProgressPushResult {
    if (!remote.sliced) {
        val result = patchPortableReadingProgressOnly(
            jsonText = remote.jsonText,
            patches = patches,
            exportedAt = exportedAt,
            readingSessions = readingSessions,
            wordLookupCounters = wordLookupCounters,
            tombstones = tombstones,
        )
        val pushResult = PortableReadingProgressPushResult(
            patched = result.patched,
            sessionsAdded = result.sessionsAdded,
            wordLookupCountersMerged = result.wordLookupCountersMerged,
            tombstonesMerged = result.tombstonesMerged,
        )
        if (pushResult.pushed > 0) {
            putSyncDocumentIfUnchanged(PortableSnapshotLatestPath, result.jsonText.toByteArray(Charsets.UTF_8), remote.sha)
        }
        return pushResult
    }
    // Only slices with local data to merge are worth downloading; the rest can't change.
    val neededSlices = buildList {
        if (readingSessions.isNotEmpty()) add(RemotePortableSnapshotSlice.ReadingSessions)
        if (wordLookupCounters.isNotEmpty()) add(RemotePortableSnapshotSlice.WordLookupCounters)
        if (tombstones.isNotEmpty()) add(RemotePortableSnapshotSlice.Tombstones)
    }
    remote.prefetch(neededSlices)?.let { throw it }
    val sliceJsonByKey = neededSlices
        .mapNotNull { slice -> remote.sliceJsonOrNull(slice.key)?.let { slice.key to it } }
        .toMap()
    val result = patchSlicedPortableReadingProgress(
        manifestJson = remote.jsonText,
        sliceJsonByKey = sliceJsonByKey,
        patches = patches,
        exportedAt = exportedAt,
        readingSessions = readingSessions,
        wordLookupCounters = wordLookupCounters,
        tombstones = tombstones,
    )
    if (result.changed > 0) {
        result.sliceWrites.forEach { (path, jsonText) ->
            putNewSyncDocument(path, jsonText.toByteArray(Charsets.UTF_8))
            SnapshotSliceCache.put("$cacheScope|$path", jsonText)
        }
        putSyncDocumentIfUnchanged(PortableSnapshotLatestPath, result.manifestJson.toByteArray(Charsets.UTF_8), remote.sha)
    }
    return PortableReadingProgressPushResult(
        patched = result.patched,
        sessionsAdded = result.sessionsAdded,
        wordLookupCountersMerged = result.wordLookupCountersMerged,
        tombstonesMerged = result.tombstonesMerged,
    )
}

suspend fun GitHubContentsAssetStore.putPortableSnapshotDocuments(
    snapshot: PortableSnapshot,
    deviceSnapshotPath: String,
    expectedLatestSha: String?,
    diagnosticsLogStore: DiagnosticsLogStore? = null,
    onProgress: ((PortableSnapshotPublishProgress) -> Unit)? = null,
    /**
     * The snapshot this publish replaces. A slice whose records match one of its slices that is already in
     * memory keeps pointing at that published file instead of being uploaded (and committed) again.
     */
    previous: RemotePortableSnapshotDocument? = null,
): List<SyncedSnapshotSlice> {
    val documents = snapshot.toSlicedJsonDocuments()
    val latestDocument = documents.last { it.path == PortableSnapshotLatestPath }
    val allSliceDocuments = documents.filter { it.path != PortableSnapshotLatestPath }
    val dataHashByKey = allSliceDocuments.associate { document -> requireNotNull(document.sliceKey) to requireNotNull(document.dataHash) }
    val reusedPathsByKey = allSliceDocuments.mapNotNull { document ->
        val key = document.sliceKey ?: return@mapNotNull null
        val previousHash = previous?.knownSliceDataHashOrNull(key) ?: return@mapNotNull null
        val previousPath = previous.slicePathOrNull(key) ?: return@mapNotNull null
        if (previousPath.endsWith(".zip") != isZippedSlice(key)) return@mapNotNull null
        if (dataHashByKey[key] == previousHash) key to previousPath else null
    }.toMap()
    val sliceDocuments = allSliceDocuments.filter { it.sliceKey !in reusedPathsByKey }
    val sliceUploads = sliceDocuments.map { document ->
        val zipped = isZippedSlice(requireNotNull(document.sliceKey))
        SnapshotSliceUpload(
            sliceKey = requireNotNull(document.sliceKey),
            path = if (zipped) document.path.removeSuffix(".json") + ".zip" else document.path,
            zipped = zipped,
            jsonText = document.jsonText,
        )
    }
    val publishedPathsByKey = reusedPathsByKey + sliceUploads.associate { it.sliceKey to it.path }
    val published = publishedPathsByKey.map { (key, path) -> SyncedSnapshotSlice(key, path, dataHashByKey.getValue(key)) }
    val latestJson = repointPortableSnapshotSlices(latestDocument.jsonText, publishedPathsByKey)
    // Unchanged data still goes out once per newer build, so the snapshot names the newest app that synced it.
    val remoteBuild = previous?.let { parsePortableAppBuild(it.jsonText) }
    val outdatedBuildStamp = snapshot.appBuild?.let { local ->
        remoteBuild == null || compareValuesBy(local, remoteBuild, { it.versionCode }, { it.builtAt }) > 0
    } == true
    if (sliceUploads.isEmpty() && previous != null && !outdatedBuildStamp &&
        portableSnapshotManifestDataEquals(latestJson, previous.jsonText)
    ) {
        // The latest snapshot already says exactly this: writing it again would only bump exportedAt (and cost
        // five GitHub round trips).
        diagnosticsLogStore?.record(
            category = DiagnosticCategory.SYNC,
            source = "RemotePortableSnapshotStore.putPortableSnapshotDocuments",
            message = "Snapshot unchanged; publish skipped",
            detail = "exportedAt=${snapshot.exportedAt}, books=${snapshot.books.size}",
        )
        return published
    }
    val latestBytes = latestJson.toByteArray(Charsets.UTF_8)
    fun report(stage: PortableSnapshotPublishStage, completed: Int, path: String? = null) {
        onProgress?.invoke(
            PortableSnapshotPublishProgress(
                stage = stage,
                completedDocuments = completed,
                totalDocuments = sliceUploads.size + 2,
                completedBytes = 0,
                totalBytes = 0,
                currentPath = path,
            ),
        )
    }
    val sliceBytesByPath = LinkedHashMap<String, ByteArray>()
    sliceUploads.forEachIndexed { index, upload ->
        report(PortableSnapshotPublishStage.SNAPSHOT_SLICE, completed = index + 1, path = upload.path)
        sliceBytesByPath[upload.path] = if (upload.zipped) zipSnapshotJson(upload.jsonText) else upload.jsonText.toByteArray(Charsets.UTF_8)
    }
    val publishStartedAt = System.nanoTime()
    // Pruning makes room for a new slice directory; a publish that reused every slice added none. Its deletes ride
    // along in the publish commit, keeping whatever the new pointer references.
    var pruneFailed = false
    val staleFiles = if (sliceUploads.isEmpty()) {
        emptyList()
    } else {
        report(PortableSnapshotPublishStage.PRUNING, completed = sliceUploads.size + 1)
        try {
            staleSnapshotSliceFiles(
                currentExportedAt = snapshot.exportedAt,
                referencedExportTimes = publishedPathsByKey.values.mapNotNullTo(mutableSetOf(), ::sliceExportTimeOrNull),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            pruneFailed = true
            emptyList()
        }
    }
    report(PortableSnapshotPublishStage.LATEST_POINTER, completed = sliceUploads.size + 2, path = PortableSnapshotLatestPath)
    val changes = buildList {
        add(SyncDocumentChange(deviceSnapshotPath, latestBytes))
        sliceBytesByPath.forEach { (path, bytes) -> add(SyncDocumentChange(path, bytes)) }
        add(SyncDocumentChange(PortableSnapshotLatestPath, latestBytes))
        staleFiles.forEach { path -> add(SyncDocumentChange(path, null)) }
    }
    val singleCommit = commitSyncDocuments(
        changes = changes,
        message = "Sync Vayana snapshot ${snapshot.exportedAt}",
        guardPath = PortableSnapshotLatestPath,
        guardSha = expectedLatestSha,
    )
    if (!singleCommit) {
        // An empty repository (the very first publish) only takes Contents API writes, one commit per file.
        putSyncDocument(deviceSnapshotPath, latestBytes)
        sliceBytesByPath.forEach { (path, bytes) -> putNewSyncDocument(path, bytes) }
        putSyncDocumentIfUnchanged(PortableSnapshotLatestPath, latestBytes, expectedLatestSha)
    }
    sliceUploads.forEach { upload -> SnapshotSliceCache.put("$cacheScope|${upload.path}", upload.jsonText) }
    diagnosticsLogStore?.record(
        category = DiagnosticCategory.SYNC,
        source = "RemotePortableSnapshotStore.putPortableSnapshotDocuments",
        message = "Published sliced snapshot",
        detail = buildString {
            append("exportedAt=").append(snapshot.exportedAt)
            append(", books=").append(snapshot.books.size)
            append(", slices=").append(sliceDocuments.size)
            append(", reusedSlices=").append(reusedPathsByKey.size)
            append(", latestBytes=").append(latestBytes.size)
            append(", sliceBytes=").append(sliceBytesByPath.values.sumOf { it.size })
            append(", prunedFiles=").append(staleFiles.size)
            append(", pruneFailed=").append(pruneFailed)
            append(", singleCommit=").append(singleCommit)
            append(", publishMs=").append((System.nanoTime() - publishStartedAt) / 1_000_000)
        },
    )
    return published
}

/**
 * Slice files in directories older than the newest [RetainedSnapshotSliceSets] that nothing in
 * [referencedExportTimes] (the snapshot being published) points at: a device with a lagging clock, or a
 * progress-only push reusing older slice files, can reference directories that sort below others.
 */
internal suspend fun GitHubContentsAssetStore.staleSnapshotSliceFiles(
    currentExportedAt: Long,
    referencedExportTimes: Set<Long>,
): List<String> {
    val sliceDirectoryTimes = listSyncDocumentDirectory(SnapshotSlicesRoot)
        .filter { it.type == "dir" }
        .mapNotNull { entry -> entry.name.toLongOrNull()?.takeIf { it > 0L } }
    val retainedExportTimes = (sliceDirectoryTimes + currentExportedAt)
        .distinct()
        .sortedDescending()
        .take(RetainedSnapshotSliceSets)
        .toSet() + currentExportedAt + referencedExportTimes
    return sliceDirectoryTimes.filterNot { it in retainedExportTimes }.flatMap { exportedAt ->
        val allowedPaths = SnapshotSliceFileNames.mapTo(mutableSetOf()) { fileName -> "$SnapshotSlicesRoot/$exportedAt/$fileName" }
        listSyncDocumentDirectory("$SnapshotSlicesRoot/$exportedAt")
            .filter { it.type == "file" && it.path in allowedPaths }
            .map { it.path }
    }
}

private fun sliceExportTimeOrNull(path: String): Long? =
    path.removePrefix("$SnapshotSlicesRoot/").substringBefore('/').toLongOrNull()

private data class SnapshotSliceUpload(
    val sliceKey: String,
    val path: String,
    val zipped: Boolean,
    val jsonText: String,
)

/** Community quotes are thousands of near-identical records: zipped they shrink several times over. */
private fun isZippedSlice(key: String): Boolean = key == RemotePortableSnapshotSlice.CommunityQuotes.key

private const val SnapshotSlicesRoot = "vayana/snapshot-slices"
private const val RetainedSnapshotSliceSets = 3
private const val AnnotationsZipEntryName = "annotations.json"
private const val MaxUncompressedSliceBytes = 16 * 1024 * 1024
private const val ZipBufferBytes = 16 * 1024
private const val MaxInitialZipBufferBytes = 256 * 1024
private val SnapshotSliceFileNames = setOf(
    "annotations.json",
    "annotations.zip",
    "community-quotes.json",
    "community-quotes.zip",
    "shelves.json",
    "shelf-memberships.json",
    "reading-sessions.json",
    "vocabulary-cards.json",
    "word-lookup-counters.json",
    "book-aliases.json",
    "tombstones.json",
)
