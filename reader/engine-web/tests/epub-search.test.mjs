import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import { test } from 'node:test'
import { JSDOM } from 'jsdom'

// view.js imports ./search.js for in-book EPUB search; it once wasn't vendored, which broke search silently.
// text-walker.js reads the browser's NodeFilter when it loads and `document` when it walks.
globalThis.NodeFilter = new JSDOM('').window.NodeFilter
const load = async name => {
    const source = await readFile(new URL(`../src/main/assets/foliate/${name}`, import.meta.url), 'utf8')
    return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`)
}
const { searchMatcher } = await load('search.js')
const { textWalker } = await load('text-walker.js')

function documentOf(html) {
    const { window } = new JSDOM(`<html><body>${html}</body></html>`)
    globalThis.document = window.document
    return window.document
}

test('finds every match across element boundaries, with an excerpt', () => {
    const doc = documentOf('<p>The river ran <em>quiet</em> under the bridge.</p><p>A quiet town slept.</p>')
    const matcher = searchMatcher(textWalker, { defaultLocale: 'en' })

    const results = Array.from(matcher(doc, 'quiet'))

    assert.equal(results.length, 2)
    assert.equal(results[0].range.toString(), 'quiet')
    // The excerpt's context comes from the matched text node: none for a word alone in <em>.
    assert.deepEqual(results[1].excerpt, { pre: 'A ', match: 'quiet', post: ' town slept.' })
})

test('a match spanning three text nodes keeps its middle in the excerpt', () => {
    // Upstream makeExcerpt sliced the middle nodes by their strings, not their indices, and dropped "the whale".
    const doc = documentOf('<p>Call me Ishmael, <em>the</em> whale <b>swam</b> on past the pier.</p>')
    const matcher = searchMatcher(textWalker, { defaultLocale: 'en' })

    const [result] = Array.from(matcher(doc, 'the whale swam'))

    assert.equal(result.range.toString(), 'the whale swam')
    assert.equal(result.excerpt.match, 'the whale swam')
})

test('matches case- and accent-insensitively by default', () => {
    const doc = documentOf('<p>Café society, CAFE culture.</p>')
    const matcher = searchMatcher(textWalker, { defaultLocale: 'en' })

    assert.equal(Array.from(matcher(doc, 'cafe')).length, 2)
})

test('a phrase that is not there finds nothing', () => {
    const doc = documentOf('<p>Nothing to see here.</p>')
    const matcher = searchMatcher(textWalker, { defaultLocale: 'en' })

    assert.equal(Array.from(matcher(doc, 'dragon')).length, 0)
})

// The bridge's search plumbing, run without the Android reader around it.
const bridge = await readFile(new URL('../src/main/assets/bridge.js', import.meta.url), 'utf8')
const searchSource = bridge.slice(bridge.indexOf('let searchToken'), bridge.indexOf('function annotationForRenderedValue'))

function bridgeFixture() {
    const posted = []
    const context = vm.createContext({ post: (type, payload) => posted.push({ type, payload }), view: null, fixedLayout: false })
    vm.runInContext(searchSource, context)
    return { context, posted }
}

// A fake foliate view whose search yields one section of results as the test releases it.
function fakeView() {
    const log = []
    const runs = []
    return {
        log,
        runs,
        clearSearch() { log.push('clear') },
        search({ query }) {
            const run = { query, gates: [] }
            runs.push(run)
            log.push(`start:${query}`)
            const gate = () => new Promise(resolve => run.gates.push(resolve))
            return (async function* () {
                try {
                    await gate()
                    yield { progress: 0.5 }
                    yield {
                        label: 'Chapter 1',
                        subitems: [{ cfi: 'epubcfi(/6/2!/4/2,/1:0,/1:3)', excerpt: { pre: '  It was ', match: query, post: ' of\n times  ' } }],
                    }
                    await gate()
                    yield 'done'
                } finally {
                    log.push(`stop:${query}`)
                }
            })()
        },
    }
}

const tick = () => new Promise(resolve => setImmediate(resolve))
const release = async (view, index) => {
    while (!view.runs[index]?.gates.length) await tick()
    view.runs[index].gates.shift()()
}
const plain = value => JSON.parse(JSON.stringify(value))

test('search posts each excerpt as one plain line', async () => {
    const { context, posted } = bridgeFixture()
    const view = fakeView()
    context.view = view
    const done = context.search('best')
    await release(view, 0)
    await release(view, 0)
    await done
    assert.deepEqual(plain(posted), [{
        type: 'searchResults',
        payload: {
            query: 'best',
            results: [{ cfi: 'epubcfi(/6/2!/4/2,/1:0,/1:3)', excerpt: 'It was best of times', tocLabel: 'Chapter 1' }],
        },
    }])
})

test('searchExcerptText tolerates strings and missing parts', () => {
    const { context } = bridgeFixture()
    assert.equal(context.searchExcerptText(' already  text '), 'already text')
    assert.equal(context.searchExcerptText({ match: 'only' }), 'only')
    assert.equal(context.searchExcerptText(undefined), '')
})

test('a newer search waits for the superseded one to stop, and only the newer one posts', async () => {
    const { context, posted } = bridgeFixture()
    const view = fakeView()
    context.view = view
    context.search('old')
    while (!view.runs.length) await tick()
    const newer = context.search('new')
    await release(view, 0) // the old search draws its section, then notices it was replaced
    while (view.runs.length < 2) await tick()
    assert.deepEqual(view.log, ['start:old', 'stop:old', 'start:new'])
    await release(view, 1)
    await release(view, 1)
    await newer
    assert.deepEqual(posted.map(p => p.payload.query), ['new'])
})

test('a search replaced before it starts never reaches foliate', async () => {
    const { context, posted } = bridgeFixture()
    const view = fakeView()
    context.view = view
    context.search('typo')
    const latest = context.search('typed')
    await release(view, 0)
    await release(view, 0)
    await latest
    assert.deepEqual(view.runs.map(r => r.query), ['typed'])
    assert.deepEqual(posted.map(p => p.payload.query), ['typed'])
})

test('clearSearch drops in-flight results and clears again once the search stops', async () => {
    const { context, posted } = bridgeFixture()
    const view = fakeView()
    context.view = view
    context.search('gone')
    while (!view.runs.length) await tick()
    const cleared = context.clearSearch()
    assert.deepEqual(view.log, ['start:gone', 'clear'])
    await release(view, 0)
    await cleared
    assert.deepEqual(view.log, ['start:gone', 'clear', 'stop:gone', 'clear'])
    assert.deepEqual(posted, [])
})

test('a failure in a replaced search is not reported', async () => {
    const { context, posted } = bridgeFixture()
    let fail
    context.view = {
        clearSearch() {},
        search: () => (async function* () { await new Promise((_, reject) => { fail = reject }) })(),
    }
    const run = context.search('x')
    while (!fail) await tick()
    context.clearSearch()
    fail(new Error('boom'))
    await run
    assert.deepEqual(posted, [])
})

test('search failures are reported', async () => {
    const { context, posted } = bridgeFixture()
    context.view = { clearSearch() {}, search: () => (async function* () { throw new Error('boom') })() }
    await context.search('x')
    assert.deepEqual(plain(posted), [{ type: 'error', payload: { message: 'Search failed: boom' } }])
})
