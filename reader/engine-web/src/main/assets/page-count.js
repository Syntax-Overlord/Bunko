// Count real layouts one chapter at a time without navigating the visible reader.
export async function measureBookPages(renderer, sections, isCancelled, yieldWork, prepareDocument = () => {}) {
    if (isCancelled()) return null
    await yieldWork()
    if (isCancelled()) return null
    const counter = renderer.createPageCounter()
    const counts = new Map()
    try {
        for (let index = 0; index < sections.length; index++) {
            if (isCancelled()) return null
            if (sections[index].linear === 'no') continue
            await counter.goTo({ index })
            if (isCancelled()) return null
            const content = counter.getContents()[0]
            if (content?.index !== index || !content.doc) return null
            prepareDocument(content.doc)
            await content.doc.fonts.ready
            if (isCancelled()) return null
            await yieldWork()
            if (isCancelled()) return null
            // render() lays out synchronously, so pages is current without another yield.
            counter.render()
            const pages = counter.pages - 2
            if (!Number.isFinite(pages) || pages < 1) return null
            counts.set(index, pages)
        }
        return counts
    } finally {
        counter.destroy()
        counter.remove()
    }
}

// Everything that changes how many pages a chapter takes. Colours never do, so a theme switch keeps its counts.
export function pageLayoutKey(css, sideMargin, width, height, bionic) {
    return JSON.stringify([String(css ?? '').replace(/#[0-9a-f]{6}\b/gi, ''), sideMargin, width, height, Boolean(bionic)])
}

// Counts saved for this layout, or a fresh measurement that is saved under it. `measured` says which.
export async function layoutPageCounts(cache, layout, measure) {
    const saved = cache.get(layout)
    if (saved) return { counts: saved, measured: false }
    const counts = await measure()
    if (counts) cache.set(layout, counts)
    return { counts, measured: Boolean(counts) }
}
