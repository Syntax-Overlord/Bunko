# Unreleased

- Stop sync when a snapshot references a missing cloud slice, preserving the cloud snapshot instead of treating the missing records as empty.
- Block scripts embedded in EPUB book resources from reaching reader controls, and ignore malformed reader bridge events.
- Reject community-quote slices exceeding the reader's 16 MiB expansion limit before writing any cloud snapshot files.

- Resolve saved cover paths in Stats through the shared library path resolver so reading-record covers display correctly.
- Add reading records to Stats with covers, book details, start/finish dates and accumulated recorded time, including physical books still in progress. Tap a record to open Book details.
- Record a finish date when a physical or other page-tracked offline book is added at its final page, so yearly books-read statistics include it. Explicit finish dates remain unchanged.
- Prevent invalid nonpositive physical-book page totals from crashing Wear OS catalog sync; treat them as unknown totals.
- Stop reading-interval subtraction scans when sorted coverage cannot affect the remaining source range. See the [measured experiment](../PERFORMANCE.md).

See [Vayana 0.90](v0.90.md) for the previously released changes since v0.87.
