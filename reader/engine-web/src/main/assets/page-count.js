// Count real layouts one chapter at a time without navigating the visible reader.
export async function measureBookPages(renderer, sections, isCancelled, yieldWork, prepareDocument = () => {}) {
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
            counter.render()
            await yieldWork()
            if (isCancelled()) return null
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
