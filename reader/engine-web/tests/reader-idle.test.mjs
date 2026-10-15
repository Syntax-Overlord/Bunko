import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import vm from 'node:vm'

const bridge = await readFile(new URL('../src/main/assets/bridge.js', import.meta.url), 'utf8')

function harness() {
    let now = 0
    const timers = []
    const handlers = new Map()
    const doc = { defaultView: { frameElement: { isConnected: true }, addEventListener() {} },
        addEventListener: (type, handler) => handlers.set(type, handler) }
    const context = vm.createContext({ Date: { now: () => now },
        setTimeout: (run, delay) => { timers.push({ run, delay }); return timers.length },
    })
    vm.runInContext(bridge.slice(bridge.indexOf('const ReaderIdleMillis'), bridge.indexOf('// Dynamic page-count')), context)
    context.wireReaderActivity(doc)
    const advance = async () => {
        const timer = timers.shift()
        now += timer.delay
        timer.run()
        await Promise.resolve()
    }
    return { context, doc, timers, advance, event: (type, pointerId = 1) => handlers.get(type)?.({ pointerId }),
        setTime: value => { now = value } }
}

test('pointer hover does not postpone background work', () => {
    const h = harness()
    h.event('pointermove')
    assert.equal(h.context.readerWorkDelay(), 0)
    h.event('pointerdown')
    h.event('pointerup')
    h.setTime(200)
    h.event('pointermove')
    h.setTime(250)
    assert.equal(h.context.readerWorkDelay(), 0)
})

test('background work waits for a held gesture and the quiet period after release', async () => {
    const h = harness()
    h.event('pointerdown')
    h.setTime(1000)
    assert.equal(h.context.readerWorkDelay(), 250, 'a held pointer stays busy beyond the quiet period')
    let finished = false
    const work = h.context.waitForReaderIdle(() => false).then(() => { finished = true })
    await h.advance()
    assert.equal(finished, false)
    h.event('pointerup')
    await h.advance()
    await work
    assert.equal(finished, true)
    assert.equal(h.context.readerWorkDelay(), 0)
})

test('cancelled work and a detached chapter cannot leave background work blocked', async () => {
    const h = harness()
    h.event('pointerdown')
    let cancelled = false
    const work = h.context.waitForReaderIdle(() => cancelled)
    cancelled = true
    await h.advance()
    await work
    assert.equal(h.timers.length, 0)
    h.doc.defaultView.frameElement.isConnected = false
    h.setTime(1000)
    assert.equal(h.context.readerWorkDelay(), 0)
})

test('a pinch stays busy until both fingers lift', () => {
    const h = harness()
    h.event('pointerdown', 1)
    h.event('pointerdown', 2)
    h.event('pointerup', 1)
    h.setTime(1000)
    assert.equal(h.context.readerWorkDelay(), 250)
    h.event('pointercancel', 2)
    h.setTime(1500)
    assert.equal(h.context.readerWorkDelay(), 0)
})

test('quote matching coalesces while busy and discards chapters that have left the reader', () => {
    const h = harness()
    Object.assign(h.context, {
        activeAnnotationsList: [{ value: 'quote:1', text: 'A passage to match' }],
        completedMatchingRevision: new WeakMap(), annotationRevision: 1,
        quoteMatchTimers: new WeakMap(), view: { renderer: { getContents: () => [] } },
    })
    vm.runInContext(bridge.slice(bridge.indexOf('function matchTextAnnotationsForDoc'), bridge.indexOf('function highlightCount')), h.context)
    h.event('pointerdown')
    h.context.matchTextAnnotationsForDoc(h.doc, 0)
    h.context.matchTextAnnotationsForDoc(h.doc, 0)
    assert.equal(h.timers.length, 1)
    h.timers.shift().run()
    assert.equal(h.timers.length, 0)
    assert.equal(h.context.quoteMatchTimers.has(h.doc), false)
    h.context.matchTextAnnotationsForDoc(h.doc, 0)
    h.context.view = {} // The next book has not created its renderer yet.
    assert.doesNotThrow(() => h.timers.shift().run())
})

test('deferred quotes match the loaded chapter once reading is idle', () => {
    const h = harness()
    let scans = 0
    Object.assign(h.context, {
        activeAnnotationsList: [{ value: 'quote:1', text: 'A passage to match' }],
        completedMatchingRevision: new WeakMap(), annotationRevision: 1,
        quoteMatchTimers: new WeakMap(), unmatchedInDoc: new WeakMap(), annotationApplyGeneration: 0,
        resolvedTextAnnotations: new Map(), pendingTextAnnotations: new Set(),
        isTextAnnotationValue: () => true,
        findTextRangeInDoc: () => { scans++; return null },
        view: { renderer: { getContents: () => [{ doc: h.doc, index: 0 }] } },
    })
    vm.runInContext(bridge.slice(bridge.indexOf('function matchTextAnnotationsForDoc'), bridge.indexOf('function highlightCount')), h.context)
    h.event('pointerdown')
    h.context.matchTextAnnotationsForDoc(h.doc, 0)
    assert.equal(scans, 0)
    h.event('pointerup')
    h.setTime(500)
    h.timers.shift().run()
    assert.equal(scans, 1)
    assert.equal(h.context.completedMatchingRevision.get(h.doc), 1)
})
