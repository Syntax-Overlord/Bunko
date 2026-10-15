import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFile } from 'node:fs/promises'

const source = await readFile(new URL('../src/main/assets/page-count.js', import.meta.url), 'utf8')
const { layoutPageCounts, measureBookPages, pageLayoutKey } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)

function fixture() {
    let index = -1, destroyed = false, removed = false
    const counter = {
        goTo: async target => { index = target.index },
        getContents: () => [{ index, doc: { fonts: { ready: Promise.resolve() } } }],
        render() {}, get pages() { return [4, 102, 9][index] },
        destroy() { destroyed = true }, remove() { removed = true },
    }
    return { counter, renderer: { createPageCounter: () => counter },
        cleaned: () => destroyed && removed }
}

test('counts actual chapter layouts, excluding non-linear sections', async () => {
    const f = fixture()
    const counts = await measureBookPages(f.renderer, [{}, { linear: 'no' }, {}], () => false, async () => {})
    assert.deepEqual([...counts], [[0, 2], [2, 7]])
    assert.equal(f.cleaned(), true)
})

test('a resize during a count discards the old layout and releases its renderer', async () => {
    const f = fixture()
    let cancelled = false
    let yields = 0
    const counts = await measureBookPages(f.renderer, [{}, {}], () => cancelled, async () => { cancelled = ++yields === 3 })
    assert.equal(counts, null)
    assert.equal(f.cleaned(), true)
})

test('a failed section cannot be mistaken for the previously measured chapter', async () => {
    const f = fixture()
    f.counter.goTo = async () => {}
    assert.equal(await measureBookPages(f.renderer, [{}], () => false, async () => {}), null)
    assert.equal(f.cleaned(), true)
})

test('a superseded count does not create a hidden renderer', async () => {
    let created = 0
    const renderer = { createPageCounter: () => { created++; return fixture().counter } }
    assert.equal(await measureBookPages(renderer, [{}], () => true, async () => {}), null)
    assert.equal(created, 0)
})

test('a layout invalidated while fonts load skips rendering and yielding', async () => {
    const f = fixture()
    let cancelled = false, renders = 0, yields = 0
    f.counter.getContents = () => [{ index: 0, doc: {
        fonts: { ready: Promise.resolve().then(() => { cancelled = true }) },
    } }]
    f.counter.render = () => { renders++ }
    const counts = await measureBookPages(f.renderer, [{}], () => cancelled, async () => { yields++ })
    assert.equal(counts, null)
    assert.equal(renders, 0)
    assert.equal(yields, 1, 'only the initial idle wait ran')
    assert.equal(f.cleaned(), true)
})

test('a saved layout is reused without measuring, and a changed layout measures again', async () => {
    const cache = new Map()
    const css = 'body{color:#112233 !important;font-size:100% !important;}'
    const layout = pageLayoutKey(css, 10, 400, 800, false)
    let measures = 0
    const measure = async () => { measures++; return measureBookPages(fixture().renderer, [{}, {}], () => false, async () => {}) }

    const first = await layoutPageCounts(cache, layout, measure)
    assert.equal(first.measured, true)
    assert.deepEqual([...first.counts], [[0, 2], [1, 100]])

    // Reopening (counts restored from Kotlin) or a theme change: same pagination, no measuring pass.
    const restored = new Map([[layout, new Map(first.counts)]])
    const sameLayout = pageLayoutKey(css.replace('#112233', '#EEEEEE'), 10, 400, 800, false)
    const hit = await layoutPageCounts(restored, sameLayout, measure)
    assert.equal(hit.measured, false)
    assert.deepEqual([...hit.counts], [[0, 2], [1, 100]])
    assert.equal(measures, 1)

    // The read-aloud panel shortens the viewport: a different layout, so it is measured.
    const shorter = pageLayoutKey(css, 10, 400, 600, false)
    assert.equal((await layoutPageCounts(cache, shorter, measure)).measured, true)
    assert.equal(measures, 2)
    // Closing the panel returns to the first layout's counts.
    assert.equal((await layoutPageCounts(cache, layout, measure)).measured, false)
    assert.equal(measures, 2)

    for (const changed of [pageLayoutKey(css.replace('100%', '110%'), 10, 400, 800, false),
        pageLayoutKey(css, 12, 400, 800, false), pageLayoutKey(css, 10, 500, 800, false),
        pageLayoutKey(css, 10, 400, 800, true)]) {
        assert.notEqual(changed, layout)
    }
})

test('a cancelled count leaves no saved layout behind', async () => {
    const cache = new Map()
    const result = await layoutPageCounts(cache, 'layout', async () => null)
    assert.deepEqual(result, { counts: null, measured: false })
    assert.equal(cache.size, 0)
})
