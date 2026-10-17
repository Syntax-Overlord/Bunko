# Unreleased

- Sync Home Library books between devices: a device without Home Library now gets them, read-only, with their reading progress, dates, sessions and time taken, so Stats and the Today card match. Home Library's catalog on the mirroring device is never overwritten by an older copy. Books removed from Home Library stay on other devices. (Database v38 and v39.)
- Write sync snapshots in a compact format: books move into their own zipped slice and every slice is zipped, so each background sync uploads about a third as much (240 KB of book data becomes about 64 KB). Every syncing device needs this version, since 0.92 and older can't read the format.
- Keep the screen on during a Goodreads import, in the in-app browser and in the Fetch from Goodreads dialog.
- Keep measured page counts when switching between light and dark themes, instead of measuring the whole book again.

See [Vayana 0.92](v0.92.md) for the previously released changes since v0.90.
