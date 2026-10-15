# Performance experiments

## Reader layout and annotation reuse — 2026-10-15

Controlled operation-count checks against the reader bridge:

| Workload | Before | After |
| --- | --- | --- |
| 100 identical CSS/margin requests | 100 CSS writes, estimate resets, and count scheduling requests | 1 of each |
| 1,000 annotations restored across 20 chapter overlays | 20,000 chapter resolutions | 1,000 |
| 200 identical community passages in one matching pass | 200 chapter searches | 1 |

Unchanged CSS is remembered per renderer; a margin change still invalidates pagination without
rewriting the same CSS. Bionic formatting and actual typography changes retain their own resets.
Count scheduling already debounces rapid requests, so the first row measures avoided writes and
invalidations, not 100 completed full-book counts.

Annotation chapter indexes are weakly keyed by book view and annotation object. Only successful
resolutions are remembered; changed quote CFIs and replacement views resolve again. DOM ranges
are recreated for every overlay. Identical quote searches share a range only within one matching
pass, with separate keys for strict community matching and personal-import fallback. A missing
overlay remains retryable on the next pass.

Tests guard these work counts, replacement renderers/views, changed CFIs, transient misses, and
overlay retries. These are deterministic reductions in work, not measured phone latency or FPS.

## Reading gestures take priority — 2026-10-15

Full-book pagination waits before creating its hidden renderer and before laying out each chapter
until the reader has been idle for 250 ms. Pointer holds and multi-finger gestures keep it paused;
hardware page turns also restart the quiet interval. Font loading and section loading already in
flight finish normally, with cancellation checks preventing obsolete layouts from being published.
Quote-text matching is deferred and coalesced during gestures, and deferred callbacks discard
chapters that are no longer loaded. Personal highlights with existing CFIs still render directly.

Deterministic tests cover held pointers, pinch release, cancellation, detached chapters, and
coalesced quote matching. These checks prove scheduling behavior, not a measured frame-rate gain.
Pointer moves need no activity handler: the held-pointer state already pauses background work,
and releasing the final pointer starts the quiet interval. Mouse or stylus hover cannot delay it.
The session clock is collected only inside its visible header, so hidden indicators do not
invalidate the whole reader on clock updates. Hidden indicators also skip touch-state updates.

## Search excerpt first-match lookup — 2026-10-14

Excerpt generation now stops at the first matching word instead of calculating all highlight ranges and
using only their first offset. Full highlight generation remains available for displayed text. Seeded
comparisons cover Unicode, case, overlapping prefixes, empty queries, missing matches and excerpt bounds.

A controlled JVM comparison used a 1,800-character passage with an early match, two query tokens, 100
warm-up iterations and five batches of 1,200 lookups (20 result sets of 60 hits). The median batch fell
from 73.13 ms to 0.78 ms (93.7×). This measures only first-match lookup; later or missing matches require
more scanning. It does not measure full search latency or phone performance.

## Incremental notebook generation and search categories — 2026-10-14

Successful notebook exports retain metadata and annotation inputs, in addition to their content hashes.
A controlled 200-notebook check with one edited note generates and hashes **1 notebook instead of 200**.
The cache retains immutable input snapshots, not generated Markdown. It is cleared on folder changes,
invalidated before reconciliation and on missing files, and pruned when books leave the library.
Manual and periodic reconciliation still render and compare all notebooks; failed writes are not remembered.
Directory inventory and database reads still happen on automatic refreshes. This measures avoided work,
not end-to-end phone export latency.

Search builds one book-ID lookup per results update and shares it across titles, notes and content hits.
A Books, Notes or Contents selection constructs and collects only that category's query, instead of all
three. Hidden EPUB content does not load or decompress chapters. Switching categories cancels old queries;
All queries every category again. Filters remain available with zero matches, and counts are shown only
for queried categories after the current search lands. Existing background content indexing is unchanged.

## Notebook hash formatting — 2026-10-14

The shared SHA-256 helper uses Kotlin's standard `ByteArray.toHexString()` instead of invoking a formatter
for each of the 32 digest bytes. Notebook identities now reuse that helper. Digests and filenames retain
exactly the same lowercase, zero-padded encoding; input-stream ownership stays unchanged.

A controlled JVM comparison hashed 2,700 bytes per call, with 200 warm-up iterations and five batches of
500 calls. The median batch fell from 97.27 ms to 11.96 ms (8.1×). This measures hashing and hex formatting,
not complete notebook export time or phone performance. The encoder is stable since Kotlin 2.2; the project
uses Kotlin 2.3.21. See [Kotlin's hex encoder](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/to-hex-string.html).

## Search query processing — 2026-10-14

The tokenizer now stops splitting after eight useful words. A controlled JVM comparison used an eight-word
query followed by 20,000 trailing words, ten warm-up iterations and five batches of 30 calls. The median
batch fell from 102.37 ms to 3.03 ms (33.8×); this is parser time for a large pasted query, not phone latency.
Phrase processing also stops when the shared word budget is exhausted.

Search reuses query tokens for field matches and all 60 possible content excerpts. Scope options are sorted
on the background dispatcher only when the book stream changes, rather than also on shelf updates.
Selector labels, choice lists and filtered choices are remembered until their inputs change.

## Automatic notebook file comparisons — 2026-10-14

Automatic refreshes cache SHA-256 hashes only after a successful file comparison or write. A controlled test
with 200 previously verified notebooks and one edited notebook reduced comparison callbacks from **200 to 1**.
Unchanged automatic refreshes require zero notebook-content reads. Manual and periodic reconciliation bypass
the cache; failed checks and missing files invalidate entries, and folder changes clear it.

Decision: keep the process-local hash map. This measures avoided provider reads, not phone latency or battery
use. Markdown generation and directory inventory still run; no notebook contents are retained in the cache.

## Reading interval subtraction — 2026-10-08

Watch-session imports subtract already recorded intervals to avoid double-counting.
The covered intervals are sorted and merged before subtraction. Stop scanning when
the next interval starts beyond the source range, or coverage reaches its end.

An isolated JVM experiment used 1,000 source intervals and 1,000 later covered
intervals, with 200 warm-up calls and seven batches of 20 calls on each implementation.
The original median was **35.45 ms per batch** (32.17–42.54 ms); the early-exit
candidate median was **1.57 ms** (1.28–3.93 ms). This is a synthetic non-overlapping
case. An isolated rerun of the actual optimized implementation measured **2.01 ms**
(1.79–2.58 ms), about **17.6× faster** than the original median. These are not
measurements of phone/watch sync latency or UI responsiveness. Benefits
depend on interval placement; the worst-case complexity remains quadratic.

Decision: keep the two early exits. A deterministic test checks subtraction against
millisecond-level coverage for 200 sets of unordered, overlapping ranges, in addition
to existing pause-gap tests. No cache, dependency, or wire-format change is needed.
