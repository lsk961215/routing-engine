// Run against a static production preview or the published GitHub Pages URL.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const data = require('../frontend/src/demo-data.json');
const base = process.env.DEMO_URL || 'http://127.0.0.1:4173/routing-engine/';
const output = path.resolve(__dirname, '../.runtime/demo-check');
fs.mkdirSync(output, { recursive: true });
(async () => {
  const browser = await chromium.launch({ headless: true, args: ['--enable-webgl', '--use-gl=angle', '--use-angle=swiftshader'] });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    const errors = [], apiRequests = [], badAssets = [], workers = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', request => { if (new URL(request.url()).pathname.startsWith('/api/')) apiRequests.push(request.url()); });
    page.on('response', response => {
      if (response.url().startsWith(base) && response.status() >= 400) badAssets.push(response.url());
      if (response.url().includes('maplibre-gl-worker')) workers.push(response.status());
    });
    await page.goto(base);
    await page.waitForFunction(() => document.querySelector('#fit-route')?.disabled === false, {}, { timeout: 60000 });
    assert.equal(await page.locator('#example option').count(), 8);
    for (const example of data.examples) {
      await page.selectOption('#example', example.id);
      assert.equal(await page.textContent('#route-name'), example.name);
      assert.equal(new URL(page.url()).hash, '#' + example.id);
      assert.equal(await page.locator('.endpoint').count(), 2);
      for (const result of example.routes) {
        assert.equal(await page.textContent(`#${result.algorithm}-time`), result.searchMillis.median.toLocaleString('ko-KR', { maximumFractionDigits: 2 }));
      }
    }
    await page.selectOption('#example', data.examples[0].id);
    await page.uncheck('#show-astar');
    await page.selectOption('#example', data.examples[2].id);
    assert.equal(await page.isChecked('#show-astar'), false);
    await page.check('#show-astar');
    await page.click('#fit-route');
    await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true });
    await page.click('a[href="#how-it-works"]');
    assert.equal(await page.inputValue('#example'), data.examples[2].id);
    await page.click('.methodology summary');
    const downloadPromise = page.waitForEvent('download');
    await page.click('#download-data');
    const download = await downloadPromise;
    const downloaded = JSON.parse(fs.readFileSync(await download.path(), 'utf8'));
    assert.deepEqual(downloaded, data);
    await page.goto(base + '#' + data.examples[4].id);
    assert.equal(await page.inputValue('#example'), data.examples[4].id);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(() => document.querySelector('#fit-route')?.disabled === false);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await page.selectOption('#example', data.examples[1].id);
    await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
    // Metrics and example selection still work if the external map is unavailable.
    await page.route('https://tiles.openfreemap.org/**', route => route.abort());
    await page.reload();
    await page.waitForFunction(() => document.querySelector('#map-status')?.textContent.includes('불러오지 못했습니다'));
    await page.selectOption('#example', data.examples[7].id);
    assert.equal(await page.textContent('#route-name'), data.examples[7].name);
    assert.deepEqual(apiRequests, []);
    assert.deepEqual(badAssets, []);
    assert.ok(workers.includes(200));
    assert.deepEqual(errors, []);
    console.log('PASS: 8 static examples, real metrics, toggles, hash links, JSON download, mobile, map failure, no API requests, Worker 200');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
