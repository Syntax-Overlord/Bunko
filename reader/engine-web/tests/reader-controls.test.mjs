import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import vm from 'node:vm'

const bridge = await readFile(new URL('../src/main/assets/bridge.js', import.meta.url), 'utf8')
const start = bridge.indexOf('const DoubleTapWindowMillis')
const end = bridge.indexOf('function selectWordAt', start)
assert.ok(start >= 0 && end > start, 'reader tap arbitration not found in bridge.js')

function harness({ word = false, fixed = false, selected = false } = {}) {
    const posted = []
    const timers = new Map()
    let timerId = 0
    let clicks
    const handlers = new Map()
    let lookups = 0
    let zooms = 0
    const context = vm.createContext({
        Math,
        Number,
        WeakMap,
        fixedLayout: fixed,
        publishedAnnotationId: null,
        window: { innerWidth: 900, innerHeight: 900 },
        post: (type, payload) => posted.push({ type, payload }),
        selectWordAt: () => {
            lookups++
            return word
        },
        toggleFixedZoom: () => { zooms++ },
        wireReaderActivity: () => {},
        setTimeout: callback => {
            const id = ++timerId
            timers.set(id, callback)
            return id
        },
        clearTimeout: id => timers.delete(id),
    })
    vm.runInContext(bridge.slice(start, end), context)
    const doc = {
        defaultView: { top: { innerWidth: 900, innerHeight: 900 } },
        addEventListener: (type, handler) => { handlers.set(type, handler); if (type === 'click') clicks = handler },
        getSelection: () => ({ type: selected ? 'Range' : 'Caret', isCollapsed: !selected }),
    }
    context.wireDoubleTapLookup(doc)
    const click = (time, x = 450, y = 300) => clicks({
        target: { closest: () => null },
        screenX: x,
        clientX: x,
        clientY: y,
        timeStamp: time,
    })
    const flush = () => {
        const pending = [...timers.values()]
        timers.clear()
        pending.forEach(callback => callback())
    }
    return {
        context,
        doc,
        posted,
        click,
        pointerDown: (time, x = 450, y = 300) => handlers.get('pointerdown')({ timeStamp: time, clientX: x, clientY: y }),
        flush,
        lookups: () => lookups,
        zooms: () => zooms,
    }
}

test('single center tap reveals indicators, while double tap opens controls', () => {
    const h = harness()
    h.click(0)
    assert.equal(h.posted.length, 0)
    h.flush()
    assert.deepEqual(h.posted.map(event => event.type), ['readingInfoRequested'])

    const double = harness()
    double.click(0)
    double.click(100)
    assert.deepEqual(double.posted.map(event => event.type), ['controlsRequested'])
})

test('single taps anywhere in the center strip consistently reveal indicators', () => {
    for (const y of [100, 800]) {
        const h = harness()
        h.click(0, 450, y)
        h.flush()
        assert.deepEqual(h.posted.map(event => event.type), ['readingInfoRequested'])
    }
})

test('center hit testing accounts for the scrolled chapter iframe position', () => {
    const h = harness()
    h.doc.defaultView.frameElement = { getBoundingClientRect: () => ({ left: -1800, top: 100 }) }
    h.click(0, 2250, 350)
    h.flush()
    assert.deepEqual(h.posted.map(event => event.type), ['readingInfoRequested'])
})

test('the single-tap controls preference applies throughout the center strip', () => {
    for (const y of [100, 450, 800]) {
        const h = harness()
        h.context.setReaderControlsGesture(1, false, false)
        h.click(0, 450, y)
        h.flush()
        assert.deepEqual(h.posted.map(event => event.type), ['controlsRequested'])
    }
})

test('a selection tap never reveals indicators', () => {
    const h = harness({ selected: true })
    h.click(0)
    h.flush()
    assert.equal(h.posted.length, 0)
})

test('highlight taps suppress the pending indicator reveal', () => {
    const h = harness()
    h.click(0)
    h.context.publishedAnnotationId = 'highlight-42'
    h.flush()
    assert.equal(h.posted.length, 0)
})

test('a selection started during the single-tap delay suppresses the reveal', () => {
    const h = harness()
    h.click(0)
    h.doc.getSelection = () => ({ type: 'Range', isCollapsed: false })
    h.flush()
    assert.equal(h.posted.length, 0)
})

test('long presses and dragged selections are not center taps', () => {
    for (const [time, x] of [[600, 450], [100, 480]]) {
        const h = harness()
        h.pointerDown(0)
        h.click(time, x)
        h.flush()
        assert.equal(h.posted.length, 0)
    }
})

test('double-tap controls preserve dictionary lookup on a word', () => {
    const word = harness({ word: true })
    word.context.setReaderControlsGesture(2, false, false)
    word.click(0)
    word.click(100)
    assert.equal(word.lookups(), 1)
    assert.equal(word.posted.length, 0)

    const blank = harness()
    blank.context.setReaderControlsGesture(2, false, false)
    blank.click(0)
    blank.click(100)
    assert.deepEqual(blank.posted.map(event => event.type), ['controlsRequested'])
})

test('triple tap wins over the delayed double-tap action', () => {
    const h = harness({ word: true })
    h.context.setReaderControlsGesture(3, false, false)
    h.click(0)
    h.click(100)
    h.click(200)
    h.flush()
    assert.equal(h.lookups(), 0)
    assert.deepEqual(h.posted.map(event => event.type), ['controlsRequested'])
})

test('reader controls never open while a text selection remains active', () => {
    for (const tapCount of [1, 2, 3]) {
        const h = harness({ selected: true })
        h.context.setReaderControlsGesture(tapCount, false, false)
        h.click(0)
        h.click(100)
        if (tapCount === 3) h.click(200)
        h.flush()
        assert.equal(h.posted.length, 0)
    }
})

test('visible controls and read-aloud block new control requests', () => {
    for (const [visible, blocked] of [[true, false], [false, true]]) {
        const h = harness()
        h.context.setReaderControlsGesture(1, visible, blocked)
        h.click(0)
        h.flush()
        assert.equal(h.posted.length, 0)
    }
})
