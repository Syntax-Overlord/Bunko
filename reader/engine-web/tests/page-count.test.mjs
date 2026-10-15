import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFile } from 'node:fs/promises'

const source = await readFile(new URL('../src/main/assets/page-count.js', import.meta.url), 'utf8')
const { measureBookPages } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)

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
