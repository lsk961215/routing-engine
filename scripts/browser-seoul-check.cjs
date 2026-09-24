const { selectTestRoute } = require('./browser-route-helpers.cjs');
// Requires Seoul backend :8080, Vite :5173, and Playwright available to Node.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--enable-webgl', '--use-gl=angle', '--use-angle=swiftshader'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.route('**/src/main.ts*', async route => {
      const response = await route.fetch();
      await route.fulfill({ response, body: (await response.text()) + '\nwindow.__routeTestMap = map;\n' });
    });
    await page.goto('http://127.0.0.1:5173/');
    await page.waitForFunction(() => !document.querySelector('#select-start').disabled, undefined, { timeout: 45000 });
    const pending = page.waitForResponse(r => r.url().includes('/api/route?'));
    await selectTestRoute(page);
    const response = await pending;
    assert.equal(response.status(), 200);
    const data = await response.json();
    assert.ok(data.routes[0].distance > 9000 && data.routes[0].distance < 20000);
    await page.waitForFunction(() => window.__routeTestMap?.getLayer('route') && !document.querySelector('#route-info').classList.contains('hidden'));
    const rendered = await page.evaluate(async () => (await window.__routeTestMap.getSource('route').getData()).geometry.coordinates);
    assert.deepEqual(rendered, data.routes[0].geometry.coordinates);
    assert.match(await page.locator('#route-distance').innerText(), /km/);
    await page.waitForFunction(() => window.__routeTestMap.queryRenderedFeatures({ layers: ['route'] }).length > 0);
    await page.screenshot({ path: '/tmp/routing-seoul-browser.png' });
    // Repeated requests replace the existing source without duplicate layers.
    const repeated = page.waitForResponse(r => r.url().includes('/api/route?'));
    await selectTestRoute(page);
    assert.equal((await repeated).status(), 200);
    await page.waitForFunction(() => !!window.__routeTestMap.getLayer('route'));
    assert.deepEqual(errors, []);
    console.log('Seoul browser PASS:', data.routes[0].distance, 'metres,', rendered.length, 'coordinates');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exit(1); });
