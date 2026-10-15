import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import vm from 'node:vm'
import { test } from 'node:test'

const bridge = await readFile(new URL('../src/main/assets/bridge.js', import.meta.url), 'utf8')
const source = bridge.slice(bridge.indexOf('function applyReaderMargin('), bridge.indexOf('function applyStyle('))

test('margin changes render once and height-only keyboard resizes do not repeat the margin render', () => {
    let attribute = null, renders = 0
    const renderer = {
        getAttribute: () => attribute,
        setAttribute: (_, value) => { attribute = value; renders++ },
        render: () => { renders++ },
    }
    const context = vm.createContext({ view: { renderer }, readerSideMarginPercent: 10,
        window: {}, innerWidth: 500, innerHeight: 1000 })
    vm.runInContext(source, context)
    context.applyReaderMargin(10)
    assert.equal(attribute, '400px')
    assert.equal(renders, 1, 'paginator attribute callback already renders')
    context.innerHeight = 600
    context.applyReaderMargin(10)
    assert.equal(renders, 1, 'ResizeObserver handles the height change')
    context.applyReaderMargin(20)
    assert.equal(attribute, '300px')
    assert.equal(renders, 2)
    context.innerWidth = 1000
    context.innerHeight = 700
    context.applyReaderMargin(10)
    assert.equal(attribute, '360px')
    assert.equal(renders, 3)
})

test('unchanged typography preserves pagination and margin-only changes do not rewrite CSS', () => {
    const operations = { styles: 0, resets: 0, counts: 0 }
    const renderer = { setStyles: () => { operations.styles++ } }
    const context = vm.createContext({ view: { renderer }, readerSideMarginPercent: 10,
        resetPageEstimate: () => { operations.resets++ },
        schedulePageCount: () => { operations.counts++ },
        applyReaderMargin: margin => { context.readerSideMarginPercent = margin },
    })
    const styleStart = bridge.indexOf('const appliedReaderStyles')
    vm.runInContext(bridge.slice(styleStart >= 0 ? styleStart : bridge.indexOf('function applyStyle('),
        bridge.indexOf('const BionicWordClass')), context)
    for (let i = 0; i < 100; i++) context.applyStyle('body{font-size:100%}', 10)
    assert.deepEqual(operations, { styles: 1, resets: 1, counts: 1 })
    context.applyStyle('body{font-size:100%}', 20)
    assert.deepEqual(operations, { styles: 1, resets: 2, counts: 2 })
    context.applyStyle('body{font-size:120%}', 20)
    assert.deepEqual(operations, { styles: 2, resets: 3, counts: 3 })
    context.view = { renderer: { setStyles: renderer.setStyles } }
    context.applyStyle('body{font-size:120%}', 20)
    assert.deepEqual(operations, { styles: 3, resets: 4, counts: 4 }, 'a replacement renderer needs its own style')
})
