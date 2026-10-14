# Performance experiments

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
