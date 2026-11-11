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
    const chooseExample = async id => {
      await page.click('#example');
      await page.getByRole('option', {name: data.examples.find(example => example.id === id).name, exact: true}).click();
    };
    const traceReady = async () => page.waitForFunction(() => document.querySelector('#trace-play')?.disabled === false, {}, { timeout: 60000 });
    const checkAutoplay = async () => {
      await traceReady();
      assert.equal(await page.getAttribute('#trace-play', 'aria-pressed'), 'true');
      assert.ok(Number(await page.getAttribute('#trace-position', 'aria-valuenow')) < 250, 'New route must replay near the beginning');
      const position = Number(await page.getAttribute('#trace-position', 'aria-valuenow'));
      await page.waitForFunction(before => Number(document.querySelector('#trace-position').getAttribute('aria-valuenow')) > before, position);
    };
    const checkCompleted = async example => {
      await traceReady();
      await page.click('#trace-end');
      for (const route of example.routes) {
        const n = route.expandedStates.toLocaleString('ko-KR');
        assert.equal(await page.textContent(`#trace-${route.algorithm}-count`), `${n} / ${n} · 완료`);
      }
    };
    await checkAutoplay();
    assert.equal(await page.getByRole('switch', {name: 'ALT', exact: true}).isChecked(), true);
    assert.equal(data.version, 2);
    assert.ok(data.examples.every(example => example.routes.map(r => r.algorithm).join(',') === 'dijkstra,astar,alt'));
    await page.click('#example');
    assert.equal(await page.getByRole('option').count(), 8);
    await page.keyboard.press('Escape');
    for (const example of data.examples) {
      await chooseExample(example.id);
      await checkAutoplay();
      await checkCompleted(example);
      assert.equal(await page.textContent('#route-name'), example.name);
      assert.equal(new URL(page.url()).hash, '#' + example.id);
      assert.equal(await page.locator('.endpoint').count(), 2);
      for (const result of example.routes) {
        assert.equal(await page.textContent(`#${result.algorithm}-edges`),result.inspectedEdges.toLocaleString('ko-KR'));
        assert.equal(await page.textContent(`#${result.algorithm}-queue`),result.maxQueueSize.toLocaleString('ko-KR'));
        if(result.algorithm !== 'dijkstra') assert.equal(await page.textContent(`#${result.algorithm}-speedup`),`${(result.baselineSearchMillis.median/result.searchMillis.median).toFixed(2)}×`);
        assert.equal(await page.textContent(`#${result.algorithm}-time`), result.searchMillis.median.toLocaleString('ko-KR', { maximumFractionDigits: 2 }));
      }
    }
    await chooseExample(data.examples[0].id);
    await checkCompleted(data.examples[0]);
    // Compare rendered pixels, not just checkbox state, to verify both expansion layers disappear.
    const colors = async () => {
      const buffer = await page.locator('.map-panel').screenshot();
      return page.evaluate(async encoded => {
        const image = new Image(); image.src = 'data:image/png;base64,' + encoded; await image.decode();
        const canvas = document.createElement('canvas'); canvas.width = image.width; canvas.height = image.height;
        const ctx = canvas.getContext('2d'); ctx.drawImage(image, 0, 0);
        const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        let blue = 0, orange = 0, green = 0;
        for (let i = 0; i < pixels.length; i += 4) {
          const [r,g,b] = [pixels[i],pixels[i+1],pixels[i+2]];
          if (b > r + 40 && b > g + 15 && b > 120) blue++;
          if (r > 140 && r > g * 1.35 && g > b * 1.3) orange++;
          if (g > r + 25 && g > b + 25 && g > 90) green++;
        }
        return {blue,orange,green};
      }, buffer.toString('base64'));
    };
    await page.waitForTimeout(500);
    const fullColors = await colors();
    await page.click('#trace-start');
    assert.equal(await page.getAttribute('#trace-position', 'aria-valuenow'), '0');
    await page.waitForTimeout(300);
    const emptyColors = await colors();
    assert.ok(fullColors.blue > emptyColors.blue + 1000, JSON.stringify({fullColors,emptyColors}));
    assert.ok(fullColors.orange > emptyColors.orange + 100, JSON.stringify({fullColors,emptyColors}));
    assert.ok(fullColors.green > emptyColors.green + 40, JSON.stringify({fullColors,emptyColors}));
    // Before ALT completes, green pixels can only come from its exploration points, not its final route.
    await page.locator('#trace-position').press('Home');
    for (let step = 0; step < 10; step++) await page.locator('#trace-position').press('PageUp');
    assert.doesNotMatch(await page.textContent('#trace-alt-count'), /완료$/);
    await page.waitForTimeout(300);
    const earlyAlt = await colors();
    await page.getByRole('switch', {name: 'ALT', exact: true}).uncheck();
    await page.waitForTimeout(200);
    const withoutAlt = await colors();
    assert.ok(earlyAlt.green > withoutAlt.green + 10, JSON.stringify({earlyAlt,withoutAlt}));
    await page.getByRole('switch', {name: 'ALT', exact: true}).check();
    await page.click('#trace-start');
    await page.click('#trace-play');
    await page.waitForFunction(() => Number(document.querySelector('#trace-position').getAttribute('aria-valuenow')) > 30);
    await page.click('#trace-play');
    const paused = await page.getAttribute('#trace-position', 'aria-valuenow');
    await page.waitForTimeout(250);
    assert.equal(await page.getAttribute('#trace-position', 'aria-valuenow'), paused);
    await page.locator('#trace-position').press('Home');
    for (let step = 0; step < 50; step++) await page.locator('#trace-position').press('PageUp');
    const max = Math.max(...data.examples[0].routes.map(r => r.expandedStates));
    const ordinal = Math.floor(max / 8);
    assert.equal(await page.textContent('#trace-count'), `같은 확장 횟수 ${ordinal.toLocaleString('ko-KR')}회`);
    assert.match(await page.textContent('#trace-astar-count'), /완료$/);
    assert.match(await page.textContent('#trace-alt-count'), /완료$/);
    assert.doesNotMatch(await page.textContent('#trace-dijkstra-count'), /완료$/);
    await page.click('#trace-end');
    await checkCompleted(data.examples[0]);
    await page.getByRole('switch', {name: '탐색 영역', exact: true}).uncheck();
    await page.waitForTimeout(200);
    const hiddenColors = await colors();
    assert.ok(fullColors.blue > hiddenColors.blue + 1000);
    await page.getByRole('switch', {name: '탐색 영역', exact: true}).check();
    await page.getByRole('switch', {name: 'A*', exact: true}).uncheck();
    await page.getByRole('switch', {name: 'ALT', exact: true}).uncheck();
    await chooseExample(data.examples[2].id);
    await checkCompleted(data.examples[2]);
    assert.equal(await page.getByRole('switch', {name: 'A*', exact: true}).isChecked(), false);
    assert.equal(await page.getByRole('switch', {name: 'ALT', exact: true}).isChecked(), false);
    await page.getByRole('switch', {name: 'ALT', exact: true}).check();
    await page.getByRole('switch', {name: 'A*', exact: true}).check();
    await page.click('#fit-route');
    await page.waitForTimeout(700);
    await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true });
    // Opening metrics must preserve the map dimensions and the user's zoomed view.
    await page.click('.maplibregl-ctrl-zoom-in');
    await page.waitForTimeout(700);
    const mapView = () => page.evaluate(() => {
      const map = document.querySelector('#demo-map');
      const canvas = map.querySelector('canvas');
      return { width: map.clientWidth, height: map.clientHeight, canvasWidth: canvas.width, canvasHeight: canvas.height,
        markers: [...map.querySelectorAll('.endpoint')].map(marker => marker.style.transform) };
    });
    const beforeMetrics = await mapView();
    for (const algorithm of ['dijkstra', 'astar', 'alt']) {
      await page.click(`.${algorithm} .work-metrics summary`);
      assert.ok(await page.locator(`#${algorithm}-edges`).isVisible());
      assert.ok(await page.locator(`#${algorithm}-queue`).isVisible());
      await page.waitForTimeout(150);
      assert.deepEqual(await mapView(), beforeMetrics, `${algorithm}: opening metrics changed the map`);
    }
    await page.screenshot({ path: path.join(output, 'work-metrics.png'), fullPage: true });
    for (const algorithm of ['dijkstra', 'astar', 'alt']) {
      await page.click(`.${algorithm} .work-metrics summary`);
      await page.waitForTimeout(150);
      assert.deepEqual(await mapView(), beforeMetrics, `${algorithm}: closing metrics changed the map`);
    }
    await page.click('.methodology summary');
    assert.match(await page.textContent('#landmark-method'), /전처리를 제외/);
    assert.match(await page.textContent('#alt-method-settings'), /별도 실행/);
    const downloadPromise = page.waitForEvent('download');
    await page.click('#download-data');
    const download = await downloadPromise;
    const downloaded = JSON.parse(fs.readFileSync(await download.path(), 'utf8'));
    assert.deepEqual(downloaded, data);
    await page.goto(base + '#' + data.examples[4].id);
    await page.waitForSelector('#example');
    assert.equal(await page.textContent('#example'), data.examples[4].name);
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForFunction(() => document.querySelector('#fit-route')?.disabled === false);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    await chooseExample(data.examples[1].id);
    await checkCompleted(data.examples[1]);
    await page.waitForTimeout(700);
    assert.ok((await colors()).blue > 1000, 'Mobile exploration layer must render');
    await page.locator('.methodology details').evaluate(e => { e.open = false; });
    await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
    // A cached two-algorithm trace cannot be mistaken for the new three-algorithm dataset.
    const stale = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../frontend/demo/public/traces/' + data.examples[3].id + '.json'), 'utf8'));
    stale.version = 1;
    stale.traces = stale.traces.filter(trace => trace.algorithm !== 'alt');
    await page.route('**/traces/' + data.examples[3].id + '.json', route => route.fulfill({json: stale}));
    await chooseExample(data.examples[3].id);
    await page.waitForFunction(() => !document.querySelector('#trace-retry').hidden);
    assert.equal(await page.isDisabled('#trace-play'), true);
    assert.match(await page.textContent('#trace-status'), /일치하지 않습니다/);
    await page.unroute('**/traces/' + data.examples[3].id + '.json');
    await page.click('#trace-retry');
    await checkAutoplay();
    // Loading failures can be retried without losing the metric cards.
    await page.route('**/traces/' + data.examples[0].id + '.json', route => route.abort());
    await chooseExample(data.examples[0].id);
    await page.waitForFunction(() => !document.querySelector('#trace-retry').hidden);
    assert.equal(await page.isDisabled('#trace-play'), true);
    assert.equal(await page.textContent('#route-name'), data.examples[0].name);
    await page.unroute('**/traces/' + data.examples[0].id + '.json');
    await page.click('#trace-retry');
    await checkAutoplay();
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
    await chooseExample(delayed.id); await seen;
    assert.equal(await page.getAttribute('#trace-position', 'aria-valuenow'), '0');
    assert.equal(await page.isDisabled('#trace-play'), true);
    await chooseExample(data.examples[7].id);
    await checkAutoplay();
    await checkCompleted(data.examples[7]); release();
    await page.waitForTimeout(200);
    assert.equal(await page.getAttribute('#trace-play', 'aria-pressed'), 'false', 'Stale trace must not restart playback');
    assert.equal(await page.getAttribute('#trace-position', 'aria-valuenow'), '1000');
    await checkCompleted(data.examples[7]);
    await page.unroute('**/traces/' + delayed.id + '.json');
    await page.click('#trace-play');
    await page.waitForFunction(() => Number(document.querySelector('#trace-position').getAttribute('aria-valuenow')) > 20);
    await chooseExample(data.examples[1].id);
    await checkAutoplay();
    await page.waitForFunction(() => document.querySelector('#trace-position').getAttribute('aria-valuenow') === '1000' && document.querySelector('#trace-play').getAttribute('aria-pressed') === 'false', {}, {timeout: 20000});
    await checkCompleted(data.examples[1]);
    // Astryx uses a bottom sheet on touch screens, with the same route/replay contract.
    const touch = await browser.newContext({viewport: {width: 390, height: 844}, isMobile: true, hasTouch: true});
    const phone = await touch.newPage();
    phone.on('pageerror', error => errors.push(error.message));
    await phone.goto(base);
    await phone.waitForSelector('#trace-play:not([disabled])', {timeout: 60000});
    await phone.tap('#example');
    assert.equal(await phone.getByRole('option').count(), 8);
    await phone.getByRole('option', {name: data.examples[2].name, exact: true}).tap();
    await phone.waitForSelector('#trace-play:not([disabled])');
    assert.equal(await phone.getAttribute('#trace-play', 'aria-pressed'), 'true');
    assert.ok(Number(await phone.getAttribute('#trace-position', 'aria-valuenow')) < 250);
    await phone.click('#trace-end');
    await phone.getByRole('switch', {name: 'A*', exact: true}).uncheck();
    assert.equal(await phone.getByRole('switch', {name: 'A*', exact: true}).isChecked(), false);
    await phone.getByRole('switch', {name: 'A*', exact: true}).check();
    await phone.getByRole('switch', {name: 'ALT', exact: true}).uncheck();
    assert.equal(await phone.getByRole('switch', {name: 'ALT', exact: true}).isChecked(), false);
    await phone.getByRole('switch', {name: 'ALT', exact: true}).check();
    for (const width of [390, 320, 768]) {
      await phone.setViewportSize({width, height: 844});
      await phone.locator('.work-metrics').evaluateAll(items => items.forEach(item => { item.open = true; }));
      await phone.waitForTimeout(400);
      assert.ok(await phone.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `Layout overflow at ${width}px`);
    }
    await touch.close();
    // Metrics and example selection still work if the external map is unavailable.
    await page.route('https://tiles.openfreemap.org/**', route => route.abort());
    await page.reload();
    await page.waitForFunction(() => document.querySelector('#map-status')?.textContent.includes('불러오지 못했습니다'));
    await chooseExample(data.examples[7].id);
    assert.equal(await page.textContent('#route-name'), data.examples[7].name);
    assert.deepEqual(apiRequests, []);
    assert.deepEqual(badAssets, []);
    assert.ok(workers.includes(200));
    assert.deepEqual(errors, []);
    console.log('PASS: 8 examples × 3 algorithms (Dijkstra/A*/ALT), green expansion pixels before completion, stale v1 rejection, automatic replay on selection, natural completion, expansion colors, playback/pause/scrub, visibility, stale responses, retry, real metrics, Astryx selector and keyboard slider, touch sheet, 320/390/768 layouts, links/download, mobile, map failure, no API, Worker 200');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
