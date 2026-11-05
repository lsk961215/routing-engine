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
    const traceReady = async () => page.waitForFunction(() => document.querySelector('#trace-play')?.disabled === false, {}, { timeout: 60000 });
    const checkCompleted = async example => {
      await traceReady();
      for (const route of example.routes) {
        const n = route.expandedStates.toLocaleString('ko-KR');
        assert.equal(await page.textContent(`#trace-${route.algorithm}-count`), `${n} / ${n} · 완료`);
      }
    };
    assert.equal(await page.locator('#example option').count(), 8);
    for (const example of data.examples) {
      await page.selectOption('#example', example.id);
      await checkCompleted(example);
      assert.equal(await page.textContent('#route-name'), example.name);
      assert.equal(new URL(page.url()).hash, '#' + example.id);
      assert.equal(await page.locator('.endpoint').count(), 2);
      for (const result of example.routes) {
        assert.equal(await page.textContent(`#${result.algorithm}-time`), result.searchMillis.median.toLocaleString('ko-KR', { maximumFractionDigits: 2 }));
      }
    }
    await page.selectOption('#example', data.examples[0].id);
    await checkCompleted(data.examples[0]);
    // Compare rendered pixels, not just checkbox state, to verify both expansion layers disappear.
    const colors = async () => {
      const buffer = await page.locator('.map-panel').screenshot();
      return page.evaluate(async encoded => {
        const image = new Image(); image.src = 'data:image/png;base64,' + encoded; await image.decode();
        const canvas = document.createElement('canvas'); canvas.width = image.width; canvas.height = image.height;
        const ctx = canvas.getContext('2d'); ctx.drawImage(image, 0, 0);
        const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        let blue = 0, orange = 0;
        for (let i = 0; i < pixels.length; i += 4) {
          const [r,g,b] = [pixels[i],pixels[i+1],pixels[i+2]];
          if (b > r + 40 && b > g + 15 && b > 120) blue++;
          if (r > 140 && r > g * 1.35 && g > b * 1.3) orange++;
        }
        return {blue,orange};
      }, buffer.toString('base64'));
    };
    await page.waitForTimeout(500);
    const fullColors = await colors();
    await page.click('#trace-start');
    assert.equal(await page.inputValue('#trace-position'), '0');
    await page.waitForTimeout(300);
    const emptyColors = await colors();
    assert.ok(fullColors.blue > emptyColors.blue + 1000, JSON.stringify({fullColors,emptyColors}));
    assert.ok(fullColors.orange > emptyColors.orange + 100, JSON.stringify({fullColors,emptyColors}));
    await page.click('#trace-play');
    await page.waitForFunction(() => Number(document.querySelector('#trace-position').value) > 30);
    await page.click('#trace-play');
    const paused = await page.inputValue('#trace-position');
    await page.waitForTimeout(250);
    assert.equal(await page.inputValue('#trace-position'), paused);
    await page.locator('#trace-position').evaluate(e => { e.value = '500'; e.dispatchEvent(new Event('input', {bubbles:true})); });
    const max = Math.max(...data.examples[0].routes.map(r => r.expandedStates));
    const ordinal = Math.floor(max / 8);
    assert.equal(await page.textContent('#trace-count'), `같은 확장 횟수 ${ordinal.toLocaleString('ko-KR')}회`);
    assert.match(await page.textContent('#trace-astar-count'), /완료$/);
    assert.doesNotMatch(await page.textContent('#trace-dijkstra-count'), /완료$/);
    await page.click('#trace-end');
    await checkCompleted(data.examples[0]);
    await page.uncheck('#show-exploration');
    await page.waitForTimeout(200);
    const hiddenColors = await colors();
    assert.ok(fullColors.blue > hiddenColors.blue + 1000);
    await page.check('#show-exploration');
    await page.uncheck('#show-astar');
    await page.selectOption('#example', data.examples[2].id);
    await checkCompleted(data.examples[2]);
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
    await checkCompleted(data.examples[1]);
    await page.waitForTimeout(700);
    assert.ok((await colors()).blue > 1000, 'Mobile exploration layer must render');
    await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
    // Loading failures can be retried without losing the metric table.
    await page.route('**/traces/' + data.examples[0].id + '.json', route => route.abort());
    await page.selectOption('#example', data.examples[0].id);
    await page.waitForFunction(() => !document.querySelector('#trace-retry').hidden);
    assert.equal(await page.isDisabled('#trace-play'), true);
    assert.equal(await page.textContent('#route-name'), data.examples[0].name);
    await page.unroute('**/traces/' + data.examples[0].id + '.json');
    await page.click('#trace-retry');
    await checkCompleted(data.examples[0]);
    // An old, delayed response cannot replace the currently selected trace.
    let release, started;
    const gate = new Promise(resolve => { release = resolve; });
    const seen = new Promise(resolve => { started = resolve; });
    const delayed = data.examples[3];
    await page.route('**/traces/' + delayed.id + '.json', async route => {
      started(); await gate;
      const trace = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../frontend/demo/public/traces/' + delayed.id + '.json'), 'utf8'));
      await route.fulfill({json:trace}).catch(() => {});
    });
    await page.selectOption('#example', delayed.id); await seen;
    await page.selectOption('#example', data.examples[7].id);
    await checkCompleted(data.examples[7]); release();
    await page.waitForTimeout(200);
    await checkCompleted(data.examples[7]);
    await page.unroute('**/traces/' + delayed.id + '.json');
    await page.click('#trace-play');
    await page.waitForFunction(() => Number(document.querySelector('#trace-position').value) > 20);
    await page.selectOption('#example', data.examples[1].id);
    await checkCompleted(data.examples[1]);
    assert.equal(await page.getAttribute('#trace-play', 'aria-pressed'), 'false');
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
    console.log('PASS: 8 examples, expansion colors, playback/pause/scrub, visibility, stale responses, retry, real metrics, links/download, mobile, map failure, no API, Worker 200');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
