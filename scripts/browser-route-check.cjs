// Run with Playwright available to Node; requires backend :8080, Vite :5173 and generated small-area graph.
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const b = fs.readFileSync(path.join(__dirname, '../backend/data/processed/graph-small-area.rgraph'));
  const n = b.readInt32BE(8), m = b.readInt32BE(12);
  const node = i => [b.readDoubleBE(20 + i * 24 + 16), b.readDoubleBE(20 + i * 24 + 8)];
  const offsets = Array.from({ length: n + 1 }, (_, i) => b.readInt32BE(20 + n * 24 + i * 4));
  const edgeBase = 20 + n * 24 + (n + 1) * 4;
  const source = e => offsets.findIndex((offset, i) => i < n && offset <= e && offsets[i + 1] > e);
  const onEdge = (e, t) => {
    const a = node(source(e)), z = node(b.readInt32BE(edgeBase + e * 20));
    return [a[0] + (z[0] - a[0]) * t, a[1] + (z[1] - a[1]) * t];
  };
  assert.ok(m > 6);
  const start = onEdge(0, .25), end = onEdge(6, .75);
  const browser = await chromium.launch({ headless: true, args: ['--enable-webgl', '--use-gl=angle', '--use-angle=swiftshader'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    // Test-only reference: no test globals or routes are added to the application source.
    await page.route('**/src/main.ts', async route => {
      const response = await route.fetch();
      await route.fulfill({ response, body: (await response.text()) + '\nwindow.__routeTestMap = map;\n' });
    });
    await page.goto('http://127.0.0.1:5173/');
    await page.waitForFunction(() => window.__routeTestMap?.isStyleLoaded(), undefined, { timeout: 45000 });
    await page.evaluate(([a, b]) => {
      window.__routeTestMap.fitBounds([[Math.min(a[0],b[0]), Math.min(a[1],b[1])], [Math.max(a[0],b[0]), Math.max(a[1],b[1])]], { padding: 180, maxZoom: 19, duration: 0 });
    }, [start, end]);
    const click = async coordinate => {
      const point = await page.evaluate(c => {
        const p = window.__routeTestMap.project(c), r = window.__routeTestMap.getCanvas().getBoundingClientRect();
        return { x: p.x + r.left, y: p.y + r.top };
      }, coordinate);
      await page.mouse.click(point.x, point.y);
    };
    await click(start);
    const response = page.waitForResponse(r => r.url().includes('/api/route?'));
    await click(end);
    const result = await response;
    if (result.status() !== 200) console.log({start,end,url:result.url(),body:await result.text()});
    assert.equal(result.status(), 200);
    const data = await result.json();
    assert.ok(data.routes[0].distance > 0);
    await page.waitForFunction(() => window.__routeTestMap.getLayer('route') && !document.querySelector('#route-info').classList.contains('hidden'));
    assert.match(await page.locator('#routing-status').innerText(), /근처 도로/);
    const positions = await page.evaluate(async () => (await window.__routeTestMap.getSource('route').getData()).geometry.coordinates.length);
    assert.equal(positions, data.routes[0].geometry.coordinates.length);
    await page.screenshot({ path: '/tmp/routing-segment-browser.png' });

    // A third click resets the previous result. Reverse travel on this one-way chain is impossible.
    await click(start);
    assert.equal(await page.evaluate(() => !!window.__routeTestMap.getLayer('route')), false);
    await click(end);
    const reverseResponse = page.waitForResponse(r => r.url().includes('/api/route?'));
    await click(start);
    assert.equal((await reverseResponse).status(), 404);
    await page.waitForFunction(() => document.querySelector('#routing-status').textContent.includes('연결하는 경로가 없습니다'));
    await click(start);
    // Outside the supported graph must show a useful error.
    await page.evaluate(() => window.__routeTestMap.jumpTo({ center: [128, 38], zoom: 17 }));
    await click([128, 38]);
    const outsideResponse = page.waitForResponse(r => r.url().includes('/api/route?'));
    await click([128.001, 38]);
    assert.equal((await outsideResponse).status(), 422);
    await page.waitForFunction(() => document.querySelector('#routing-status').textContent.includes('지원 영역'));
    assert.equal(await page.locator('#route-info').evaluate(e => e.classList.contains('hidden')), true);

    // Reset while an actual request response is delayed: stale results must not reappear.
    await click([128, 38]);
    await page.evaluate(([a, b]) => window.__routeTestMap.fitBounds([[Math.min(a[0],b[0]), Math.min(a[1],b[1])], [Math.max(a[0],b[0]), Math.max(a[1],b[1])]], { padding: 180, maxZoom: 19, duration: 0 }), [start, end]);
    let started;
    const requestStarted = new Promise(resolve => { started = resolve; });
    await page.route('**/api/route?**', async route => {
      const fetched = await route.fetch();
      started();
      await new Promise(resolve => setTimeout(resolve, 500));
      await route.fulfill({ response: fetched }).catch(() => {});
    });
    await click(start); await click(end); await requestStarted; await click(start);
    await page.waitForTimeout(800);
    assert.equal(await page.evaluate(() => !!window.__routeTestMap.getLayer('route')), false);
    assert.match(await page.locator('#routing-status').innerText(), /다시 선택/);
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ status: 'passed', distance: data.routes[0].distance, positions, checks: ['route-render', 'reset', 'oneway-404', 'outside-422', 'stale-request'], screenshot: '/tmp/routing-segment-browser.png' }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
